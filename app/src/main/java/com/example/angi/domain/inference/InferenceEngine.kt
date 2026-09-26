package com.example.angi.domain.inference

import com.example.angi.domain.models.ModelDescriptor
import kotlinx.coroutines.flow.Flow

/**
 * Core interface isolating the application from Qualcomm GenieX / native runtime.
 * Presentation and domain code interact strictly with this interface.
 */
interface InferenceEngine {

    suspend fun loadModel(model: ModelDescriptor): Result<Unit>

    fun generate(request: GenerationRequest): Flow<GenerationEvent>

    suspend fun cancel()

    suspend fun unload()
    suspend fun unloadModel(): Result<Unit> = runCatching { unload() }

    fun runtimeInfo(): RuntimeInfo

    suspend fun applyChatTemplate(
        messages: List<com.example.angi.domain.conversation.Message>,
        availableTools: List<com.example.angi.domain.tools.ToolDefinition>,
        systemInstruction: String?
    ): Result<String> = Result.failure(UnsupportedOperationException("Chat template not implemented"))
}

data class GenerationRequest(
    val prompt: String,
    val maxTokens: Int = 1024,
    val temperature: Float = 0.7f,
    val topP: Float = 0.9f,
    val stopWords: List<String> = emptyList(),
    val systemPrompt: String? = null
)

sealed interface GenerationEvent {
    data class Token(val text: String) : GenerationEvent
    data class ToolRequest(val toolCall: com.example.angi.domain.tools.ToolCall) : GenerationEvent
    data class Metrics(val stats: GenerationMetrics) : GenerationEvent
    data class Error(val throwable: Throwable, val userMessage: String) : GenerationEvent
    data object Completed : GenerationEvent
}

data class GenerationMetrics(
    val ttftMs: Double = 0.0,
    val prefillTokensPerSec: Double = 0.0,
    val decodeTokensPerSec: Double = 0.0,
    val promptTokens: Long = 0L,
    val generatedTokens: Long = 0L,
    val totalTimeMs: Double = 0.0,
    val backend: String = "",
    val computeUnit: String = "",
    val stopReason: String = ""
)

enum class RuntimeState {
    UNINITIALIZED,
    INITIALIZING,
    READY,
    MODEL_LOADING,
    MODEL_LOADED,
    MODEL_LOAD_FAILED,
    GENERATION_ACTIVE,
    FAILED
}

data class RuntimeInfo(
    val engineName: String,
    val backend: String,
    val computeUnit: String,
    val isModelLoaded: Boolean,
    val loadedModelId: String?,
    val detectedChipset: String,
    val isSnapdragonHexagonSupported: Boolean,
    val sdkVersion: String,
    val isSdkInitialized: Boolean = false,
    val runtimeState: RuntimeState = RuntimeState.UNINITIALIZED,
    val lastError: String? = null
)
