package com.example.angi.domain.inference

import com.example.angi.domain.models.ModelDescriptor
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * FakeInferenceEngine is strictly an explicit test/development mock.
 * It is NEVER silently substituted for production inference.
 */
class FakeInferenceEngine(
    private val simulatedTokens: List<String> = listOf("Fake", " response", " for", " testing."),
    var tokenDelayMs: Long = 0L
) : InferenceEngine {

    private var loadedModel: ModelDescriptor? = null
    private var state: RuntimeState = RuntimeState.UNINITIALIZED
    var cancelCalledCount: Int = 0
        private set

    fun setRuntimeState(newState: RuntimeState) {
        state = newState
    }

    override suspend fun loadModel(model: ModelDescriptor): Result<Unit> {
        loadedModel = model
        state = RuntimeState.MODEL_LOADED
        return Result.success(Unit)
    }

    override fun generate(request: GenerationRequest): Flow<GenerationEvent> = flow {
        if (state != RuntimeState.MODEL_LOADED && state != RuntimeState.GENERATION_ACTIVE) {
            emit(GenerationEvent.Error(IllegalStateException("No model loaded in FakeInferenceEngine"), "Fake model not loaded"))
            return@flow
        }
        val prevState = state
        state = RuntimeState.GENERATION_ACTIVE
        try {
            for (token in simulatedTokens) {
                if (tokenDelayMs > 0) {
                    kotlinx.coroutines.delay(tokenDelayMs)
                }
                emit(GenerationEvent.Token(token))
            }
            emit(GenerationEvent.Completed)
        } finally {
            state = prevState
        }
    }

    override suspend fun cancel() {
        cancelCalledCount++
    }

    override suspend fun unload() {
        unloadModel()
    }

    override suspend fun unloadModel(): Result<Unit> {
        if (state == RuntimeState.GENERATION_ACTIVE) {
            return Result.failure(IllegalStateException("Cannot unload model while generation is active"))
        }
        loadedModel = null
        state = RuntimeState.READY
        return Result.success(Unit)
    }

    override suspend fun applyChatTemplate(
        messages: List<com.example.angi.domain.conversation.Message>,
        availableTools: List<com.example.angi.domain.tools.ToolDefinition>,
        systemInstruction: String?
    ): Result<String> {
        val prompt = buildString {
            if (!systemInstruction.isNullOrBlank()) {
                append("<|im_start|>system\n").append(systemInstruction).append("<|im_end|>\n")
            }
            for (msg in messages) {
                when (msg) {
                    is com.example.angi.domain.conversation.Message.User -> append("<|im_start|>user\n").append(msg.text).append("<|im_end|>\n")
                    is com.example.angi.domain.conversation.Message.Assistant -> append("<|im_start|>assistant\n").append(msg.text).append("<|im_end|>\n")
                    is com.example.angi.domain.conversation.Message.Tool -> append("<|im_start|>tool\n").append(msg.result.output).append("<|im_end|>\n")
                }
            }
            append("<|im_start|>assistant\n")
        }
        return Result.success(prompt)
    }

    override fun runtimeInfo(): RuntimeInfo {
        return RuntimeInfo(
            engineName = "Fake Test Inference Engine",
            backend = "FAKE_MOCK",
            computeUnit = "MOCK",
            isModelLoaded = loadedModel != null,
            loadedModelId = loadedModel?.id,
            detectedChipset = "Mock Chipset",
            isSnapdragonHexagonSupported = false,
            sdkVersion = "mock-1.0",
            isSdkInitialized = true,
            runtimeState = state,
            lastError = null
        )
    }
}
