package com.example.angi.runtime.geniex

import android.content.Context
import android.os.Build
import android.util.Log
import com.example.angi.domain.inference.GenerationEvent
import com.example.angi.domain.inference.GenerationMetrics
import com.example.angi.domain.inference.GenerationRequest
import com.example.angi.domain.inference.InferenceEngine
import com.example.angi.domain.inference.RuntimeInfo
import com.example.angi.domain.models.ComputeUnit
import com.example.angi.domain.models.ModelDescriptor
import com.example.angi.domain.models.RuntimeType
import com.example.angi.domain.tools.ToolCall
import com.geniex.sdk.GenieXSdk
import com.geniex.sdk.LlmWrapper
import com.geniex.sdk.ModelManagerWrapper
import com.geniex.sdk.bean.ChatMessage
import com.geniex.sdk.bean.ComputeUnitValue
import com.geniex.sdk.bean.GenerationConfig
import com.geniex.sdk.bean.LlmCreateInput
import com.geniex.sdk.bean.LlmStreamResult
import com.geniex.sdk.bean.ModelConfig
import com.geniex.sdk.bean.RuntimeIdValue
import com.geniex.sdk.bean.SamplerConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

class GenieXInferenceEngine(
    private val context: Context
) : InferenceEngine {

    private val tag = "GenieXInferenceEngine"
    private val mutex = Mutex()

    private var activeLlm: LlmWrapper? = null
    private var activeModel: ModelDescriptor? = null
    private var isSdkInitialized = false
    private var detectedChipset: String = "Unknown Qualcomm"
    private var isHexagonSupported: Boolean = false
    private var runtimeState: com.example.angi.domain.inference.RuntimeState = com.example.angi.domain.inference.RuntimeState.UNINITIALIZED
    private var lastError: String? = null

    init {
        initializeSdk()
    }

    private fun initializeSdk() {
        runtimeState = com.example.angi.domain.inference.RuntimeState.INITIALIZING
        runCatching {
            GenieXSdk.Companion.getInstance().init(context, object : GenieXSdk.InitCallback {
                override fun onSuccess() {
                    isSdkInitialized = true
                    runtimeState = com.example.angi.domain.inference.RuntimeState.READY
                    Log.i(tag, "GenieXSdk initialized successfully.")
                }

                override fun onFailure(err: String) {
                    runtimeState = com.example.angi.domain.inference.RuntimeState.FAILED
                    lastError = err
                    Log.w(tag, "GenieXSdk init callback error: $err")
                }
            })
        }.onFailure { e ->
            runtimeState = com.example.angi.domain.inference.RuntimeState.FAILED
            lastError = e.message
            Log.w(tag, "GenieXSdk init exception: ${e.message}")
        }

        val soc = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Build.SOC_MODEL else "SM8550"
        detectedChipset = if (soc.isNotBlank()) soc else "Snapdragon 8 Gen 2 (SM8550)"
        isHexagonSupported = Build.HARDWARE.contains("qcom", ignoreCase = true) ||
                Build.MODEL.contains("S918", ignoreCase = true) ||
                detectedChipset.contains("SM8550", ignoreCase = true)
    }

    override suspend fun loadModel(model: ModelDescriptor): Result<Unit> = mutex.withLock {
        withContext(Dispatchers.IO) {
            runCatching {
                Log.i(tag, "Loading model: ${model.name} (${model.format}) on ${model.preferredCompute}")
                runtimeState = com.example.angi.domain.inference.RuntimeState.MODEL_LOADING

                // Terminate and clean up any existing context
                activeLlm?.close()
                activeLlm = null
                activeModel = null

                val modelFile = File(model.modelPath)
                if (!modelFile.exists() || modelFile.length() == 0L) {
                    val errorMsg = "Model weights file not found or empty: ${model.modelPath}"
                    runtimeState = com.example.angi.domain.inference.RuntimeState.MODEL_LOAD_FAILED
                    lastError = errorMsg
                    throw java.io.FileNotFoundException(errorMsg)
                }

                // Requirement A4: Separate configuration paths for LLAMA_CPP and QAIRT
                val runtimeIdStr: String
                val computeUnitStr: String
                val modelConfig: ModelConfig

                when (model.runtime) {
                    RuntimeType.LLAMA_CPP -> {
                        runtimeIdStr = RuntimeIdValue.LLAMA_CPP.value ?: "llama_cpp"
                        val totalLayers = determineTotalModelLayers(model)
                        val nGpuLayers = when (model.preferredCompute) {
                            ComputeUnit.CPU -> 0
                            ComputeUnit.GPU -> totalLayers
                            ComputeUnit.NPU -> totalLayers
                            ComputeUnit.HYBRID -> (totalLayers + 1) / 2
                        }
                        computeUnitStr = when (model.preferredCompute) {
                            ComputeUnit.NPU -> ComputeUnitValue.NPU.value ?: "npu"
                            ComputeUnit.GPU -> ComputeUnitValue.GPU.value ?: "gpu"
                            ComputeUnit.CPU -> ComputeUnitValue.CPU.value ?: "cpu"
                            ComputeUnit.HYBRID -> ComputeUnitValue.HYBRID.value ?: "hybrid"
                        }
                        modelConfig = ModelConfig(
                            nCtx = model.contextLength,
                            nThreads = 4,
                            nThreadsBatch = 4,
                            nBatch = 512,
                            nUBatch = 512,
                            nSeqMax = 1,
                            nGpuLayers = nGpuLayers,
                            chat_template_path = "",
                            chat_template_content = "",
                            max_tokens = 1024,
                            enable_thinking = false,
                            verbose = true
                        )
                    }
                    RuntimeType.QAIRT -> {
                        // QAIRT models use pre-compiled graph dimensions; do not override arbitrary llama.cpp tuning fields
                        runtimeIdStr = RuntimeIdValue.QAIRT.value ?: "qairt"
                        computeUnitStr = ComputeUnitValue.NPU.value ?: "npu"
                        modelConfig = ModelConfig(
                            nCtx = 0,
                            nThreads = 0,
                            nThreadsBatch = 0,
                            nBatch = 0,
                            nUBatch = 0,
                            nSeqMax = 1,
                            nGpuLayers = 0,
                            chat_template_path = "",
                            chat_template_content = "",
                            max_tokens = 1024,
                            enable_thinking = false,
                            verbose = true
                        )
                    }
                }

                val createInput = LlmCreateInput(
                    model_name = model.name,
                    model_path = model.modelPath,
                    tokenizer_path = model.tokenizerPath,
                    config = modelConfig,
                    runtime_id = runtimeIdStr,
                    compute_unit = computeUnitStr
                )

                val buildResult = LlmWrapper.builder()
                    .llmCreateInput(createInput)
                    .dispatcher(Dispatchers.IO)
                    .build()

                if (buildResult.isSuccess) {
                    activeLlm = buildResult.getOrThrow()
                    activeModel = model
                    runtimeState = com.example.angi.domain.inference.RuntimeState.MODEL_LOADED
                    lastError = null
                    Log.i(tag, "Native GenieX LlmWrapper created successfully for ${model.name}")
                    Unit
                } else {
                    val err = buildResult.exceptionOrNull() ?: RuntimeException("GenieX native build failed")
                    runtimeState = com.example.angi.domain.inference.RuntimeState.MODEL_LOAD_FAILED
                    lastError = err.message
                    Log.e(tag, "Native GenieX model load failed: ${err.message}", err)
                    throw err
                }
            }
        }
    }

    override fun generate(request: GenerationRequest): Flow<GenerationEvent> = channelFlow {
        val model = activeModel
        val llm = activeLlm

        // Requirement A1 & A2: Zero fake inference success in production engine.
        if (llm == null || model == null || (runtimeState != com.example.angi.domain.inference.RuntimeState.MODEL_LOADED && runtimeState != com.example.angi.domain.inference.RuntimeState.GENERATION_ACTIVE)) {
            val err = IllegalStateException("Inference unavailable: No model loaded in Qualcomm GenieX native runtime. Current state: $runtimeState")
            send(GenerationEvent.Error(err, "Inference error: Model is not loaded into native runtime. Please verify model weights file."))
            return@channelFlow
        }

        runtimeState = com.example.angi.domain.inference.RuntimeState.GENERATION_ACTIVE
        val startTime = System.currentTimeMillis()
        var tokenCount = 0
        val fullText = StringBuilder()

        try {
            val samplerConfig = SamplerConfig(
                temperature = request.temperature,
                topP = request.topP,
                topK = 40,
                minP = 0.05f,
                repetitionPenalty = 1.1f,
                presencePenalty = 0.0f,
                frequencyPenalty = 0.0f,
                seed = 42,
                grammarPath = "",
                grammarString = ""
            )

            val genConfig = GenerationConfig(
                maxTokens = request.maxTokens,
                stopWords = request.stopWords.toTypedArray(),
                stopCount = request.stopWords.size,
                nPast = 0,
                samplerConfig = samplerConfig,
                imagePaths = emptyArray(),
                imageCount = 0,
                audioPaths = emptyArray(),
                audioCount = 0
            )

            var ttftRecorded = 0.0

            llm.generateStreamFlow(request.prompt, genConfig).collect { streamResult ->
                when (streamResult) {
                    is LlmStreamResult.Token -> {
                        if (ttftRecorded == 0.0) {
                            ttftRecorded = (System.currentTimeMillis() - startTime).toDouble()
                        }
                        tokenCount++
                        fullText.append(streamResult.text)
                        send(GenerationEvent.Token(streamResult.text))

                        checkForToolCall(fullText.toString())?.let { call ->
                            send(GenerationEvent.ToolRequest(call))
                        }
                    }
                    is LlmStreamResult.Completed -> {
                        val profile = streamResult.profile
                        val totalTime = (System.currentTimeMillis() - startTime).toDouble()
                        val metrics = GenerationMetrics(
                            ttftMs = if (profile.ttftMs > 0) profile.ttftMs else ttftRecorded,
                            prefillTokensPerSec = profile.prefillSpeed,
                            decodeTokensPerSec = if (profile.decodingSpeed > 0) profile.decodingSpeed else (tokenCount / (totalTime / 1000.0).coerceAtLeast(0.001)),
                            promptTokens = profile.promptTokens,
                            generatedTokens = if (profile.generatedTokens > 0) profile.generatedTokens else tokenCount.toLong(),
                            totalTimeMs = totalTime,
                            backend = model.runtime.value,
                            computeUnit = model.preferredCompute.value,
                            stopReason = profile.stopReason
                        )
                        send(GenerationEvent.Metrics(metrics))
                        send(GenerationEvent.Completed)
                    }
                    is LlmStreamResult.Error -> {
                        send(GenerationEvent.Error(streamResult.throwable, "Inference error: ${streamResult.throwable.localizedMessage}"))
                    }
                }
            }
        } catch (t: Throwable) {
            send(GenerationEvent.Error(t, "GenieX streaming exception: ${t.localizedMessage}"))
        } finally {
            runtimeState = com.example.angi.domain.inference.RuntimeState.MODEL_LOADED
        }
    }.flowOn(Dispatchers.Default)

    override suspend fun cancel(): Unit = mutex.withLock {
        withContext(Dispatchers.IO) {
            runCatching {
                activeLlm?.stopStream()
            }
            Unit
        }
    }

    override suspend fun unload(): Unit = mutex.withLock {
        withContext(Dispatchers.IO) {
            runCatching {
                activeLlm?.close()
                activeLlm = null
                activeModel = null
                runtimeState = if (isSdkInitialized) com.example.angi.domain.inference.RuntimeState.READY else com.example.angi.domain.inference.RuntimeState.UNINITIALIZED
            }
            Unit
        }
    }

    override fun runtimeInfo(): RuntimeInfo {
        val loaded = (runtimeState == com.example.angi.domain.inference.RuntimeState.MODEL_LOADED || runtimeState == com.example.angi.domain.inference.RuntimeState.GENERATION_ACTIVE) && activeLlm != null
        return RuntimeInfo(
            engineName = "Qualcomm GenieX On-Device Engine",
            backend = activeModel?.runtime?.value ?: "UNINITIALIZED",
            computeUnit = activeModel?.preferredCompute?.value ?: "NONE",
            isModelLoaded = loaded,
            loadedModelId = if (loaded) activeModel?.id else null,
            detectedChipset = detectedChipset,
            isSnapdragonHexagonSupported = isHexagonSupported,
            sdkVersion = "0.3.1 (Qualcomm GenieX)",
            isSdkInitialized = isSdkInitialized,
            runtimeState = runtimeState,
            lastError = lastError
        )
    }

    override suspend fun applyChatTemplate(
        messages: List<com.example.angi.domain.conversation.Message>,
        availableTools: List<com.example.angi.domain.tools.ToolDefinition>,
        systemInstruction: String?
    ): Result<String> = withContext(Dispatchers.Default) {
        runCatching {
            val llm = activeLlm ?: throw IllegalStateException("Model not loaded in GenieXInferenceEngine")
            val effectiveSystem = buildEffectiveSystemPrompt(availableTools, systemInstruction)
            val chatMessages = ArrayList<ChatMessage>()
            chatMessages.add(ChatMessage("system", effectiveSystem))

            for (msg in messages) {
                when (msg) {
                    is com.example.angi.domain.conversation.Message.User -> {
                        chatMessages.add(ChatMessage("user", msg.text))
                    }
                    is com.example.angi.domain.conversation.Message.Assistant -> {
                        val content = buildString {
                            if (msg.toolCall != null) {
                                append("```tool_code\n")
                                val argsMap = msg.toolCall.arguments.entries.joinToString(prefix = "{", postfix = "}") { entry ->
                                    "\"" + entry.key + "\": \"" + entry.value + "\""
                                }
                                append("{\"tool\": \"").append(msg.toolCall.name).append("\", \"arguments\": ").append(argsMap).append("}\n```\n")
                            }
                            if (msg.text.isNotEmpty()) {
                                append(msg.text)
                            }
                        }
                        chatMessages.add(ChatMessage("assistant", content))
                    }
                    is com.example.angi.domain.conversation.Message.Tool -> {
                        val content = buildString {
                            append("Tool result for ").append(msg.result.toolName).append(":\n").append(msg.result.output)
                            if (msg.result.error != null) {
                                append("\nError: ").append(msg.result.error)
                            }
                        }
                        chatMessages.add(ChatMessage("tool", content))
                    }
                }
            }

            val templateResult = llm.applyChatTemplate(
                messages = chatMessages.toTypedArray(),
                tools = "",
                addGenerationPrompt = true,
                enableThinking = false
            )
            val output = templateResult.getOrThrow()
            output.formattedText
        }
    }

    private fun buildEffectiveSystemPrompt(
        availableTools: List<com.example.angi.domain.tools.ToolDefinition>,
        systemInstruction: String?
    ): String {
        val sb = StringBuilder()
        sb.append("You are ANGI, an advanced AI running on-device on Qualcomm Snapdragon hardware.")
        if (!systemInstruction.isNullOrBlank()) {
            sb.append("\n").append(systemInstruction)
        }
        if (availableTools.isNotEmpty()) {
            sb.append("\n\nYou have access to the following tools:\n")
            for (tool in availableTools) {
                sb.append("- ").append(tool.name).append(": ").append(tool.description).append("\n")
                if (tool.parameters.isNotEmpty()) {
                    sb.append("  Arguments: ")
                    val paramList = tool.parameters.map { (key, param) ->
                        "$key (${param.type}): ${param.description}"
                    }.joinToString(", ")
                    sb.append(paramList).append("\n")
                }
            }
            sb.append("\nTo use a tool, respond with a JSON block in this exact format:\n")
            sb.append("```tool_code\n{\"tool\": \"tool_name\", \"arguments\": {\"param\": \"value\"}}\n```\n")
        }
        return sb.toString()
    }

    private fun determineTotalModelLayers(model: ModelDescriptor): Int {
        runCatching {
            val file = File(model.modelPath)
            if (file.exists() && file.length() > 64) {
                val blockCount = readGgufBlockCount(file)
                if (blockCount > 0) return blockCount
            }
        }

        val fam = model.family.lowercase()
        val params = model.parameterCount.lowercase()
        return when {
            fam.contains("phi") -> 32
            fam.contains("qwen") -> {
                if (params.contains("0.5")) 24
                else if (params.contains("1.5") || params.contains("1b")) 28
                else if (params.contains("3") || params.contains("7")) 28
                else 28
            }
            fam.contains("llama") -> {
                if (params.contains("1b") || params.contains("1.")) 16
                else if (params.contains("3b") || params.contains("3.")) 28
                else if (params.contains("7b") || params.contains("8b")) 32
                else 28
            }
            params.contains("1b") || params.contains("1.") -> 16
            params.contains("3b") || params.contains("3.") -> 28
            params.contains("7b") || params.contains("8b") -> 32
            else -> 28
        }
    }

    private fun readGgufBlockCount(file: File): Int {
        return runCatching {
            file.inputStream().use { input ->
                val header = ByteArray(4)
                if (input.read(header) != 4) return@use 0
                if (header[0] != 0x47.toByte() || header[1] != 0x47.toByte() || header[2] != 0x55.toByte() || header[3] != 0x46.toByte()) {
                    return@use 0
                }
                // Skip version (4 bytes), tensor_count (8 bytes)
                val skipBytes = ByteArray(12)
                if (input.read(skipBytes) != 12) return@use 0

                // Read kv_count (uint64 little endian, treat as long)
                val kvCountBytes = ByteArray(8)
                if (input.read(kvCountBytes) != 8) return@use 0
                var kvCount = 0L
                for (i in 0..7) {
                    kvCount = kvCount or ((kvCountBytes[i].toLong() and 0xFFL) shl (i * 8))
                }

                val maxKvToScan = minOf(kvCount, 128L)
                for (k in 0 until maxKvToScan) {
                    val keyLenBytes = ByteArray(8)
                    if (input.read(keyLenBytes) != 8) break
                    var keyLen = 0L
                    for (i in 0..7) {
                        keyLen = keyLen or ((keyLenBytes[i].toLong() and 0xFFL) shl (i * 8))
                    }
                    if (keyLen <= 0 || keyLen > 512) break
                    val keyBytes = ByteArray(keyLen.toInt())
                    if (input.read(keyBytes) != keyLen.toInt()) break
                    val key = String(keyBytes, Charsets.US_ASCII)

                    val typeBytes = ByteArray(4)
                    if (input.read(typeBytes) != 4) break
                    val valueType = (typeBytes[0].toInt() and 0xFF) or
                        ((typeBytes[1].toInt() and 0xFF) shl 8) or
                        ((typeBytes[2].toInt() and 0xFF) shl 16) or
                        ((typeBytes[3].toInt() and 0xFF) shl 24)

                    if (key.endsWith(".block_count") && valueType == 4) { // GGUF_TYPE_UINT32
                        val valBytes = ByteArray(4)
                        if (input.read(valBytes) == 4) {
                            val count = (valBytes[0].toInt() and 0xFF) or
                                ((valBytes[1].toInt() and 0xFF) shl 8) or
                                ((valBytes[2].toInt() and 0xFF) shl 16) or
                                ((valBytes[3].toInt() and 0xFF) shl 24)
                            return@use count
                        }
                    }

                    // Skip value
                    when (valueType) {
                        0, 1, 7 -> input.skip(1)
                        2, 3 -> input.skip(2)
                        4, 5, 6 -> input.skip(4)
                        10, 11, 12 -> input.skip(8)
                        8 -> { // String
                            val strLenBytes = ByteArray(8)
                            if (input.read(strLenBytes) != 8) break
                            var strLen = 0L
                            for (i in 0..7) {
                                strLen = strLen or ((strLenBytes[i].toLong() and 0xFFL) shl (i * 8))
                            }
                            if (strLen > 0) input.skip(strLen)
                        }
                        else -> break // Array or unknown, stop scan
                    }
                }
                0
            }
        }.getOrDefault(0)
    }

    private fun checkForToolCall(text: String): ToolCall? {
        val pattern = Regex("```tool_code\\s*\\n(\\{[\\s\\S]*?\\})\\s*\\n```")
        val match = pattern.find(text) ?: return null
        return runCatching {
            val jsonStr = match.groupValues[1]
            val json = JSONObject(jsonStr)
            val toolName = json.getString("tool")
            val argsObj = json.optJSONObject("arguments")
            val argsMap = mutableMapOf<String, Any?>()
            argsObj?.keys()?.forEach { k -> argsMap[k] = argsObj.get(k) }
            ToolCall(
                id = "call_${System.currentTimeMillis()}",
                name = toolName,
                arguments = argsMap
            )
        }.getOrNull()
    }
}
