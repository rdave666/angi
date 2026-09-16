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
                        val nGpuLayers = when (model.preferredCompute) {
                            ComputeUnit.CPU -> 0
                            ComputeUnit.GPU -> 99
                            ComputeUnit.NPU -> 99 // Offload layers to HTP/NPU accelerator
                            ComputeUnit.HYBRID -> 50
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
