package com.example.angi.domain.conversation

import com.example.angi.domain.inference.GenerationMetrics
import com.example.angi.domain.tools.ToolCall
import com.example.angi.domain.tools.ToolResult

data class Conversation(
    val id: String,
    val title: String,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

sealed interface Message {
    val id: String
    val conversationId: String
    val timestamp: Long

    data class User(
        override val id: String,
        override val conversationId: String,
        override val timestamp: Long = System.currentTimeMillis(),
        val text: String
    ) : Message

    data class Assistant(
        override val id: String,
        override val conversationId: String,
        override val timestamp: Long = System.currentTimeMillis(),
        val text: String,
        val metrics: GenerationMetrics? = null,
        val isStreaming: Boolean = false,
        val toolCall: ToolCall? = null
    ) : Message

    data class Tool(
        override val id: String,
        override val conversationId: String,
        override val timestamp: Long = System.currentTimeMillis(),
        val result: ToolResult
    ) : Message
}

sealed interface GenerationState {
    data object Idle : GenerationState
    data class Preparing(val status: String) : GenerationState
    data class Generating(val partialText: String, val tokensCount: Int) : GenerationState
    data class ExecutingTool(val toolName: String) : GenerationState
    data class AwaitingConfirmation(val toolCall: ToolCall, val toolName: String) : GenerationState
    data object Stopping : GenerationState
    data class Failed(val error: String) : GenerationState
}
