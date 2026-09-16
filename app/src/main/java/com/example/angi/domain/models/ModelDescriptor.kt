package com.example.angi.domain.models

enum class ModelFormat {
    GGUF,
    QAIRT_BUNDLE,
    ONNX
}

enum class RuntimeType(val value: String) {
    LLAMA_CPP("llama_cpp"),
    QAIRT("qairt")
}

enum class ComputeUnit(val value: String) {
    NPU("NPU"),
    GPU("GPU"),
    CPU("CPU"),
    HYBRID("HYBRID")
}

enum class Modality {
    TEXT_ONLY,
    MULTIMODAL_EXPERIMENTAL
}

enum class ModelLifecycleState {
    MISSING,
    DOWNLOADING,
    IMPORTING,
    VALIDATING,
    AVAILABLE,
    LOADING,
    LOADED,
    FAILED,
    INCOMPATIBLE
}

data class ModelDescriptor(
    val id: String,
    val name: String,
    val family: String,
    val format: ModelFormat,
    val runtime: RuntimeType,
    val preferredCompute: ComputeUnit,
    val fallbackCompute: ComputeUnit = ComputeUnit.CPU,
    val modelPath: String,
    val tokenizerPath: String = "",
    val parameterCount: String = "2B",
    val contextLength: Int = 2048,
    val fileSizeBytes: Long = 0L,
    val isBundled: Boolean = false,
    val lifecycleState: ModelLifecycleState = ModelLifecycleState.AVAILABLE,
    val isReady: Boolean = (lifecycleState == ModelLifecycleState.AVAILABLE || lifecycleState == ModelLifecycleState.LOADED),
    val modality: Modality = Modality.TEXT_ONLY,
    val description: String = ""
)
