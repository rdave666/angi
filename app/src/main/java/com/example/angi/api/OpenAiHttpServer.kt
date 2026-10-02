package com.example.angi.api

import android.util.Log
import com.example.angi.data.models.ModelRepository
import com.example.angi.domain.conversation.Message
import com.example.angi.domain.inference.GenerationEvent
import com.example.angi.domain.inference.GenerationRequest
import com.example.angi.domain.inference.InferenceEngine
import com.example.angi.domain.inference.RuntimeState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.UUID

/**
 * Embedded HTTP server exposing OpenAI-compatible REST and SSE endpoints.
 * All inference is routed strictly through the single active [InferenceEngine].
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
            bind(InetSocketAddress(bindAddr, port))
        }
        serverSocket = socket
        boundPort = socket.localPort
        isRunning = true

        serverJob = serverScope.launch {
            Log.i(tag, "OpenAI API server started on http://$host:$boundPort (requireAuth=$requireAuth)")
            while (isActive && !socket.isClosed) {
                try {
                    val clientSocket = socket.accept()
                    launch {
                        handleClient(clientSocket)
                    }
                } catch (e: Exception) {
                    if (socket.isClosed || !isActive) break
                    Log.w(tag, "Socket accept error: ${e.message}")
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
        Log.i(tag, "OpenAI API server stopped.")
    }

    private suspend fun handleClient(socket: Socket) = withContext(Dispatchers.IO) {
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
                    handleChatCompletions(request, socket, outputStream)
                }
                request.method == "POST" && (path == "/v1/completions" || path == "/completions") -> {
                    handleCompletions(request, socket, outputStream)
                }
                else -> {
                    sendError(outputStream, 404, "Not Found", "not_found", "The requested endpoint does not exist: $path")
                }
            }
        } catch (e: Exception) {
            if (e !is SocketException) {
                Log.w(tag, "Client connection error: ${e.message}")
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

            // Primary active model entry
            modelsArray.put(JSONObject().apply {
                put("id", modelId)
                put("object", "model")
                put("created", 1700000000L)
                put("owned_by", "angi")
                put("name", modelName)
                put("backend", info.backend)
                put("compute_unit", info.computeUnit)
            })

            // Stable alias entry
            modelsArray.put(JSONObject().apply {
                put("id", "angi-loaded-model")
                put("object", "model")
                put("created", 1700000000L)
                put("owned_by", "angi")
                put("name", "$modelName (Current Active Alias)")
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
        out: OutputStream
    ) {
        // Runtime rule 1: No loaded model -> 503
        val info = inferenceEngine.runtimeInfo()
        if (!info.isModelLoaded || info.loadedModelId == null) {
            sendError(out, 503, "No model loaded", "no_model_loaded", "No model is currently loaded in ANGI. Load a model first.")
            return
        }

        val bodyJson = runCatching { JSONObject(request.body) }.getOrNull()
        if (bodyJson == null) {
            sendError(out, 400, "Invalid JSON", "invalid_request_error", "Request body must be valid JSON.")
            return
        }

        // Model field validation:
        // Accept active model ID or stable alias "angi-loaded-model", reject other IDs with 400
        val requestedModel = bodyJson.optString("model", "").trim()
        val activeModelId = info.loadedModelId!!
        if (requestedModel.isNotEmpty()) {
            val matchesActive = requestedModel.equals(activeModelId, ignoreCase = true)
            val matchesAlias = requestedModel.equals("angi-loaded-model", ignoreCase = true)
            if (!matchesActive && !matchesAlias) {
                sendError(
                    out,
                    400,
                    "Invalid model",
                    "invalid_request_error",
                    "The model '$requestedModel' does not exist or is not loaded. Currently loaded model is '$activeModelId' (or alias 'angi-loaded-model')."
                )
                return
            }
        }

        // Runtime rule 2: Generation already active / busy -> 429
        if (info.runtimeState == RuntimeState.GENERATION_ACTIVE || !inferenceLock.tryLock()) {
            sendError(out, 429, "Rate limit exceeded", "rate_limit_exceeded", "Inference engine is currently busy with another request.")
            return
        }

        try {
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

            runInferenceWithDisconnectWatcher(socket) {
                if (isStream) {
                    executeStreamingChat(reqId, created, activeModelId, genRequest, out)
                } else {
                    executeNonStreamingChat(reqId, created, activeModelId, genRequest, out)
                }
            }
        } finally {
            inferenceLock.unlock()
        }
    }

    private suspend fun handleCompletions(
        request: HttpRequest,
        socket: Socket,
        out: OutputStream
    ) {
        val info = inferenceEngine.runtimeInfo()
        if (!info.isModelLoaded || info.loadedModelId == null) {
            sendError(out, 503, "No model loaded", "no_model_loaded", "No model is currently loaded in ANGI.")
            return
        }

        val bodyJson = runCatching { JSONObject(request.body) }.getOrNull()
        if (bodyJson == null) {
            sendError(out, 400, "Invalid JSON", "invalid_request_error", "Request body must be valid JSON.")
            return
        }

        val requestedModel = bodyJson.optString("model", "").trim()
        val activeModelId = info.loadedModelId!!
        if (requestedModel.isNotEmpty()) {
            val matchesActive = requestedModel.equals(activeModelId, ignoreCase = true)
            val matchesAlias = requestedModel.equals("angi-loaded-model", ignoreCase = true)
            if (!matchesActive && !matchesAlias) {
                sendError(
                    out,
                    400,
                    "Invalid model",
                    "invalid_request_error",
                    "The model '$requestedModel' does not exist or is not loaded. Currently loaded model is '$activeModelId' (or alias 'angi-loaded-model')."
                )
                return
            }
        }

        if (info.runtimeState == RuntimeState.GENERATION_ACTIVE || !inferenceLock.tryLock()) {
            sendError(out, 429, "Rate limit exceeded", "rate_limit_exceeded", "Inference engine is currently busy.")
            return
        }

        try {
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

            runInferenceWithDisconnectWatcher(socket) {
                if (isStream) {
                    executeStreamingCompletions(reqId, created, activeModelId, genRequest, out)
                } else {
                    executeNonStreamingCompletions(reqId, created, activeModelId, genRequest, out)
                }
            }
        } finally {
            inferenceLock.unlock()
        }
    }

    /**
     * Executes the given inference block while actively monitoring the client socket for disconnects.
     * If the client closes the connection during streaming or non-streaming execution,
     * the inference block is cancelled immediately and [InferenceEngine.cancel] is invoked.
     */
    private suspend fun runInferenceWithDisconnectWatcher(
        socket: Socket,
        block: suspend () -> Unit
    ) {
        var completedNormally = false
        val currentJob = kotlinx.coroutines.currentCoroutineContext()[Job]

        // Parallel watcher that detects TCP FIN/RST or socket close from the client
        val watcherJob = serverScope.launch(Dispatchers.IO) {
            try {
                val buf = ByteArray(1)
                val read = socket.getInputStream().read(buf)
                if (read == -1) {
                    Log.i(tag, "Client socket EOF detected during active inference, cancelling...")
                    currentJob?.cancel(CancellationException("Client disconnected (EOF)"))
                }
            } catch (_: SocketException) {
                Log.i(tag, "Client socket reset detected during active inference, cancelling...")
                currentJob?.cancel(CancellationException("Client socket closed"))
            } catch (_: IOException) {
                currentJob?.cancel(CancellationException("Client socket I/O error"))
            }
        }

        try {
            block()
            completedNormally = true
        } finally {
            watcherJob.cancel()
            try {
                socket.shutdownInput()
            } catch (_: Throwable) {}
            if (!completedNormally) {
                try {
                    inferenceEngine.cancel()
                } catch (t: Throwable) {
                    Log.w(tag, "Error cancelling inference engine: ${t.message}")
                }
            }
        }
    }

    private suspend fun formatChatPrompt(messages: List<Message>, systemPrompt: String?): String {
        val templateResult = inferenceEngine.applyChatTemplate(messages, emptyList(), systemPrompt)
        if (templateResult.isSuccess) {
            return templateResult.getOrThrow()
        }

        // Universal ChatML fallback
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

    private suspend fun executeNonStreamingChat(
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

    private suspend fun executeStreamingChat(
        id: String,
        created: Long,
        modelId: String,
        request: GenerationRequest,
        out: OutputStream
    ) {
        val sseHeaders = "HTTP/1.1 200 OK\r\n" +
                "Content-Type: text/event-stream; charset=utf-8\r\n" +
                "Cache-Control: no-cache\r\n" +
                "Connection: keep-alive\r\n" +
                "Access-Control-Allow-Origin: *\r\n\r\n"

        out.write(sseHeaders.toByteArray(Charsets.UTF_8))
        out.flush()

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
        sendSseChunk(out, initialChunk.toString())

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
                    sendSseChunk(out, chunk.toString())
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
                    throw event.throwable
                }
                else -> {}
            }
        }
    }

    private suspend fun executeNonStreamingCompletions(
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

    private suspend fun executeStreamingCompletions(
        id: String,
        created: Long,
        modelId: String,
        request: GenerationRequest,
        out: OutputStream
    ) {
        val sseHeaders = "HTTP/1.1 200 OK\r\n" +
                "Content-Type: text/event-stream; charset=utf-8\r\n" +
                "Cache-Control: no-cache\r\n" +
                "Connection: keep-alive\r\n" +
                "Access-Control-Allow-Origin: *\r\n\r\n"

        out.write(sseHeaders.toByteArray(Charsets.UTF_8))
        out.flush()

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
                    sendSseChunk(out, chunk.toString())
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
                    throw event.throwable
                }
                else -> {}
            }
        }
    }

    private fun sendSseChunk(out: OutputStream, jsonStr: String) {
        sendSseRaw(out, "data: $jsonStr\n\n")
    }

    private fun sendSseRaw(out: OutputStream, rawText: String) {
        out.write(rawText.toByteArray(Charsets.UTF_8))
        out.flush()
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

    /**
     * Parses HTTP request line and headers using ASCII bytes,
     * and strictly reads exact Content-Length BYTES before UTF-8 decoding.
     */
    private fun parseHttpRequest(inputStream: InputStream): HttpRequest? {
        val headerBytes = ByteArrayOutputStream()
        var state = 0
        var b: Int
        while (inputStream.read().also { b = it } != -1) {
            headerBytes.write(b)
            when (state) {
                0 -> if (b == '\r'.code) state = 1 else state = 0
                1 -> if (b == '\n'.code) state = 2 else if (b == '\r'.code) state = 1 else state = 0
                2 -> if (b == '\r'.code) state = 3 else state = 0
                3 -> if (b == '\n'.code) {
                    state = 4
                    break
                } else if (b == '\r'.code) {
                    state = 1
                } else {
                    state = 0
                }
            }
        }
        if (state != 4) return null

        val headerString = headerBytes.toString(Charsets.US_ASCII.name())
        val lines = headerString.split("\r\n")
        if (lines.isEmpty()) return null

        val requestLine = lines[0]
        val parts = requestLine.split(" ")
        if (parts.size < 2) return null

        val method = parts[0].uppercase()
        val path = parts[1]
        val headers = mutableMapOf<String, String>()

        for (i in 1 until lines.size) {
            val line = lines[i]
            val colonIdx = line.indexOf(':')
            if (colonIdx > 0) {
                val name = line.substring(0, colonIdx).trim().lowercase()
                val value = line.substring(colonIdx + 1).trim()
                headers[name] = value
            }
        }

        // Read exact byte Content-Length to avoid UTF-8 character length truncation/mismatch
        val contentLength = headers["content-length"]?.toIntOrNull() ?: 0
        val body = if (contentLength > 0) {
            val bodyBytes = ByteArray(contentLength)
            var totalRead = 0
            while (totalRead < contentLength) {
                val count = inputStream.read(bodyBytes, totalRead, contentLength - totalRead)
                if (count == -1) break
                totalRead += count
            }
            if (totalRead < contentLength) {
                return null
            }
            String(bodyBytes, 0, totalRead, Charsets.UTF_8)
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
