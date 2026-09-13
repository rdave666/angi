package com.example.angi.domain.conversation

import com.example.angi.data.db.ConversationRepository
import com.example.angi.data.models.ModelRepository
import com.example.angi.data.settings.SettingsRepository
import com.example.angi.domain.inference.GenerationEvent
import com.example.angi.domain.inference.GenerationRequest
import com.example.angi.domain.inference.InferenceEngine
import com.example.angi.domain.tools.ToolCall
import com.example.angi.domain.tools.ToolExecutor
import com.example.angi.domain.tools.ToolRegistry
import com.example.angi.domain.tools.ToolResult
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
    private val promptBuilder: PromptBuilder = StandardPromptBuilder(),
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default)
) {

    private val _generationState = MutableStateFlow<GenerationState>(GenerationState.Idle)
    val generationState: StateFlow<GenerationState> = _generationState.asStateFlow()

    private var activeJob: Job? = null

    fun sendUserMessage(
        conversationId: String,
        userText: String,
        currentMessages: List<Message>
    ) {
        if (_generationState.value !is GenerationState.Idle && _generationState.value !is GenerationState.Failed) {
            return
        }

        val userMessage = Message.User(
            id = UUID.randomUUID().toString(),
            conversationId = conversationId,
            text = userText
        )

        activeJob = scope.launch {
            conversationRepository.saveMessage(userMessage)

            val model = modelRepository.getActiveModel() ?: run {
                _generationState.value = GenerationState.Failed("No active model found. Please select a model in Model Manager.")
                return@launch
            }

            _generationState.value = GenerationState.Preparing("Preparing model context on ${model.preferredCompute.name}...")

            val settings = settingsRepository.settings.value
            val availableTools = toolRegistry.getAllDefinitions().filter { settingsRepository.isToolEnabled(it.name) }

            val updatedHistory = currentMessages + userMessage
            val prompt = promptBuilder.build(
                messages = updatedHistory,
                availableTools = availableTools,
                model = model,
                systemInstruction = settings.systemPrompt
            )

            val request = GenerationRequest(
                prompt = prompt,
                maxTokens = settings.maxTokens,
                temperature = settings.temperature,
                topP = settings.topP
            )

            val assistantMsgId = UUID.randomUUID().toString()
            var streamedText = StringBuilder()
            var detectedToolCall: ToolCall? = null

            _generationState.value = GenerationState.Generating("", 0)

            inferenceEngine.generate(request).collect { event ->
                when (event) {
                    is GenerationEvent.Token -> {
                        streamedText.append(event.text)
                        _generationState.value = GenerationState.Generating(streamedText.toString(), streamedText.length)
                    }
                    is GenerationEvent.ToolRequest -> {
                        detectedToolCall = event.toolCall
                    }
                    is GenerationEvent.Metrics -> {
                        val assistantMsg = Message.Assistant(
                            id = assistantMsgId,
                            conversationId = conversationId,
                            text = streamedText.toString(),
                            metrics = event.stats,
                            toolCall = detectedToolCall
                        )
                        conversationRepository.saveMessage(assistantMsg)
                    }
                    is GenerationEvent.Error -> {
                        _generationState.value = GenerationState.Failed(event.userMessage)
                    }
                    is GenerationEvent.Completed -> {
                        if (detectedToolCall != null) {
                            // Execute tool and continue generation turn
                            val toolCall = detectedToolCall!!
                            _generationState.value = GenerationState.ExecutingTool(toolCall.name)
                            val toolResult = toolExecutor.execute(toolCall.name, toolCall.arguments)
                            val toolMessage = Message.Tool(
                                id = UUID.randomUUID().toString(),
                                conversationId = conversationId,
                                result = toolResult
                            )
                            conversationRepository.saveMessage(toolMessage)

                            // Follow-up generation with tool result
                            val historyWithTool = updatedHistory + Message.Assistant(
                                id = assistantMsgId,
                                conversationId = conversationId,
                                text = streamedText.toString(),
                                toolCall = toolCall
                            ) + toolMessage

                            val followupPrompt = promptBuilder.build(
                                messages = historyWithTool,
                                availableTools = availableTools,
                                model = model,
                                systemInstruction = settings.systemPrompt
                            )

                            val secondAssistantId = UUID.randomUUID().toString()
                            val secondStreamedText = StringBuilder()

                            inferenceEngine.generate(
                                GenerationRequest(
                                    prompt = followupPrompt,
                                    maxTokens = settings.maxTokens,
                                    temperature = settings.temperature,
                                    topP = settings.topP
                                )
                            ).collect { secondEvent ->
                                when (secondEvent) {
                                    is GenerationEvent.Token -> {
                                        secondStreamedText.append(secondEvent.text)
                                        _generationState.value = GenerationState.Generating(secondStreamedText.toString(), secondStreamedText.length)
                                    }
                                    is GenerationEvent.Metrics -> {
                                        val finalMsg = Message.Assistant(
                                            id = secondAssistantId,
                                            conversationId = conversationId,
                                            text = secondStreamedText.toString(),
                                            metrics = secondEvent.stats
                                        )
                                        conversationRepository.saveMessage(finalMsg)
                                    }
                                    is GenerationEvent.Completed -> {
                                        _generationState.value = GenerationState.Idle
                                    }
                                    is GenerationEvent.Error -> {
                                        _generationState.value = GenerationState.Failed(secondEvent.userMessage)
                                    }
                                    else -> {}
                                }
                            }
                        } else {
                            _generationState.value = GenerationState.Idle
                        }
                    }
                }
            }
        }
    }

    fun stopGeneration() {
        scope.launch {
            _generationState.value = GenerationState.Stopping
            inferenceEngine.cancel()
            activeJob?.cancel()
            _generationState.value = GenerationState.Idle
        }
    }
}
