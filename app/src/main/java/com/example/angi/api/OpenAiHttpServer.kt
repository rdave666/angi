package com.example.angi.api

import android.util.Log
import com.example.angi.domain.conversation.Message
import com.example.angi.domain.inference.GenerationEvent
import com.example.angi.domain.inference.GenerationRequest
import com.example.angi.domain.inference.InferenceEngine
import com.example.angi.domain.inference.RuntimeState
import com.example.angi.data.models.ModelRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.UUID

/**
 * Embedded HTTP server exposing OpenAI-compatible REST and SSE endpoints.
 * All inference is routed strictly through the provided [InferenceEngine].
 */
class OpenAiHttpServer(
    val host: String = "127.0.0.1",
    val port: Int = 8080,
    val apiKey: String = "",
    val requireAuth: Boolean = false,
    private val inferenceEngine: InferenceEngine,
    private val modelRepository: ModelRepository? = null
) {
    private val tag = "OpenAiHttpServer"
    private var serverSocket: ServerSocket? = null
    private var serverJob: Job? = null
    private val serverScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val inferenceLock = Mutex()

    private fun logI(tag: String, msg: String) {
        try {
            Log.i(tag, msg)
        } catch (_: Throwable) {
            println("[$tag] $msg")
        }
    }

    private fun logW(tag: String, msg: String) {
        try {
            Log.w(tag, msg)
        } catch (_: Throwable) {
            println("[$tag] $msg")
        }
    }

    @Volatile
    var isRunning: Boolean = false
        private set

    @Volatile
    var boundPort: Int = port
        private set

    @Synchronized
    fun start(): Int {
        if (isRunning) return boundPort

        val bindAddr = InetAddress.getByName(host)
        val socket = ServerSocket().apply {
            reuseAddress = true
            bind(InetSocketAddress(bindAddr, port))
        }
        serverSocket = socket
        boundPort = socket.localPort
        isRunning = true

        serverJob = serverScope.launch {
            logI(tag, "OpenAI API server started on http://$host:$boundPort (requireAuth=$requireAuth)")
            while (isActive && !socket.isClosed) {
                try {
                    val clientSocket = socket.accept()
                    launch {
                        handleClient(clientSocket)
                    }
                } catch (e: Exception) {
                    if (socket.isClosed || !isActive) break
                    logW(tag, "Socket accept error: ${e.message}")
                }
            }
        }
        return boundPort
    }

    @Synchronized
    fun stop() {
        if (!isRunning) return
        isRunning = false
        try {
            serverSocket?.close()
        } catch (_: Exception) {}
        serverSocket = null
        serverJob?.cancel()
        serverJob = null
        logI(tag, "OpenAI API server stopped.")
    }

    private suspend fun handleClient(socket: Socket) = withContext(Dispatchers.IO) {
        var currentInferenceJob: Job? = null
        try {
            socket.soTimeout = 60000 // 60s read timeout
            val inputStream = socket.getInputStream()
            val outputStream = socket.getOutputStream()

            val request = parseHttpRequest(inputStream) ?: run {
                sendError(outputStream, 400, "Bad Request", "bad_request", "Malformed HTTP request")
                socket.close()
                return@withContext
            }

            // Authentication enforcement:
            // LAN mode (host 0.0.0.0 or requireAuth) requires Bearer token
            val isLoopback = socket.inetAddress.isLoopbackAddress
            val needsAuth = requireAuth || !isLoopback || host == "0.0.0.0"
            if (needsAuth) {
                val authHeader = request.headers["authorization"] ?: ""
                val expected = "Bearer $apiKey"
                if (authHeader.isBlank() || authHeader != expected) {
                    sendError(outputStream, 401, "Invalid API Key", "invalid_api_key", "Bearer API key required")
                    socket.close()
                    return@withContext
                }
            }

            val path = request.path.split("?").first()
            when {
                request.method == "OPTIONS" -> {
                    sendCorsResponse(outputStream)
                }
                request.method == "GET" && path == "/health" -> {
                    handleHealth(outputStream)
                }
                request.method == "GET" && (path == "/v1/models" || path == "/models") -> {
                    handleModels(outputStream)
                }
                request.method == "POST" && (path == "/v1/chat/completions" || path == "/chat/completions") -> {
                    handleChatCompletions(request, socket, outputStream) { job ->
                        currentInferenceJob = job
                    }
                }
                request.method == "POST" && (path == "/v1/completions" || path == "/completions") -> {
                    handleCompletions(request, socket, outputStream) { job ->
                        currentInferenceJob = job
                    }
                }
                else -> {
                    sendError(outputStream, 404, "Not Found", "not_found", "The requested endpoint does not exist: $path")
                }
            }
        } catch (e: Exception) {
            if (e !is SocketException) {
                logW(tag, "Client connection error: ${e.message}")
            }
        } finally {
            try {
                socket.close()
            } catch (_: Exception) {}
        }
    }

    private fun handleHealth(out: OutputStream) {
        val info = inferenceEngine.runtimeInfo()
        val json = JSONObject().apply {
            put("status", "ok")
            put("model_loaded", info.isModelLoaded)
            put("model", info.loadedModelId ?: JSONObject.NULL)
            put("compute", info.computeUnit)
            put("backend", info.backend)
        }
        sendJsonResponse(out, 200, json.toString())
    }

    private suspend fun handleModels(out: OutputStream) {
        val info = inferenceEngine.runtimeInfo()
        val modelsArray = JSONArray()

        if (info.isModelLoaded && info.loadedModelId != null) {
            val modelId = info.loadedModelId!!
            val modelName = runCatching {
                modelRepository?.getModelById(modelId)?.name
            }.getOrNull() ?: modelId

            modelsArray.put(JSONObject().apply {
                put("id", modelId)
                put("object", "model")
                put("created", 1700000000L)
                put("owned_by", "angi")
                put("name", modelName)
                put("backend", info.backend)
                put("compute_unit", info.computeUnit)
            })
        }

        val resp = JSONObject().apply {
            put("object", "list")
            put("data", modelsArray)
        }
        sendJsonResponse(out, 200, resp.toString())
    }

    private suspend fun handleChatCompletions(
        request: HttpRequest,
        socket: Socket,
        out: OutputStream,
        onJobStarted: (Job) -> Unit
    ) {
        // Runtime rule 1: No loaded model -> 503
        val info = inferenceEngine.runtimeInfo()
        if (!info.isModelLoaded || info.loadedModelId == null) {
            sendError(out, 503, "No model loaded", "no_model_loaded", "No model is currently loaded in ANGI. Load a model first.")
            return
        }

        // Runtime rule 2: Generation already active / busy -> 429
        if (info.runtimeState == RuntimeState.GENERATION_ACTIVE || !inferenceLock.tryLock()) {
            sendError(out, 429, "Rate limit exceeded", "rate_limit_exceeded", "Inference engine is currently busy with another request.")
            return
        }

        try {
            val bodyJson = runCatching { JSONObject(request.body) }.getOrNull()
            if (bodyJson == null) {
                sendError(out, 400, "Invalid JSON", "invalid_request_error", "Request body must be valid JSON.")
                return
            }

            val isStream = bodyJson.optBoolean("stream", false)
            val maxTokens = bodyJson.optInt("max_tokens", 1024)
            val temperature = bodyJson.optDouble("temperature", 0.7).toFloat()
            val topP = bodyJson.optDouble("top_p", 0.9).toFloat()
            val stopWords = parseStopWords(bodyJson.opt("stop"))

            val messagesArray = bodyJson.optJSONArray("messages") ?: JSONArray()
            val domainMessages = mutableListOf<Message>()
            var systemPrompt: String? = null

            for (i in 0 until messagesArray.length()) {
                val msgObj = messagesArray.optJSONObject(i) ?: continue
                val role = msgObj.optString("role", "user").lowercase()
                val content = msgObj.optString("content", "")
                when (role) {
                    "system" -> systemPrompt = content
                    "assistant" -> domainMessages.add(
                        Message.Assistant(
                            id = UUID.randomUUID().toString(),
                            conversationId = "api_session",
                            text = content
                        )
                    )
                    else -> domainMessages.add(
                        Message.User(
                            id = UUID.randomUUID().toString(),
                            conversationId = "api_session",
                            text = content
                        )
                    )
                }
            }

            // Format chat prompt using template or fallback
            val formattedPrompt = formatChatPrompt(domainMessages, systemPrompt)
            val genRequest = GenerationRequest(
                prompt = formattedPrompt,
                maxTokens = maxTokens,
                temperature = temperature,
                topP = topP,
                stopWords = stopWords,
                systemPrompt = systemPrompt
            )

            val reqId = "chatcmpl-" + UUID.randomUUID().toString().replace("-", "").take(16)
            val created = System.currentTimeMillis() / 1000
            val loadedModelId = info.loadedModelId

            if (isStream) {
                handleStreamingChat(reqId, created, loadedModelId, genRequest, socket, out, onJobStarted)
            } else {
                handleNonStreamingChat(reqId, created, loadedModelId, genRequest, out)
            }
        } finally {
            inferenceLock.unlock()
        }
    }

    private suspend fun handleCompletions(
        request: HttpRequest,
        socket: Socket,
        out: OutputStream,
        onJobStarted: (Job) -> Unit
    ) {
        val info = inferenceEngine.runtimeInfo()
        if (!info.isModelLoaded || info.loadedModelId == null) {
            sendError(out, 503, "No model loaded", "no_model_loaded", "No model is currently loaded in ANGI.")
            return
        }

        if (info.runtimeState == RuntimeState.GENERATION_ACTIVE || !inferenceLock.tryLock()) {
            sendError(out, 429, "Rate limit exceeded", "rate_limit_exceeded", "Inference engine is currently busy.")
            return
        }

        try {
            val bodyJson = runCatching { JSONObject(request.body) }.getOrNull()
            if (bodyJson == null) {
                sendError(out, 400, "Invalid JSON", "invalid_request_error", "Request body must be valid JSON.")
                return
            }

            val prompt = bodyJson.optString("prompt", "")
            val isStream = bodyJson.optBoolean("stream", false)
            val maxTokens = bodyJson.optInt("max_tokens", 1024)
            val temperature = bodyJson.optDouble("temperature", 0.7).toFloat()
            val topP = bodyJson.optDouble("top_p", 0.9).toFloat()
            val stopWords = parseStopWords(bodyJson.opt("stop"))

            val genRequest = GenerationRequest(
                prompt = prompt,
                maxTokens = maxTokens,
                temperature = temperature,
                topP = topP,
                stopWords = stopWords
            )

            val reqId = "cmpl-" + UUID.randomUUID().toString().replace("-", "").take(16)
            val created = System.currentTimeMillis() / 1000
            val loadedModelId = info.loadedModelId

            if (isStream) {
                handleStreamingCompletions(reqId, created, loadedModelId, genRequest, socket, out, onJobStarted)
            } else {
                handleNonStreamingCompletions(reqId, created, loadedModelId, genRequest, out)
            }
        } finally {
            inferenceLock.unlock()
        }
    }

    private suspend fun formatChatPrompt(messages: List<Message>, systemPrompt: String?): String {
        val templateResult = inferenceEngine.applyChatTemplate(messages, emptyList(), systemPrompt)
        if (templateResult.isSuccess) {
            return templateResult.getOrThrow()
        }

        // Universal ChatML fallback for models running locally
        return buildString {
            if (!systemPrompt.isNullOrBlank()) {
                append("<|im_start|>system\n").append(systemPrompt.trim()).append("<|im_end|>\n")
            }
            for (msg in messages) {
                when (msg) {
                    is Message.User -> append("<|im_start|>user\n").append(msg.text.trim()).append("<|im_end|>\n")
                    is Message.Assistant -> append("<|im_start|>assistant\n").append(msg.text.trim()).append("<|im_end|>\n")
                    is Message.Tool -> append("<|im_start|>tool\n").append(msg.result.output.trim()).append("<|im_end|>\n")
                }
            }
            append("<|im_start|>assistant\n")
        }
    }

    private suspend fun handleNonStreamingChat(
        id: String,
        created: Long,
        modelId: String,
        request: GenerationRequest,
        out: OutputStream
    ) {
        val fullContent = StringBuilder()
        var promptTokens = 0L
        var completionTokens = 0L

        val flow = inferenceEngine.generate(request)
        flow.collect { event ->
            when (event) {
                is GenerationEvent.Token -> {
                    fullContent.append(event.text)
                    completionTokens++
                }
                is GenerationEvent.Metrics -> {
                    promptTokens = event.stats.promptTokens
                    if (event.stats.generatedTokens > 0) {
                        completionTokens = event.stats.generatedTokens
                    }
                }
                is GenerationEvent.Error -> {
                    throw event.throwable
                }
                is GenerationEvent.Completed -> {}
                is GenerationEvent.ToolRequest -> {}
            }
        }

        val resp = JSONObject().apply {
            put("id", id)
            put("object", "chat.completion")
            put("created", created)
            put("model", modelId)
            put("choices", JSONArray().apply {
                put(JSONObject().apply {
                    put("index", 0)
                    put("message", JSONObject().apply {
                        put("role", "assistant")
                        put("content", fullContent.toString())
                    })
                    put("finish_reason", "stop")
                })
            })
            put("usage", JSONObject().apply {
                put("prompt_tokens", promptTokens)
                put("completion_tokens", completionTokens)
                put("total_tokens", promptTokens + completionTokens)
            })
        }
        sendJsonResponse(out, 200, resp.toString())
    }

    private suspend fun handleStreamingChat(
        id: String,
        created: Long,
        modelId: String,
        request: GenerationRequest,
        socket: Socket,
        out: OutputStream,
        onJobStarted: (Job) -> Unit
    ) {
        val sseHeaders = "HTTP/1.1 200 OK\r\n" +
                "Content-Type: text/event-stream; charset=utf-8\r\n" +
                "Cache-Control: no-cache\r\n" +
                "Connection: keep-alive\r\n" +
                "Access-Control-Allow-Origin: *\r\n\r\n"

        try {
            out.write(sseHeaders.toByteArray(Charsets.UTF_8))
            out.flush()
        } catch (e: Exception) {
            inferenceEngine.cancel()
            return
        }

        // Initial role chunk
        val initialChunk = JSONObject().apply {
            put("id", id)
            put("object", "chat.completion.chunk")
            put("created", created)
            put("model", modelId)
            put("choices", JSONArray().apply {
                put(JSONObject().apply {
                    put("index", 0)
                    put("delta", JSONObject().apply {
                        put("role", "assistant")
                    })
                    put("finish_reason", JSONObject.NULL)
                })
            })
        }
        if (!sendSseChunk(out, initialChunk.toString())) {
            inferenceEngine.cancel()
            return
        }

        val currentJob = CoroutineScope(Dispatchers.IO).launch {
            try {
                val flow = inferenceEngine.generate(request)
                flow.collect { event ->
                    when (event) {
                        is GenerationEvent.Token -> {
                            val chunk = JSONObject().apply {
                                put("id", id)
                                put("object", "chat.completion.chunk")
                                put("created", created)
                                put("model", modelId)
                                put("choices", JSONArray().apply {
                                    put(JSONObject().apply {
                                        put("index", 0)
                                        put("delta", JSONObject().apply {
                                            put("content", event.text)
                                        })
                                        put("finish_reason", JSONObject.NULL)
                                    })
                                })
                            }
                            if (!sendSseChunk(out, chunk.toString())) {
                                inferenceEngine.cancel()
                                throw CancellationException("Client disconnected")
                            }
                        }
                        is GenerationEvent.Completed -> {
                            val finalChunk = JSONObject().apply {
                                put("id", id)
                                put("object", "chat.completion.chunk")
                                put("created", created)
                                put("model", modelId)
                                put("choices", JSONArray().apply {
                                    put(JSONObject().apply {
                                        put("index", 0)
                                        put("delta", JSONObject())
                                        put("finish_reason", "stop")
                                    })
                                })
                            }
                            sendSseChunk(out, finalChunk.toString())
                            sendSseRaw(out, "data: [DONE]\n\n")
                        }
                        is GenerationEvent.Error -> {
                            sendSseRaw(out, "data: [DONE]\n\n")
                        }
                        else -> {}
                    }
                }
            } catch (t: Throwable) {
                // Disconnect or cancellation
                inferenceEngine.cancel()
            }
        }
        onJobStarted(currentJob)
        currentJob.join()
    }

    private suspend fun handleNonStreamingCompletions(
        id: String,
        created: Long,
        modelId: String,
        request: GenerationRequest,
        out: OutputStream
    ) {
        val fullContent = StringBuilder()
        var promptTokens = 0L
        var completionTokens = 0L

        val flow = inferenceEngine.generate(request)
        flow.collect { event ->
            when (event) {
                is GenerationEvent.Token -> {
                    fullContent.append(event.text)
                    completionTokens++
                }
                is GenerationEvent.Metrics -> {
                    promptTokens = event.stats.promptTokens
                    if (event.stats.generatedTokens > 0) {
                        completionTokens = event.stats.generatedTokens
                    }
                }
                is GenerationEvent.Error -> {
                    throw event.throwable
                }
                is GenerationEvent.Completed -> {}
                is GenerationEvent.ToolRequest -> {}
            }
        }

        val resp = JSONObject().apply {
            put("id", id)
            put("object", "text_completion")
            put("created", created)
            put("model", modelId)
            put("choices", JSONArray().apply {
                put(JSONObject().apply {
                    put("index", 0)
                    put("text", fullContent.toString())
                    put("finish_reason", "stop")
                })
            })
            put("usage", JSONObject().apply {
                put("prompt_tokens", promptTokens)
                put("completion_tokens", completionTokens)
                put("total_tokens", promptTokens + completionTokens)
            })
        }
        sendJsonResponse(out, 200, resp.toString())
    }

    private suspend fun handleStreamingCompletions(
        id: String,
        created: Long,
        modelId: String,
        request: GenerationRequest,
        socket: Socket,
        out: OutputStream,
        onJobStarted: (Job) -> Unit
    ) {
        val sseHeaders = "HTTP/1.1 200 OK\r\n" +
                "Content-Type: text/event-stream; charset=utf-8\r\n" +
                "Cache-Control: no-cache\r\n" +
                "Connection: keep-alive\r\n" +
                "Access-Control-Allow-Origin: *\r\n\r\n"

        try {
            out.write(sseHeaders.toByteArray(Charsets.UTF_8))
            out.flush()
        } catch (e: Exception) {
            inferenceEngine.cancel()
            return
        }

        val currentJob = CoroutineScope(Dispatchers.IO).launch {
            try {
                val flow = inferenceEngine.generate(request)
                flow.collect { event ->
                    when (event) {
                        is GenerationEvent.Token -> {
                            val chunk = JSONObject().apply {
                                put("id", id)
                                put("object", "text_completion")
                                put("created", created)
                                put("model", modelId)
                                put("choices", JSONArray().apply {
                                    put(JSONObject().apply {
                                        put("index", 0)
                                        put("text", event.text)
                                        put("finish_reason", JSONObject.NULL)
                                    })
                                })
                            }
                            if (!sendSseChunk(out, chunk.toString())) {
                                inferenceEngine.cancel()
                                throw CancellationException("Client disconnected")
                            }
                        }
                        is GenerationEvent.Completed -> {
                            val finalChunk = JSONObject().apply {
                                put("id", id)
                                put("object", "text_completion")
                                put("created", created)
                                put("model", modelId)
                                put("choices", JSONArray().apply {
                                    put(JSONObject().apply {
                                        put("index", 0)
                                        put("text", "")
                                        put("finish_reason", "stop")
                                    })
                                })
                            }
                            sendSseChunk(out, finalChunk.toString())
                            sendSseRaw(out, "data: [DONE]\n\n")
                        }
                        is GenerationEvent.Error -> {
                            sendSseRaw(out, "data: [DONE]\n\n")
                        }
                        else -> {}
                    }
                }
            } catch (t: Throwable) {
                inferenceEngine.cancel()
            }
        }
        onJobStarted(currentJob)
        currentJob.join()
    }

    private fun sendSseChunk(out: OutputStream, jsonStr: String): Boolean {
        return sendSseRaw(out, "data: $jsonStr\n\n")
    }

    private fun sendSseRaw(out: OutputStream, rawText: String): Boolean {
        return try {
            out.write(rawText.toByteArray(Charsets.UTF_8))
            out.flush()
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun sendJsonResponse(out: OutputStream, statusCode: Int, body: String) {
        val statusText = when (statusCode) {
            200 -> "OK"
            400 -> "Bad Request"
            401 -> "Unauthorized"
            404 -> "Not Found"
            429 -> "Too Many Requests"
            503 -> "Service Unavailable"
            else -> "Error"
        }
        val bytes = body.toByteArray(Charsets.UTF_8)
        val header = "HTTP/1.1 $statusCode $statusText\r\n" +
                "Content-Type: application/json; charset=utf-8\r\n" +
                "Content-Length: ${bytes.size}\r\n" +
                "Connection: close\r\n" +
                "Access-Control-Allow-Origin: *\r\n" +
                "Access-Control-Allow-Headers: Authorization, Content-Type\r\n" +
                "Access-Control-Allow-Methods: GET, POST, OPTIONS\r\n\r\n"
        out.write(header.toByteArray(Charsets.UTF_8))
        out.write(bytes)
        out.flush()
    }

    private fun sendCorsResponse(out: OutputStream) {
        val header = "HTTP/1.1 204 No Content\r\n" +
                "Connection: close\r\n" +
                "Access-Control-Allow-Origin: *\r\n" +
                "Access-Control-Allow-Headers: Authorization, Content-Type\r\n" +
                "Access-Control-Allow-Methods: GET, POST, OPTIONS\r\n\r\n"
        out.write(header.toByteArray(Charsets.UTF_8))
        out.flush()
    }

    private fun sendError(out: OutputStream, status: Int, message: String, type: String, details: String) {
        val errorJson = JSONObject().apply {
            put("error", JSONObject().apply {
                put("message", details)
                put("type", type)
                put("code", type)
            })
        }
        sendJsonResponse(out, status, errorJson.toString())
    }

    private fun parseStopWords(stopObj: Any?): List<String> {
        return when (stopObj) {
            is String -> if (stopObj.isNotBlank()) listOf(stopObj) else emptyList()
            is JSONArray -> {
                val list = mutableListOf<String>()
                for (i in 0 until stopObj.length()) {
                    val s = stopObj.optString(i)
                    if (!s.isNullOrBlank()) list.add(s)
                }
                list
            }
            else -> emptyList()
        }
    }

    private fun parseHttpRequest(inputStream: InputStream): HttpRequest? {
        val reader = BufferedReader(InputStreamReader(inputStream, Charsets.UTF_8))
        val requestLine = reader.readLine() ?: return null
        val parts = requestLine.split(" ")
        if (parts.size < 2) return null

        val method = parts[0].uppercase()
        val path = parts[1]
        val headers = mutableMapOf<String, String>()

        var line = reader.readLine()
        while (!line.isNullOrBlank()) {
            val colonIdx = line.indexOf(':')
            if (colonIdx > 0) {
                val name = line.substring(0, colonIdx).trim().lowercase()
                val value = line.substring(colonIdx + 1).trim()
                headers[name] = value
            }
            line = reader.readLine()
        }

        val contentLength = headers["content-length"]?.toIntOrNull() ?: 0
        val body = if (contentLength > 0) {
            val buffer = CharArray(contentLength)
            var readTotal = 0
            while (readTotal < contentLength) {
                val read = reader.read(buffer, readTotal, contentLength - readTotal)
                if (read == -1) break
                readTotal += read
            }
            String(buffer, 0, readTotal)
        } else {
            ""
        }

        return HttpRequest(method = method, path = path, headers = headers, body = body)
    }

    private data class HttpRequest(
        val method: String,
        val path: String,
        val headers: Map<String, String>,
        val body: String
    )
}
