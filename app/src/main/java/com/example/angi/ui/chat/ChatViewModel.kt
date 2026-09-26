package com.example.angi.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.AngiApp
import com.example.angi.data.db.ConversationRepository
import com.example.angi.domain.conversation.Conversation
import com.example.angi.domain.conversation.GenerationState
import com.example.angi.domain.conversation.Message
import com.example.angi.domain.inference.RuntimeInfo
import com.example.angi.domain.models.ModelDescriptor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class ChatUiState(
    val conversation: Conversation? = null,
    val messages: List<Message> = emptyList(),
    val draftInput: String = "",
    val generationState: GenerationState = GenerationState.Idle,
    val activeModel: ModelDescriptor? = null,
    val runtimeInfo: RuntimeInfo? = null,
    val errorMessage: String? = null
)

class ChatViewModel : ViewModel() {

    private val conversationRepo = AngiApp.instance.conversationRepository
    private val modelRepo = AngiApp.instance.modelRepository
    private val convService = AngiApp.instance.conversationService
    private val inferenceEngine = AngiApp.instance.inferenceEngine
    private val shareTool = AngiApp.instance.toolRegistry.getTool("share_text")

    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    init {
        initializeChat()
    }

    private fun initializeChat() {
        viewModelScope.launch {
            val conv = conversationRepo.getOrCreateCurrentConversation()
            val activeM = modelRepo.getActiveModel()
            _uiState.value = _uiState.value.copy(
                conversation = conv,
                activeModel = activeM,
                runtimeInfo = inferenceEngine.runtimeInfo()
            )

            // Listen to messages
            launch {
                conversationRepo.getMessages(conv.id).collect { msgs ->
                    _uiState.value = _uiState.value.copy(messages = msgs)
                }
            }

            // Listen to generation state
            launch {
                convService.generationState.collect { genState ->
                    _uiState.value = _uiState.value.copy(generationState = genState)
                }
            }
        }
    }

    fun onDraftChanged(newDraft: String) {
        _uiState.value = _uiState.value.copy(draftInput = newDraft)
    }

    fun sendMessage() {
        val draft = _uiState.value.draftInput.trim()
        val conv = _uiState.value.conversation ?: return
        if (draft.isEmpty()) return

        _uiState.value = _uiState.value.copy(draftInput = "")
        convService.sendUserMessage(
            conversationId = conv.id,
            userText = draft,
            currentMessages = _uiState.value.messages
        )
    }

    fun stopGeneration() {
        convService.stopGeneration()
    }

    fun shareMessage(text: String) {
        viewModelScope.launch {
            shareTool?.execute(mapOf("text" to text, "title" to "Share from ANGI"))
        }
    }

    fun clearHistory() {
        val conv = _uiState.value.conversation ?: return
        viewModelScope.launch {
            conversationRepo.deleteConversation(conv.id)
            initializeChat()
        }
    }

    fun refreshActiveModel() {
        viewModelScope.launch {
            val active = modelRepo.getActiveModel()
            _uiState.value = _uiState.value.copy(
                activeModel = active,
                runtimeInfo = inferenceEngine.runtimeInfo()
            )
        }
    }

    fun unloadActiveModel() {
        if (_uiState.value.generationState !is GenerationState.Idle) return
        viewModelScope.launch {
            val active = _uiState.value.activeModel
            val result = inferenceEngine.unloadModel()
            if (result.isSuccess) {
                if (active != null) {
                    modelRepo.updateModelLifecycleState(active.id, com.example.angi.domain.models.ModelLifecycleState.AVAILABLE)
                }
                modelRepo.clearActiveModel()
                _uiState.value = _uiState.value.copy(
                    activeModel = null,
                    runtimeInfo = inferenceEngine.runtimeInfo()
                )
            } else {
                _uiState.value = _uiState.value.copy(
                    errorMessage = "Failed to unload model: ${result.exceptionOrNull()?.message}"
                )
            }
        }
    }
}
