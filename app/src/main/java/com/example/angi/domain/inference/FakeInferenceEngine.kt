package com.example.angi.domain.inference

import com.example.angi.domain.models.ModelDescriptor
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * FakeInferenceEngine is strictly an explicit test/development mock.
 * It is NEVER silently substituted for production inference.
 */
class FakeInferenceEngine(
    private val simulatedTokens: List<String> = listOf("Fake", " response", " for", " testing.")
) : InferenceEngine {

    private var loadedModel: ModelDescriptor? = null
    private var state: RuntimeState = RuntimeState.UNINITIALIZED

    override suspend fun loadModel(model: ModelDescriptor): Result<Unit> {
        loadedModel = model
        state = RuntimeState.MODEL_LOADED
        return Result.success(Unit)
    }

    override fun generate(request: GenerationRequest): Flow<GenerationEvent> = flow {
        if (state != RuntimeState.MODEL_LOADED) {
            emit(GenerationEvent.Error(IllegalStateException("No model loaded in FakeInferenceEngine"), "Fake model not loaded"))
            return@flow
        }
        for (token in simulatedTokens) {
            emit(GenerationEvent.Token(token))
        }
        emit(GenerationEvent.Completed)
    }

    override suspend fun cancel() {
        // No-op in fake engine
    }

    override suspend fun unload() {
        unloadModel()
    }

    override suspend fun unloadModel(): Result<Unit> {
        loadedModel = null
        state = RuntimeState.READY
        return Result.success(Unit)
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
