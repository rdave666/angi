package com.example.angi.data.models

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.example.angi.data.db.ModelDao
import com.example.angi.data.db.ModelEntity
import com.example.angi.domain.models.ComputeUnit
import com.example.angi.domain.models.Modality
import com.example.angi.domain.models.ModelDescriptor
import com.example.angi.domain.models.ModelFormat
import com.example.angi.domain.models.RuntimeType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

interface ModelRepository {
    fun getModels(): Flow<List<ModelDescriptor>>
    suspend fun getModelById(id: String): ModelDescriptor?
    suspend fun registerModel(model: ModelDescriptor)
    suspend fun importModelFromUri(uri: Uri, displayName: String? = null): Result<ModelDescriptor>
    suspend fun deleteModel(id: String): Result<Unit>
    suspend fun getActiveModel(): ModelDescriptor?
    suspend fun setActiveModel(id: String)
    suspend fun initializeBundledOrPresetModels()
}

class LocalModelRepository(
    private val context: Context,
    private val dao: ModelDao
) : ModelRepository {

    private val modelsDir: File
        get() = File(context.filesDir, "models").apply { if (!exists()) mkdirs() }

    private val prefs = context.getSharedPreferences("angi_model_prefs", Context.MODE_PRIVATE)

    override fun getModels(): Flow<List<ModelDescriptor>> {
        return dao.getAllModels().map { entities -> entities.map { it.toDomain() } }
    }

    override suspend fun getModelById(id: String): ModelDescriptor? {
        return dao.getModelById(id)?.toDomain()
    }

    override suspend fun registerModel(model: ModelDescriptor) {
        val entity = ModelEntity(
            id = model.id,
            name = model.name,
            family = model.family,
            format = model.format.name,
            runtime = model.runtime.name,
            preferredCompute = model.preferredCompute.name,
            fallbackCompute = model.fallbackCompute.name,
            modelPath = model.modelPath,
            tokenizerPath = model.tokenizerPath,
            parameterCount = model.parameterCount,
            contextLength = model.contextLength,
            fileSizeBytes = model.fileSizeBytes,
            isBundled = model.isBundled,
            isReady = model.isReady,
            modality = model.modality.name,
            description = model.description
        )
        dao.insertModel(entity)
        saveMetadataJson(model)
    }

    override suspend fun importModelFromUri(uri: Uri, displayName: String?): Result<ModelDescriptor> = withContext(Dispatchers.IO) {
        runCatching {
            val fileName = displayName ?: queryFileName(uri) ?: "model_${System.currentTimeMillis()}.gguf"
            val modelId = "model_" + UUID.randomUUID().toString().take(8)
            val targetDir = File(modelsDir, modelId).apply { mkdirs() }
            val targetFile = File(targetDir, fileName)

            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(targetFile).use { output ->
                    input.copyTo(output)
                }
            } ?: throw IllegalStateException("Could not open input stream from URI: $uri")

            val fileSize = targetFile.length()
            val format = when {
                fileName.endsWith(".gguf", ignoreCase = true) -> ModelFormat.GGUF
                fileName.endsWith(".onnx", ignoreCase = true) -> ModelFormat.ONNX
                targetFile.isDirectory || fileName.contains("qairt", ignoreCase = true) -> ModelFormat.QAIRT_BUNDLE
                else -> ModelFormat.GGUF
            }

            val runtime = when (format) {
                ModelFormat.QAIRT_BUNDLE -> RuntimeType.QAIRT
                else -> RuntimeType.LLAMA_CPP
            }

            val family = when {
                fileName.contains("qwen", ignoreCase = true) -> "Qwen"
                fileName.contains("phi", ignoreCase = true) -> "Phi"
                fileName.contains("llama", ignoreCase = true) -> "Llama"
                fileName.contains("mistral", ignoreCase = true) -> "Mistral"
                fileName.contains("gemma", ignoreCase = true) -> "Gemma"
                else -> "Local LLM"
            }

            val descriptor = ModelDescriptor(
                id = modelId,
                name = fileName.removeSuffix(".gguf").replace("-", " ").replace("_", " ").capitalizeWords(),
                family = family,
                format = format,
                runtime = runtime,
                preferredCompute = ComputeUnit.NPU,
                fallbackCompute = ComputeUnit.CPU,
                modelPath = targetFile.absolutePath,
                fileSizeBytes = fileSize,
                isBundled = false,
                isReady = true,
                modality = Modality.TEXT_ONLY,
                description = "Imported $format model optimized for Snapdragon Hexagon NPU."
            )

            registerModel(descriptor)
            setActiveModel(descriptor.id)
            descriptor
        }
    }

    override suspend fun deleteModel(id: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val model = dao.getModelById(id)?.toDomain()
            if (model != null) {
                val file = File(model.modelPath)
                if (file.exists()) {
                    file.parentFile?.deleteRecursively()
                }
                dao.deleteModelById(id)
            }
        }
    }

    override suspend fun getActiveModel(): ModelDescriptor? {
        val activeId = prefs.getString("active_model_id", null)
        return if (activeId != null) {
            dao.getModelById(activeId)?.toDomain()
        } else {
            null
        }
    }

    override suspend fun setActiveModel(id: String) {
        prefs.edit().putString("active_model_id", id).apply()
    }

    override suspend fun initializeBundledOrPresetModels() = withContext(Dispatchers.IO) {
        // Register default model profiles matching target device hardware (Snapdragon 8 Gen 2 / SM8550)
        // If no models registered, create pre-configured profiles for S23 Ultra Hexagon NPU execution
        val existing = dao.getModelById("qwen2_5_1_5b_qairt")
        if (existing == null) {
            val qairtPreset = ModelDescriptor(
                id = "qwen2_5_1_5b_qairt",
                name = "Qwen 2.5 1.5B (QAIRT NPU)",
                family = "Qwen",
                format = ModelFormat.QAIRT_BUNDLE,
                runtime = RuntimeType.QAIRT,
                preferredCompute = ComputeUnit.NPU,
                fallbackCompute = ComputeUnit.CPU,
                modelPath = File(modelsDir, "qwen2_5_1_5b_qairt/model.bin").absolutePath,
                parameterCount = "1.5B",
                contextLength = 4096,
                fileSizeBytes = 1_650_000_000L,
                isBundled = false,
                isReady = true,
                modality = Modality.TEXT_ONLY,
                description = "Optimized Snapdragon 8 Gen 2 / SM8550 Qualcomm AI Engine Direct Hexagon bundle."
            )
            registerModel(qairtPreset)

            val llamaPreset = ModelDescriptor(
                id = "llama3_2_1b_gguf",
                name = "Llama 3.2 1B (GGUF llama.cpp)",
                family = "Llama",
                format = ModelFormat.GGUF,
                runtime = RuntimeType.LLAMA_CPP,
                preferredCompute = ComputeUnit.NPU,
                fallbackCompute = ComputeUnit.GPU,
                modelPath = File(modelsDir, "llama3_2_1b_gguf/model.gguf").absolutePath,
                parameterCount = "1.2B",
                contextLength = 2048,
                fileSizeBytes = 1_250_000_000L,
                isBundled = false,
                isReady = true,
                modality = Modality.TEXT_ONLY,
                description = "High-efficiency GGUF model runnable on Hexagon NPU or Adreno GPU fallback."
            )
            registerModel(llamaPreset)

            val phiPreset = ModelDescriptor(
                id = "phi3_5_mini_qairt",
                name = "Phi-3.5 Mini 3.8B (QAIRT NPU)",
                family = "Phi",
                format = ModelFormat.QAIRT_BUNDLE,
                runtime = RuntimeType.QAIRT,
                preferredCompute = ComputeUnit.NPU,
                fallbackCompute = ComputeUnit.CPU,
                modelPath = File(modelsDir, "phi3_5_mini_qairt/model.bin").absolutePath,
                parameterCount = "3.8B",
                contextLength = 4096,
                fileSizeBytes = 2_800_000_000L,
                isBundled = false,
                isReady = true,
                modality = Modality.TEXT_ONLY,
                description = "High reasoning capacity NPU bundle for SM8550 / Snapdragon 8 Gen 2."
            )
            registerModel(phiPreset)

            setActiveModel(qairtPreset.id)
        }
    }

    private fun saveMetadataJson(model: ModelDescriptor) {
        runCatching {
            val dir = File(model.modelPath).parentFile ?: return
            val metaFile = File(dir, "metadata.json")
            val json = JSONObject().apply {
                put("id", model.id)
                put("name", model.name)
                put("family", model.family)
                put("format", model.format.name)
                put("runtime", model.runtime.name)
                put("preferredCompute", model.preferredCompute.name)
                put("fallbackCompute", model.fallbackCompute.name)
                put("parameterCount", model.parameterCount)
                put("contextLength", model.contextLength)
                put("fileSizeBytes", model.fileSizeBytes)
                put("modality", model.modality.name)
                put("description", model.description)
            }
            metaFile.writeText(json.toString(2))
        }
    }

    private fun queryFileName(uri: Uri): String? {
        if (uri.scheme == "content") {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (index != -1) return cursor.getString(index)
                }
            }
        }
        return uri.path?.let { File(it).name }
    }

    private fun String.capitalizeWords(): String = split(" ").joinToString(" ") { word ->
        word.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
    }
}
