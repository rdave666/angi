package com.example.angi.domain.conversation

import com.example.angi.data.db.ConversationRepository
import com.example.angi.data.models.ModelRepository
import com.example.angi.data.settings.InferenceSource
import com.example.angi.data.settings.SettingsRepository
import com.example.angi.domain.inference.ChatRequestContext
import com.example.angi.domain.inference.GenerationEvent
import com.example.angi.domain.inference.GenerationRequest
import com.example.angi.domain.inference.InferenceEngine
import com.example.angi.domain.tools.ToolCall
import com.example.angi.domain.tools.ToolExecutor
import com.example.angi.domain.tools.ToolRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID

class ConversationService(
    private val conversationRepository: ConversationRepository,
    private val modelRepository: ModelRepository,
    private val settingsRepository: SettingsRepository,
    private val inferenceEngine: InferenceEngine,
    private val toolRegistry: ToolRegistry,
    private val toolExecutor: ToolExecutor,
    private val externalInferenceEngine: InferenceEngine? = null,
    private val promptBuilder: PromptBuilder = StandardPromptBuilder(),
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default)
) {

    companion object {
        const val MAX_TOOL_STEPS = 8
    }

    private val _generationState = MutableStateFlow<GenerationState>(GenerationState.Idle)
    val generationState: StateFlow<GenerationState> = _generationState.asStateFlow()

    private var activeJob: Job? = null

    @Volatile
    private var activeInferenceEngine: InferenceEngine? = null

    fun sendUserMessage(
        conversationId: String,
        userText: String,
        currentMessages: List<Message>,
        toolConfirmed: Boolean = false
    ) {
        if (_generationState.value !is GenerationState.Idle &&
            _generationState.value !is GenerationState.Failed &&
            _generationState.value !is GenerationState.AwaitingConfirmation
        ) {
            return
        }

        val userMessage = Message.User(
            id = UUID.randomUUID().toString(),
            conversationId = conversationId,
            text = userText
        )

        activeJob = scope.launch {
            conversationRepository.saveMessage(userMessage)

            val settings = settingsRepository.settings.value
            val useExternal = settings.inferenceSource == InferenceSource.EXTERNAL
            val engine = if (useExternal) {
                externalInferenceEngine ?: run {
                    _generationState.value = GenerationState.Failed("External inference provider is unavailable.")
                    return@launch
                }
            } else {
                inferenceEngine
            }
            activeInferenceEngine = engine

            if (useExternal) {
                if (settings.externalApiBaseUrl.isBlank() || settings.externalApiKey.isBlank()) {
                    _generationState.value = GenerationState.Failed(
                        "External provider needs an OpenAI-compatible endpoint and API key."
                    )
                    activeInferenceEngine = null
                    return@launch
                }
                if (settings.externalModelId.isBlank()) {
                    _generationState.value = GenerationState.Failed(
                        "Select an external model in Settings before starting a chat."
                    )
                    activeInferenceEngine = null
                    return@launch
                }
            }

            val localModel = if (useExternal) {
                null
            } else {
                modelRepository.getActiveModel() ?: run {
                    _generationState.value = GenerationState.Failed(
                        "No active model found. Please select a model in Model Manager."
                    )
                    activeInferenceEngine = null
                    return@launch
                }
            }

            val availableTools = toolRegistry.getAllDefinitions()
                .filter { settingsRepository.isToolEnabled(it.name) }

            var currentHistory = currentMessages + userMessage
            var toolStepCount = 0

            try {
                while (toolStepCount < MAX_TOOL_STEPS) {
                    _generationState.value = GenerationState.Preparing(
                        if (useExternal) {
                            "Preparing external model ${settings.externalModelId}..."
                        } else {
                            "Preparing model context on ${localModel!!.preferredCompute.name}..."
                        }
                    )

                    val prompt = if (useExternal) {
                        ""
                    } else {
                        engine.applyChatTemplate(
                            messages = currentHistory,
                            availableTools = availableTools,
                            systemInstruction = settings.systemPrompt
                        ).getOrElse {
                            promptBuilder.build(
                                messages = currentHistory,
                                availableTools = availableTools,
                                model = localModel!!,
                                systemInstruction = settings.systemPrompt
                            )
                        }
                    }

                    val request = GenerationRequest(
                        prompt = prompt,
                        maxTokens = settings.maxTokens,
                        temperature = settings.temperature,
                        topP = settings.topP,
                        systemPrompt = settings.systemPrompt,
                        chatContext = ChatRequestContext(
                            messages = currentHistory,
                            availableTools = availableTools,
                            systemInstruction = settings.systemPrompt
                        ),
                        modelId = if (useExternal) settings.externalModelId else localModel?.id
                    )

                    val assistantMsgId = UUID.randomUUID().toString()
                    val streamedText = StringBuilder()
                    var detectedToolCall: ToolCall? = null
                    var generationFailed = false
                    var lastMetrics: com.example.angi.domain.inference.GenerationMetrics? = null

                    _generationState.value = GenerationState.Generating("", 0)

                    engine.generate(request).collect { event ->
                        when (event) {
                            is GenerationEvent.Token -> {
                                streamedText.append(event.text)
                                _generationState.value = GenerationState.Generating(
                                    streamedText.toString(),
                                    streamedText.length
                                )
                            }
                            is GenerationEvent.ToolRequest -> {
                                detectedToolCall = event.toolCall
                            }
                            is GenerationEvent.Metrics -> {
                                lastMetrics = event.stats
                            }
                            is GenerationEvent.Error -> {
                                generationFailed = true
                                _generationState.value = GenerationState.Failed(event.userMessage)
                            }
                            is GenerationEvent.Completed -> Unit
                        }
                    }

                    if (generationFailed) {
                        return@launch
                    }

                    val assistantMsg = Message.Assistant(
                        id = assistantMsgId,
                        conversationId = conversationId,
                        text = streamedText.toString(),
                        metrics = lastMetrics,
                        toolCall = detectedToolCall
                    )
                    conversationRepository.saveMessage(assistantMsg)
                    currentHistory = currentHistory + assistantMsg

                    val toolCall = detectedToolCall
                    if (toolCall == null) {
                        _generationState.value = GenerationState.Idle
                        return@launch
                    }

                    toolStepCount++
                    _generationState.value = GenerationState.ExecutingTool(toolCall.name)

                    val rawToolResult = toolExecutor.execute(
                        toolName = toolCall.name,
                        arguments = toolCall.arguments,
                        confirmed = toolConfirmed,
                        context = com.example.angi.domain.tools.ToolExecutionContext(conversationId)
                    )
                    val toolResult = rawToolResult.copy(callId = toolCall.id)
                    val toolMessage = Message.Tool(
                        id = UUID.randomUUID().toString(),
                        conversationId = conversationId,
                        result = toolResult
                    )
                    conversationRepository.saveMessage(toolMessage)
                    currentHistory = currentHistory + toolMessage

                    if (toolResult.requiresConfirmation) {
                        _generationState.value = GenerationState.AwaitingConfirmation(toolCall, toolCall.name)
                        return@launch
                    }
                }

                _generationState.value = GenerationState.Idle
            } finally {
                activeInferenceEngine = null
            }
        }
    }

    fun stopGeneration() {
        scope.launch {
            _generationState.value = GenerationState.Stopping
            activeInferenceEngine?.cancel()
            activeJob?.cancel()
            activeInferenceEngine = null
            _generationState.value = GenerationState.Idle
        }
    }
}
