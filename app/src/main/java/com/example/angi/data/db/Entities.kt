package com.example.angi.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import com.example.angi.domain.conversation.Conversation
import com.example.angi.domain.conversation.Message
import com.example.angi.domain.inference.GenerationMetrics
import com.example.angi.domain.models.ComputeUnit
import com.example.angi.domain.models.Modality
import com.example.angi.domain.models.ModelDescriptor
import com.example.angi.domain.models.ModelFormat
import com.example.angi.domain.models.ModelLifecycleState
import com.example.angi.domain.models.RuntimeType
import com.example.angi.domain.tools.ToolCall
import com.example.angi.domain.tools.ToolResult
import org.json.JSONObject

@Entity(tableName = "conversations")
data class ConversationEntity(
    @PrimaryKey val id: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long
) {
    fun toDomain() = Conversation(id = id, title = title, createdAt = createdAt, updatedAt = updatedAt)
}

@Entity(tableName = "messages")
data class MessageEntity(
    @PrimaryKey val id: String,
    val conversationId: String,
    val type: String, // USER, ASSISTANT, TOOL
    val text: String,
    val timestamp: Long,
    val toolCallJson: String? = null,
    val toolResultJson: String? = null,
    val metricsJson: String? = null
)

@Entity(tableName = "models")
data class ModelEntity(
    @PrimaryKey val id: String,
    val name: String,
    val family: String,
    val format: String,
    val runtime: String,
    val preferredCompute: String,
    val fallbackCompute: String,
    val modelPath: String,
    val tokenizerPath: String,
    val parameterCount: String,
    val contextLength: Int,
    val fileSizeBytes: Long,
    val isBundled: Boolean,
    val isReady: Boolean,
    val modality: String,
    val description: String,
    val lifecycleState: String = "AVAILABLE"
) {
    fun toDomain() = ModelDescriptor(
        id = id,
        name = name,
        family = family,
        format = runCatching { ModelFormat.valueOf(format) }.getOrDefault(ModelFormat.GGUF),
        runtime = runCatching { RuntimeType.valueOf(runtime) }.getOrDefault(RuntimeType.LLAMA_CPP),
        preferredCompute = runCatching { ComputeUnit.valueOf(preferredCompute) }.getOrDefault(ComputeUnit.NPU),
        fallbackCompute = runCatching { ComputeUnit.valueOf(fallbackCompute) }.getOrDefault(ComputeUnit.CPU),
        modelPath = modelPath,
        tokenizerPath = tokenizerPath,
        parameterCount = parameterCount,
        contextLength = contextLength,
        fileSizeBytes = fileSizeBytes,
        isBundled = isBundled,
        lifecycleState = runCatching { ModelLifecycleState.valueOf(lifecycleState) }.getOrDefault(
            if (isReady) ModelLifecycleState.AVAILABLE else ModelLifecycleState.MISSING
        ),
        modality = runCatching { Modality.valueOf(modality) }.getOrDefault(Modality.TEXT_ONLY),
        description = description
    )
}
