package com.example.angi.data.models

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import com.example.angi.data.db.ModelDao
import com.example.angi.data.db.ModelEntity
import com.example.angi.domain.models.ComputeUnit
import com.example.angi.domain.models.Modality
import com.example.angi.domain.models.ModelDescriptor
import com.example.angi.domain.models.ModelFormat
import com.example.angi.domain.models.RuntimeType
import com.geniex.sdk.ModelManagerWrapper
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
    suspend fun updateModelCompute(id: String, computeUnit: ComputeUnit)
    suspend fun updateModelLifecycleState(id: String, lifecycleState: com.example.angi.domain.models.ModelLifecycleState)
    suspend fun importModelFromUri(uri: Uri, displayName: String? = null): Result<ModelDescriptor>
    suspend fun deleteModel(id: String): Result<Unit>
    suspend fun getActiveModel(): ModelDescriptor?
    suspend fun setActiveModel(id: String)
    suspend fun clearActiveModel()
    suspend fun initializeBundledOrPresetModels()
}

private data class ImportMetadata(
    val format: ModelFormat,
    val runtime: RuntimeType,
    val preferredCompute: ComputeUnit,
    val fallbackCompute: ComputeUnit,
    val lifecycleState: com.example.angi.domain.models.ModelLifecycleState,
    val description: String
)

