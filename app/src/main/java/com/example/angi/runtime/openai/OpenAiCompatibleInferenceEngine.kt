package com.example.angi.runtime.openai

import com.example.angi.data.settings.SettingsRepository
import com.example.angi.domain.conversation.Message
import com.example.angi.domain.inference.GenerationEvent
import com.example.angi.domain.inference.GenerationMetrics
import com.example.angi.domain.inference.GenerationRequest
import com.example.angi.domain.inference.InferenceEngine
import com.example.angi.domain.inference.RuntimeInfo
import com.example.angi.domain.inference.RuntimeState
import com.example.angi.domain.models.ModelDescriptor
import com.example.angi.domain.tools.ToolCall
import com.example.angi.domain.tools.ToolDefinition
import com.example.angi.domain.tools.ToolParameter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.util.UUID
import java.util.concurrent.TimeUnit

class OpenAiCompatibleInferenceEngine(
    private val settingsRepository: SettingsRepository,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()
) : InferenceEngine {

    @Volatile
    private var activeCall: Call? = null

    suspend fun listModels(): Result<List<String>> = withContext(Dispatchers.IO) {
        val settings = settingsRepository.settings.value
        runCatching {
            val baseUrl = normalizeBaseUrl(settings.externalApiBaseUrl)
            require(settings.externalApiKey.isNotBlank()) { "API key is required" }

            val request = Request.Builder()
                .url("$baseUrl/models")
                .header("Authorization", "Bearer ${settings.externalApiKey}")
                .header("Accept", "application/json")
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                val responseText = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    throw IllegalStateException(
                        "Model discovery failed: HTTP ${response.code}${responseText.takeIf { it.isNotBlank() }?.let { ": ${it.take(300)}" }.orEmpty()}"
                    )
                }
                parseModelIds(responseText).also {
                    require(it.isNotEmpty()) { "Endpoint returned no models" }
                }
            }
        }
    }

    override suspend fun loadModel(model: ModelDescriptor): Result<Unit> = Result.success(Unit)

    override fun generate(request: GenerationRequest): Flow<GenerationEvent> = channelFlow {
        val settings = settingsRepository.settings.value
        val modelId = settings.externalModelId.trim()
        val startTime = System.currentTimeMillis()

        val baseUrl = runCatching { normalizeBaseUrl(settings.externalApiBaseUrl) }.getOrElse { error ->
            send(GenerationEvent.Error(error, "External provider URL is invalid: ${error.message}"))
            return@channelFlow
        }

        if (settings.externalApiKey.isBlank()) {
            val error = IllegalStateException("External provider API key is missing")
            send(GenerationEvent.Error(error, error.message ?: "External provider API key is missing"))
            return@channelFlow
        }
        if (modelId.isBlank()) {
            val error = IllegalStateException("No external model selected")
            send(GenerationEvent.Error(error, error.message ?: "No external model selected"))
            return@channelFlow
        }

        val payload = buildChatPayload(request, modelId)
        val httpRequest = Request.Builder()
            .url("$baseUrl/chat/completions")
            .header("Authorization", "Bearer ${settings.externalApiKey}")
            .header("Accept", "text/event-stream, application/json")
            .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()

        val call = client.newCall(httpRequest)
        activeCall = call
        currentCoroutineContext()[Job]?.invokeOnCompletion {
            if (it != null) call.cancel()
        }

        var emittedCompletion = false
        val toolId = StringBuilder()
        val toolName = StringBuilder()
        val toolArguments = StringBuilder()

        try {
            call.execute().use { response ->
                if (!response.isSuccessful) {
                    val responseText = response.body?.string().orEmpty()
                    val error = IllegalStateException(
                        "External provider returned HTTP ${response.code}${responseText.takeIf { it.isNotBlank() }?.let { ": ${it.take(500)}" }.orEmpty()}"
                    )
                    send(GenerationEvent.Error(error, error.message ?: "External provider request failed"))
                    return@use
                }

                val body = response.body
                if (body == null) {
                    val error = IllegalStateException("External provider returned an empty response")
                    send(GenerationEvent.Error(error, error.message ?: "External provider returned an empty response"))
                    return@use
                }

                val contentType = response.header("Content-Type").orEmpty()
                if (contentType.contains("text/event-stream", ignoreCase = true)) {
                    val source = body.source()
                    while (true) {
                        val line = source.readUtf8Line() ?: break
                        if (!line.startsWith("data:")) continue
                        val data = line.removePrefix("data:").trim()
                        if (data.isBlank()) continue
                        if (data == "[DONE]") {
                            emittedCompletion = true
                            break
                        }

                        val chunk = runCatching { JSONObject(data) }.getOrNull() ?: continue
                        val choices = chunk.optJSONArray("choices") ?: continue
                        if (choices.length() == 0) continue
                        val choice = choices.optJSONObject(0) ?: continue
                        val delta = choice.optJSONObject("delta") ?: continue

                        val content = delta.optString("content", "")
                        if (content.isNotEmpty()) {
                            send(GenerationEvent.Token(content))
                        }

                        val toolCalls = delta.optJSONArray("tool_calls")
                        if (toolCalls != null && toolCalls.length() > 0) {
                            val streamedCall = toolCalls.optJSONObject(0)
                            if (streamedCall != null) {
                                val idPart = streamedCall.optString("id", "")
                                if (idPart.isNotEmpty() && toolId.isEmpty()) toolId.append(idPart)
                                val function = streamedCall.optJSONObject("function")
                                if (function != null) {
                                    val namePart = function.optString("name", "")
                                    if (namePart.isNotEmpty()) toolName.append(namePart)
                                    val argsPart = function.optString("arguments", "")
                                    if (argsPart.isNotEmpty()) toolArguments.append(argsPart)
                                }
                            }
                        }
                    }
                } else {
                    val json = JSONObject(body.string())
                    val choices = json.optJSONArray("choices")
                    val choice = choices?.optJSONObject(0)
                    val message = choice?.optJSONObject("message")
                    val content = message?.optString("content", "").orEmpty()
                    if (content.isNotEmpty()) {
                        send(GenerationEvent.Token(content))
                    }

                    val toolCalls = message?.optJSONArray("tool_calls")
                    if (toolCalls != null && toolCalls.length() > 0) {
                        val firstCall = toolCalls.optJSONObject(0)
                        if (firstCall != null) {
                            toolId.append(firstCall.optString("id", ""))
                            val function = firstCall.optJSONObject("function")
                            if (function != null) {
                                toolName.append(function.optString("name", ""))
                                toolArguments.append(function.optString("arguments", "{}"))
                            }
                        }
                    }
                    emittedCompletion = true
                }

                if (toolName.isNotEmpty()) {
                    val args = parseToolArguments(toolArguments.toString())
                    send(
                        GenerationEvent.ToolRequest(
                            ToolCall(
                                id = toolId.toString().ifBlank { "call_${UUID.randomUUID()}" },
                                name = toolName.toString(),
                                arguments = args
                            )
                        )
                    )
                }

                val totalMs = (System.currentTimeMillis() - startTime).toDouble()
                send(
                    GenerationEvent.Metrics(
                        GenerationMetrics(
                            totalTimeMs = totalMs,
                            backend = "OPENAI_COMPATIBLE",
                            computeUnit = "REMOTE",
                            stopReason = if (toolName.isNotEmpty()) "tool_calls" else "stop"
                        )
                    )
                )
                if (emittedCompletion || toolName.isNotEmpty()) {
                    send(GenerationEvent.Completed)
                }
            }
        } catch (t: Throwable) {
            if (!call.isCanceled()) {
                send(GenerationEvent.Error(t, "External provider error: ${t.localizedMessage ?: t.javaClass.simpleName}"))
            }
        } finally {
            if (activeCall === call) activeCall = null
        }
    }.flowOn(Dispatchers.IO)

    override suspend fun cancel() {
        activeCall?.cancel()
    }

    override suspend fun unload() {
        cancel()
    }

    override fun runtimeInfo(): RuntimeInfo {
        val settings = settingsRepository.settings.value
        val configured = settings.externalApiBaseUrl.isNotBlank() &&
            settings.externalApiKey.isNotBlank() &&
            settings.externalModelId.isNotBlank()

        return RuntimeInfo(
            engineName = "External OpenAI-Compatible Provider",
            backend = "OPENAI_COMPATIBLE",
            computeUnit = "REMOTE",
            isModelLoaded = configured,
            loadedModelId = settings.externalModelId.takeIf { it.isNotBlank() },
            detectedChipset = "Remote endpoint",
            isSnapdragonHexagonSupported = false,
            sdkVersion = "OpenAI-compatible v1",
            isSdkInitialized = settings.externalApiBaseUrl.isNotBlank() && settings.externalApiKey.isNotBlank(),
            runtimeState = if (configured) RuntimeState.MODEL_LOADED else RuntimeState.READY,
            lastError = null
        )
    }

    override suspend fun applyChatTemplate(
        messages: List<Message>,
        availableTools: List<ToolDefinition>,
        systemInstruction: String?
    ): Result<String> = Result.success("")

    private fun buildChatPayload(request: GenerationRequest, modelId: String): JSONObject {
        val payload = JSONObject()
            .put("model", modelId)
            .put("stream", true)
            .put("temperature", request.temperature)
            .put("top_p", request.topP)
            .put("max_tokens", request.maxTokens)

        val messagesJson = JSONArray()
        val context = request.chatContext
        if (context == null) {
            request.systemPrompt?.takeIf { it.isNotBlank() }?.let {
                messagesJson.put(JSONObject().put("role", "system").put("content", it))
            }
            messagesJson.put(JSONObject().put("role", "user").put("content", request.prompt))
        } else {
            context.systemInstruction?.takeIf { it.isNotBlank() }?.let {
                messagesJson.put(JSONObject().put("role", "system").put("content", it))
            }
            context.messages.forEach { message ->
                when (message) {
                    is Message.User -> {
                        messagesJson.put(JSONObject().put("role", "user").put("content", message.text))
                    }
                    is Message.Assistant -> {
                        val obj = JSONObject().put("role", "assistant")
                        if (message.text.isNotBlank()) {
                            obj.put("content", message.text)
                        } else {
                            obj.put("content", JSONObject.NULL)
                        }
                        message.toolCall?.let { call ->
                            obj.put(
                                "tool_calls",
                                JSONArray().put(
                                    JSONObject()
                                        .put("id", call.id)
                                        .put("type", "function")
                                        .put(
                                            "function",
                                            JSONObject()
                                                .put("name", call.name)
                                                .put("arguments", mapToJson(call.arguments).toString())
                                        )
                                )
                            )
                        }
                        messagesJson.put(obj)
                    }
                    is Message.Tool -> {
                        val content = buildString {
                            append(message.result.output)
                            message.result.error?.let { append("\nError: ").append(it) }
                        }
                        messagesJson.put(
                            JSONObject()
                                .put("role", "tool")
                                .put("tool_call_id", message.result.callId)
                                .put("content", content)
                        )
                    }
                }
            }
        }
        payload.put("messages", messagesJson)

        val tools = context?.availableTools.orEmpty()
        if (tools.isNotEmpty()) {
            val toolsJson = JSONArray()
            tools.forEach { toolsJson.put(toolDefinitionToJson(it)) }
            payload.put("tools", toolsJson)
            payload.put("tool_choice", "auto")
        }

        return payload
    }

    private fun toolDefinitionToJson(definition: ToolDefinition): JSONObject {
        val properties = JSONObject()
        definition.parameters.forEach { (name, parameter) ->
            properties.put(
                name,
                JSONObject()
                    .put("type", openAiType(parameter))
                    .put("description", parameter.description)
            )
        }

        val parameters = JSONObject()
            .put("type", "object")
            .put("properties", properties)
            .put("additionalProperties", false)

        if (definition.requiredParameters.isNotEmpty()) {
            parameters.put("required", JSONArray(definition.requiredParameters))
        }

        return JSONObject()
            .put("type", "function")
            .put(
                "function",
                JSONObject()
                    .put("name", definition.name)
                    .put("description", definition.description)
                    .put("parameters", parameters)
            )
    }

    private fun openAiType(parameter: ToolParameter): String = when (parameter.type.lowercase()) {
        "integer", "int" -> "integer"
        "number", "float", "double" -> "number"
        "boolean", "bool" -> "boolean"
        else -> "string"
    }

    private fun parseToolArguments(raw: String): Map<String, Any?> {
        if (raw.isBlank()) return emptyMap()
        val obj = JSONObject(raw)
        return jsonObjectToMap(obj)
    }

    private fun jsonObjectToMap(obj: JSONObject): Map<String, Any?> {
        val result = linkedMapOf<String, Any?>()
        obj.keys().forEach { key ->
            result[key] = jsonToValue(obj.get(key))
        }
        return result
    }

    private fun jsonToValue(value: Any?): Any? = when (value) {
        null, JSONObject.NULL -> null
        is JSONObject -> jsonObjectToMap(value)
        is JSONArray -> List(value.length()) { index -> jsonToValue(value.get(index)) }
        else -> value
    }

    private fun mapToJson(map: Map<String, Any?>): JSONObject {
        val obj = JSONObject()
        map.forEach { (key, value) -> obj.put(key, toJsonValue(value)) }
        return obj
    }

    private fun toJsonValue(value: Any?): Any = when (value) {
        null -> JSONObject.NULL
        is Map<*, *> -> {
            val obj = JSONObject()
            value.forEach { (key, nested) ->
                if (key != null) obj.put(key.toString(), toJsonValue(nested))
            }
            obj
        }
        is Iterable<*> -> JSONArray().apply { value.forEach { put(toJsonValue(it)) } }
        is Array<*> -> JSONArray().apply { value.forEach { put(toJsonValue(it)) } }
        else -> value
    }

    companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        fun normalizeBaseUrl(input: String): String {
            val trimmed = input.trim().trimEnd('/')
            require(trimmed.isNotBlank()) { "Base URL is required" }

            val uri = URI(trimmed)
            require(uri.scheme.equals("http", true) || uri.scheme.equals("https", true)) {
                "Base URL must use http or https"
            }
            require(!uri.host.isNullOrBlank()) { "Base URL must include a host" }
            require(uri.query == null && uri.fragment == null) { "Base URL must not contain query parameters or fragments" }

            val existingPath = uri.path.orEmpty().trimEnd('/')
            val normalizedPath = if (existingPath.endsWith("/v1")) {
                existingPath
            } else {
                "$existingPath/v1"
            }.ifBlank { "/v1" }

            return URI(
                uri.scheme.lowercase(),
                uri.userInfo,
                uri.host,
                uri.port,
                if (normalizedPath.startsWith('/')) normalizedPath else "/$normalizedPath",
                null,
                null
            ).toString().trimEnd('/')
        }

        fun parseModelIds(responseBody: String): List<String> {
            val root = JSONObject(responseBody)
            val data = root.optJSONArray("data") ?: return emptyList()
            return buildList {
                for (index in 0 until data.length()) {
                    val id = data.optJSONObject(index)?.optString("id", "")?.trim().orEmpty()
                    if (id.isNotEmpty()) add(id)
                }
            }.distinct()
        }
    }
}
