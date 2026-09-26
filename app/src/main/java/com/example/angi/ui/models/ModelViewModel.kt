package com.example.angi.ui.models

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.AngiApp
import com.example.angi.domain.models.ModelDescriptor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class ModelsUiState(
    val models: List<ModelDescriptor> = emptyList(),
    val activeModel: ModelDescriptor? = null,
    val isLoading: Boolean = false,
    val statusMessage: String? = null,
    val errorMessage: String? = null
)

class ModelViewModel : ViewModel() {

    private val modelRepo = AngiApp.instance.modelRepository
    private val inferenceEngine = AngiApp.instance.inferenceEngine

    private val _uiState = MutableStateFlow(ModelsUiState())
    val uiState: StateFlow<ModelsUiState> = _uiState.asStateFlow()

    init {
        loadModels()
    }

    private fun loadModels() {
        viewModelScope.launch {
            modelRepo.getModels().collect { list ->
                val active = modelRepo.getActiveModel()
                _uiState.value = _uiState.value.copy(
                    models = list,
                    activeModel = active
                )
            }
        }
    }

    fun selectActiveModel(model: ModelDescriptor) {
        loadModel(model)
    }

    fun loadModel(model: ModelDescriptor) {
        viewModelScope.launch {
            val previousActive = _uiState.value.activeModel ?: modelRepo.getActiveModel()
            modelRepo.clearActiveModel()
            if (previousActive != null && previousActive.id != model.id) {
                modelRepo.updateModelLifecycleState(previousActive.id, com.example.angi.domain.models.ModelLifecycleState.AVAILABLE)
            }
            _uiState.value = _uiState.value.copy(
                activeModel = null,
                isLoading = true,
                statusMessage = "Loading ${model.name} on ${model.preferredCompute.name}...",
                errorMessage = null
            )
            modelRepo.updateModelLifecycleState(model.id, com.example.angi.domain.models.ModelLifecycleState.LOADING)
            val result = inferenceEngine.loadModel(model)
            if (result.isSuccess) {
                modelRepo.setActiveModel(model.id)
                modelRepo.updateModelLifecycleState(model.id, com.example.angi.domain.models.ModelLifecycleState.LOADED)
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    activeModel = model,
                    statusMessage = "Model loaded successfully on ${model.preferredCompute.name}",
                    errorMessage = null
                )
            } else {
                val errorMsg = result.exceptionOrNull()?.message ?: "Unknown model load error"
                modelRepo.clearActiveModel()
                modelRepo.updateModelLifecycleState(model.id, com.example.angi.domain.models.ModelLifecycleState.FAILED)
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    activeModel = null,
                    statusMessage = null,
                    errorMessage = "Failed to load model on ${model.preferredCompute.name}: $errorMsg"
                )
            }
        }
    }

    fun unloadActiveModel() {
        viewModelScope.launch {
            val currentInfo = inferenceEngine.runtimeInfo()
            if (currentInfo.runtimeState == com.example.angi.domain.inference.RuntimeState.GENERATION_ACTIVE) {
                _uiState.value = _uiState.value.copy(
                    errorMessage = "Cannot unload model while generation is active"
                )
                return@launch
            }
            val currentActive = _uiState.value.activeModel ?: modelRepo.getActiveModel()
            _uiState.value = _uiState.value.copy(
                isLoading = true,
                statusMessage = "Unloading model...",
                errorMessage = null
            )
            val result = inferenceEngine.unloadModel()
            if (result.isSuccess) {
                if (currentActive != null) {
                    modelRepo.updateModelLifecycleState(currentActive.id, com.example.angi.domain.models.ModelLifecycleState.AVAILABLE)
                }
                modelRepo.clearActiveModel()
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    activeModel = null,
                    statusMessage = "Model unloaded",
                    errorMessage = null
                )
            } else {
                val errorMsg = result.exceptionOrNull()?.message ?: "Unknown error"
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    statusMessage = null,
                    errorMessage = "Failed to unload model: $errorMsg"
                )
            }
        }
    }

    fun updateModelCompute(modelId: String, computeUnit: com.example.angi.domain.models.ComputeUnit) {
        viewModelScope.launch {
            modelRepo.updateModelCompute(modelId, computeUnit)
        }
    }

    fun importModel(uri: Uri) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                isLoading = true,
                statusMessage = "Importing model weights...",
                errorMessage = null
            )
            val result = modelRepo.importModelFromUri(uri)
            if (result.isSuccess) {
                val imported = result.getOrThrow()
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    statusMessage = "Imported ${imported.name} (${imported.format}). Ready to load on ${imported.preferredCompute.name}.",
                    errorMessage = null
                )
            } else {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    statusMessage = null,
                    errorMessage = "Failed to import model: ${result.exceptionOrNull()?.localizedMessage ?: result.exceptionOrNull()?.message}"
                )
            }
        }
    }

    fun deleteModel(id: String) {
        viewModelScope.launch {
            modelRepo.deleteModel(id)
        }
    }
}