class LocalModelRepository(
    private val context: Context,
    private val dao: ModelDao
) : ModelRepository {

    private val modelsDir: File
        get() = File(context.filesDir, "models").apply { if (!exists()) mkdirs() }

    private val geniexStoreDir: File
        get() = File(context.filesDir, "geniex_models").apply { if (!exists()) mkdirs() }

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
            description = model.description,
            lifecycleState = model.lifecycleState.name
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
            val lowerName = fileName.lowercase()

            // Header verification for GGUF
            val isGgufHeaderValid = if (targetFile.isFile && fileSize >= 4) {
                targetFile.inputStream().use { stream ->
                    val magic = ByteArray(4)
                    val read = stream.read(magic)
                    read == 4 && magic[0] == 0x47.toByte() && magic[1] == 0x47.toByte() && magic[2] == 0x55.toByte() && magic[3] == 0x46.toByte()
                }
            } else false

            val meta = when {
                lowerName.endsWith(".gguf") -> {
                    if (isGgufHeaderValid) {
                        ImportMetadata(ModelFormat.GGUF, RuntimeType.LLAMA_CPP, ComputeUnit.CPU, ComputeUnit.CPU, com.example.angi.domain.models.ModelLifecycleState.AVAILABLE, "Valid GGUF model runnable on llama.cpp (defaults to CPU).")
                    } else {
                        ImportMetadata(ModelFormat.GGUF, RuntimeType.LLAMA_CPP, ComputeUnit.CPU, ComputeUnit.CPU, com.example.angi.domain.models.ModelLifecycleState.FAILED, "Header validation failed: File is not a valid GGUF.")
                    }
                }
                lowerName.endsWith(".onnx") -> {
                    ImportMetadata(ModelFormat.ONNX, RuntimeType.LLAMA_CPP, ComputeUnit.CPU, ComputeUnit.CPU, com.example.angi.domain.models.ModelLifecycleState.INCOMPATIBLE, "ONNX format is incompatible with llama.cpp runtime without compilation.")
                }
                lowerName.contains("qairt") || targetFile.isDirectory -> {
                    // Check for QAIRT bundle structure
                    val hasBin = File(targetDir, "model.bin").exists() || targetFile.name.endsWith(".bin") || targetFile.name.endsWith(".dlc")
                    if (hasBin) {
                        ImportMetadata(ModelFormat.QAIRT_BUNDLE, RuntimeType.QAIRT, ComputeUnit.NPU, ComputeUnit.CPU, com.example.angi.domain.models.ModelLifecycleState.AVAILABLE, "Validated Qualcomm AI Engine Direct (QAIRT) bundle.")
                    } else {
                        ImportMetadata(ModelFormat.QAIRT_BUNDLE, RuntimeType.QAIRT, ComputeUnit.NPU, ComputeUnit.CPU, com.example.angi.domain.models.ModelLifecycleState.FAILED, "Invalid QAIRT bundle: missing model.bin or DLC graph.")
                    }
                }
                else -> {
                    if (isGgufHeaderValid) {
                        ImportMetadata(ModelFormat.GGUF, RuntimeType.LLAMA_CPP, ComputeUnit.CPU, ComputeUnit.CPU, com.example.angi.domain.models.ModelLifecycleState.AVAILABLE, "Imported GGUF model (defaults to CPU).")
                    } else {
                        ImportMetadata(ModelFormat.GGUF, RuntimeType.LLAMA_CPP, ComputeUnit.CPU, ComputeUnit.CPU, com.example.angi.domain.models.ModelLifecycleState.FAILED, "Unrecognized or invalid model format.")
                    }
                }
            }

            val family = when {
                lowerName.contains("qwen") -> "Qwen"
                lowerName.contains("phi") -> "Phi"
                lowerName.contains("llama") -> "Llama"
                lowerName.contains("mistral") -> "Mistral"
                lowerName.contains("gemma") -> "Gemma"
                else -> "Local LLM"
            }

            val descriptor = ModelDescriptor(
                id = modelId,
                name = fileName.removeSuffix(".gguf").removeSuffix(".bin").replace("-", " ").replace("_", " ").capitalizeWords(),
                family = family,
                format = meta.format,
                runtime = meta.runtime,
                preferredCompute = meta.preferredCompute,
                fallbackCompute = meta.fallbackCompute,
                modelPath = targetFile.absolutePath,
                fileSizeBytes = fileSize,
                isBundled = false,
                lifecycleState = meta.lifecycleState,
                modality = Modality.TEXT_ONLY,
                description = meta.description
            )

            registerModel(descriptor)
            descriptor
        }
    }

    override suspend fun updateModelCompute(id: String, computeUnit: ComputeUnit): Unit = withContext(Dispatchers.IO) {
        dao.updateModelCompute(id, computeUnit.name)
        dao.getModelById(id)?.let { entity ->
            saveMetadataJson(entity.toDomain())
        }
    }

    override suspend fun updateModelLifecycleState(
        id: String,
        lifecycleState: com.example.angi.domain.models.ModelLifecycleState
    ): Unit = withContext(Dispatchers.IO) {
        dao.updateModelLifecycleState(id, lifecycleState.name)
    }

    override suspend fun deleteModel(id: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val model = dao.getModelById(id)?.toDomain()
            if (model != null) {
                val file = File(model.modelPath)
                if (file.exists()) {
                    file.parentFile?.deleteRecursively()
                }
                // Also notify ModelManagerWrapper if model was tracked there
                runCatching {
                    ModelManagerWrapper.remove(model.name)
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

    override suspend fun clearActiveModel() {
        prefs.edit().remove("active_model_id").apply()
    }

    override suspend fun initializeBundledOrPresetModels(): Unit = withContext(Dispatchers.IO) {
        runCatching {
            ModelManagerWrapper.init(geniexStoreDir.absolutePath)
        }.onFailure {
            Log.w("LocalModelRepository", "ModelManagerWrapper native init deferred: ${it.message}")
        }

        // Register default model profiles matching target device hardware (Snapdragon 8 Gen 2 / SM8550)
        // Check actual file presence: if model file does not exist, mark lifecycleState = MISSING and isReady = false
        val existing = dao.getModelById("qwen2_5_1_5b_qairt")
        if (existing == null) {
            val qairtPath = File(modelsDir, "qwen2_5_1_5b_qairt/model.bin").absolutePath
            val qairtExists = File(qairtPath).exists() && File(qairtPath).length() > 0

            val qairtPreset = ModelDescriptor(
                id = "qwen2_5_1_5b_qairt",
                name = "Qwen 2.5 1.5B (QAIRT NPU)",
                family = "Qwen",
                format = ModelFormat.QAIRT_BUNDLE,
                runtime = RuntimeType.QAIRT,
                preferredCompute = ComputeUnit.NPU,
                fallbackCompute = ComputeUnit.CPU,
                modelPath = qairtPath,
                parameterCount = "1.5B",
                contextLength = 4096,
                fileSizeBytes = 1_650_000_000L,
                isBundled = false,
                lifecycleState = if (qairtExists) com.example.angi.domain.models.ModelLifecycleState.AVAILABLE else com.example.angi.domain.models.ModelLifecycleState.MISSING,
                modality = Modality.TEXT_ONLY,
                description = "Snapdragon 8 Gen 2 / SM8550 Qualcomm AI Engine Direct Hexagon bundle profile."
            )
            registerModel(qairtPreset)

            val llamaPath = File(modelsDir, "llama3_2_1b_gguf/model.gguf").absolutePath
            val llamaExists = File(llamaPath).exists() && File(llamaPath).length() > 0

            val llamaPreset = ModelDescriptor(
                id = "llama3_2_1b_gguf",
                name = "Llama 3.2 1B (GGUF llama.cpp)",
                family = "Llama",
                format = ModelFormat.GGUF,
                runtime = RuntimeType.LLAMA_CPP,
                preferredCompute = ComputeUnit.CPU,
                fallbackCompute = ComputeUnit.CPU,
                modelPath = llamaPath,
                parameterCount = "1.2B",
                contextLength = 2048,
                fileSizeBytes = 1_250_000_000L,
                isBundled = false,
                lifecycleState = if (llamaExists) com.example.angi.domain.models.ModelLifecycleState.AVAILABLE else com.example.angi.domain.models.ModelLifecycleState.MISSING,
                modality = Modality.TEXT_ONLY,
                description = "Llama 3.2 1B GGUF profile with CPU execution."
            )
            registerModel(llamaPreset)

            val phiPath = File(modelsDir, "phi3_5_mini_qairt/model.bin").absolutePath
            val phiExists = File(phiPath).exists() && File(phiPath).length() > 0

            val phiPreset = ModelDescriptor(
                id = "phi3_5_mini_qairt",
                name = "Phi-3.5 Mini 3.8B (QAIRT NPU)",
                family = "Phi",
                format = ModelFormat.QAIRT_BUNDLE,
                runtime = RuntimeType.QAIRT,
                preferredCompute = ComputeUnit.NPU,
                fallbackCompute = ComputeUnit.CPU,
                modelPath = phiPath,
                parameterCount = "3.8B",
                contextLength = 4096,
                fileSizeBytes = 2_800_000_000L,
                isBundled = false,
                lifecycleState = if (phiExists) com.example.angi.domain.models.ModelLifecycleState.AVAILABLE else com.example.angi.domain.models.ModelLifecycleState.MISSING,
                modality = Modality.TEXT_ONLY,
                description = "Qualcomm AI Engine Direct bundle profile for SM8550 / Snapdragon 8 Gen 2."
            )
            registerModel(phiPreset)
        }

        // Wire ModelManagerWrapper into local model repository so GenieX models appear alongside imported models
        syncGenieXModels()
        Unit
    }

    suspend fun syncGenieXModels() = withContext(Dispatchers.IO) {
        runCatching {
            val geniexModelNames = ModelManagerWrapper.list()
            for (name in geniexModelNames) {
                val paths = ModelManagerWrapper.getPaths(name) ?: continue
                val modelPath = paths.model_path ?: continue
                val modelFile = File(modelPath)
                val exists = modelFile.exists() && modelFile.length() > 0
                val isGguf = modelPath.endsWith(".gguf", ignoreCase = true)
                val format = if (isGguf) ModelFormat.GGUF else ModelFormat.QAIRT_BUNDLE
                val runtime = if (isGguf) RuntimeType.LLAMA_CPP else RuntimeType.QAIRT
                val preferredCompute = if (isGguf) ComputeUnit.GPU else ComputeUnit.NPU

                val family = when {
                    name.contains("qwen", ignoreCase = true) -> "Qwen"
                    name.contains("llama", ignoreCase = true) -> "Llama"
                    name.contains("phi", ignoreCase = true) -> "Phi"
                    else -> "Local LLM"
                }

                val descriptor = ModelDescriptor(
                    id = "geniex_${name.lowercase().replace(" ", "_")}",
                    name = name,
                    family = family,
                    format = format,
                    runtime = runtime,
                    preferredCompute = preferredCompute,
                    fallbackCompute = ComputeUnit.CPU,
                    modelPath = modelPath,
                    tokenizerPath = paths.tokenizer_path ?: "",
                    fileSizeBytes = if (exists) modelFile.length() else 0L,
                    isBundled = false,
                    lifecycleState = if (exists) com.example.angi.domain.models.ModelLifecycleState.AVAILABLE else com.example.angi.domain.models.ModelLifecycleState.MISSING,
                    modality = Modality.TEXT_ONLY,
                    description = "Managed by Qualcomm GenieX ModelManager (${paths.runtime_id})"
                )
                registerModel(descriptor)
            }
        }.onFailure {
            Log.w("LocalModelRepository", "Failed to sync GenieX models: ${it.message}")
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
