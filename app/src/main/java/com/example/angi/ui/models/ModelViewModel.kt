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
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, statusMessage = "Loading ${model.name}...")
            val result = inferenceEngine.loadModel(model)
            if (result.isSuccess) {
                modelRepo.setActiveModel(model.id)
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    activeModel = model,
                    statusMessage = "Model loaded successfully on ${model.preferredCompute.name}",
                    errorMessage = null
                )
            } else {
                val errorMsg = result.exceptionOrNull()?.message ?: "Unknown model load error"
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    statusMessage = null,
                    errorMessage = "Failed to load model: $errorMsg"
                )
            }
        }
    }

    fun importModel(uri: Uri) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, statusMessage = "Importing model into local storage...")
            val result = modelRepo.importModelFromUri(uri)
            if (result.isSuccess) {
                val imported = result.getOrNull()!!
                val loadResult = inferenceEngine.loadModel(imported)
                if (loadResult.isSuccess) {
                    modelRepo.setActiveModel(imported.id)
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        activeModel = imported,
                        statusMessage = "Successfully imported and loaded ${imported.name} (${imported.format})",
                        errorMessage = null
                    )
                } else {
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        statusMessage = "Imported ${imported.name}, but failed to load: ${loadResult.exceptionOrNull()?.message}",
                        errorMessage = null
                    )
                }
            } else {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    errorMessage = "Failed to import model: ${result.exceptionOrNull()?.localizedMessage}"
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
