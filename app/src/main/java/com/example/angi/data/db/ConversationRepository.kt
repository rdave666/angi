package com.example.angi.data.db

import com.example.angi.domain.conversation.Conversation
import com.example.angi.domain.conversation.Message
import com.example.angi.domain.inference.GenerationMetrics
import com.example.angi.domain.tools.ToolCall
import com.example.angi.domain.tools.ToolResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.json.JSONObject
import java.util.UUID

interface ConversationRepository {
    fun getConversations(): Flow<List<Conversation>>
    fun getMessages(conversationId: String): Flow<List<Message>>
    suspend fun getOrCreateCurrentConversation(): Conversation
    suspend fun saveConversation(conversation: Conversation)
    suspend fun saveMessage(message: Message)
    suspend fun deleteConversation(id: String)
}

class RoomConversationRepository(
    private val dao: ConversationDao
) : ConversationRepository {

    override fun getConversations(): Flow<List<Conversation>> {
        return dao.getAllConversations().map { list -> list.map { it.toDomain() } }
    }

    override fun getMessages(conversationId: String): Flow<List<Message>> {
        return dao.getMessagesForConversation(conversationId).map { entities ->
            entities.map { it.toDomain() }
        }
    }

    override suspend fun getOrCreateCurrentConversation(): Conversation {
        val mostRecent = dao.getMostRecentConversation()
        if (mostRecent != null) {
            return mostRecent.toDomain()
        }
        val newConv = Conversation(
            id = UUID.randomUUID().toString(),
            title = "Snapdragon Local Session",
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis()
        )
        dao.insertConversation(
            ConversationEntity(
                id = newConv.id,
                title = newConv.title,
                createdAt = newConv.createdAt,
                updatedAt = newConv.updatedAt
            )
        )
        return newConv
    }

    override suspend fun saveConversation(conversation: Conversation) {
        dao.insertConversation(
            ConversationEntity(
                id = conversation.id,
                title = conversation.title,
                createdAt = conversation.createdAt,
                updatedAt = conversation.updatedAt
            )
        )
    }

    override suspend fun saveMessage(message: Message) {
        val entity = when (message) {
            is Message.User -> MessageEntity(
                id = message.id,
                conversationId = message.conversationId,
                type = "USER",
                text = message.text,
                timestamp = message.timestamp
            )
            is Message.Assistant -> {
                val metricsJson = message.metrics?.let { m ->
                    JSONObject().apply {
                        put("ttftMs", m.ttftMs)
                        put("decodeTokensPerSec", m.decodeTokensPerSec)
                        put("promptTokens", m.promptTokens)
                        put("generatedTokens", m.generatedTokens)
                        put("backend", m.backend)
                        put("computeUnit", m.computeUnit)
                    }.toString()
                }
                val toolCallJson = message.toolCall?.let { tc ->
                    JSONObject().apply {
                        put("id", tc.id)
                        put("name", tc.name)
                        put("args", JSONObject(tc.arguments))
                    }.toString()
                }
                MessageEntity(
                    id = message.id,
                    conversationId = message.conversationId,
                    type = "ASSISTANT",
                    text = message.text,
                    timestamp = message.timestamp,
                    metricsJson = metricsJson,
                    toolCallJson = toolCallJson
                )
            }
            is Message.Tool -> {
                val toolResJson = JSONObject().apply {
                    put("callId", message.result.callId)
                    put("toolName", message.result.toolName)
                    put("isSuccess", message.result.isSuccess)
                    put("output", message.result.output)
                    put("error", message.result.error)
                }.toString()
                MessageEntity(
                    id = message.id,
                    conversationId = message.conversationId,
                    type = "TOOL",
                    text = message.result.output,
                    timestamp = message.timestamp,
                    toolResultJson = toolResJson
                )
            }
        }
        dao.insertMessage(entity)
    }

    override suspend fun deleteConversation(id: String) {
        dao.deleteMessagesForConversation(id)
        dao.deleteConversation(id)
    }

    private fun MessageEntity.toDomain(): Message {
        return when (type) {
            "USER" -> Message.User(
                id = id,
                conversationId = conversationId,
                timestamp = timestamp,
                text = text
            )
            "ASSISTANT" -> {
                val metrics = metricsJson?.let {
                    runCatching {
                        val json = JSONObject(it)
                        GenerationMetrics(
                            ttftMs = json.optDouble("ttftMs", 0.0),
                            decodeTokensPerSec = json.optDouble("decodeTokensPerSec", 0.0),
                            promptTokens = json.optLong("promptTokens", 0L),
                            generatedTokens = json.optLong("generatedTokens", 0L),
                            backend = json.optString("backend", ""),
                            computeUnit = json.optString("computeUnit", "")
                        )
                    }.getOrNull()
                }
                val toolCall = toolCallJson?.let {
                    runCatching {
                        val json = JSONObject(it)
                        val argsObj = json.optJSONObject("args")
                        val argsMap = mutableMapOf<String, Any?>()
                        argsObj?.keys()?.forEach { k -> argsMap[k] = argsObj.get(k) }
                        ToolCall(
                            id = json.getString("id"),
                            name = json.getString("name"),
                            arguments = argsMap
                        )
                    }.getOrNull()
                }
                Message.Assistant(
                    id = id,
                    conversationId = conversationId,
                    timestamp = timestamp,
                    text = text,
                    metrics = metrics,
                    isStreaming = false,
                    toolCall = toolCall
                )
            }
            "TOOL" -> {
                val res = runCatching {
                    val json = JSONObject(toolResultJson ?: "{}")
                    ToolResult(
                        callId = json.optString("callId", id),
                        toolName = json.optString("toolName", "tool"),
                        isSuccess = json.optBoolean("isSuccess", true),
                        output = json.optString("output", text),
                        error = json.optString("error").takeIf { it.isNotBlank() }
                    )
                }.getOrDefault(ToolResult(id, "unknown", true, text))
                Message.Tool(
                    id = id,
                    conversationId = conversationId,
                    timestamp = timestamp,
                    result = res
                )
            }
            else -> Message.User(id, conversationId, timestamp, text)
        }
    }
}
