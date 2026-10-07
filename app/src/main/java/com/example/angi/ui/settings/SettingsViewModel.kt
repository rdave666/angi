package com.example.angi.ui.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.AngiApp
import com.example.angi.api.ApiServerState
import com.example.angi.api.OpenAiApiService
import com.example.angi.data.settings.AngiSettings
import com.example.angi.data.settings.InferenceSource
import com.example.angi.domain.inference.RuntimeInfo
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.UUID

data class ExternalProviderState(
    val isLoading: Boolean = false,
    val models: List<String> = emptyList(),
    val error: String? = null,
    val lastRefreshEpochMs: Long? = null
)

class SettingsViewModel : ViewModel() {

    private val settingsRepo = AngiApp.instance.settingsRepository
    private val inferenceEngine = AngiApp.instance.inferenceEngine
    private val externalInferenceEngine = AngiApp.instance.externalInferenceEngine

    val settings: StateFlow<AngiSettings> = settingsRepo.settings
    val apiServerState: StateFlow<ApiServerState> = OpenAiApiService.serverState

    private val _runtimeInfo = MutableStateFlow(inferenceEngine.runtimeInfo())
    val runtimeInfo: StateFlow<RuntimeInfo> = _runtimeInfo.asStateFlow()

    private val _externalProviderState = MutableStateFlow(ExternalProviderState())
    val externalProviderState: StateFlow<ExternalProviderState> = _externalProviderState.asStateFlow()

    init {
        viewModelScope.launch {
            while (isActive) {
                _runtimeInfo.value = inferenceEngine.runtimeInfo()
                delay(1500)
            }
        }

        val initial = settings.value
        if (initial.externalApiBaseUrl.isNotBlank() && initial.externalApiKey.isNotBlank()) {
            refreshExternalModels()
        }
    }

    fun updateSettings(newSettings: AngiSettings) {
        settingsRepo.updateSettings(newSettings)
    }

    fun setInferenceSource(source: InferenceSource) {
        settingsRepo.updateSettings(settings.value.copy(inferenceSource = source))
    }

    fun setExternalApiBaseUrl(url: String) {
        settingsRepo.updateSettings(
            settings.value.copy(
                externalApiBaseUrl = url,
                externalModelId = if (url == settings.value.externalApiBaseUrl) {
                    settings.value.externalModelId
                } else {
                    ""
                }
            )
        )
        _externalProviderState.value = ExternalProviderState()
    }

    fun setExternalApiKey(key: String) {
        settingsRepo.updateSettings(
            settings.value.copy(
                externalApiKey = key,
                externalModelId = if (key == settings.value.externalApiKey) {
                    settings.value.externalModelId
                } else {
                    ""
                }
            )
        )
        _externalProviderState.value = ExternalProviderState()
    }

    fun setExternalModel(modelId: String) {
        settingsRepo.updateSettings(settings.value.copy(externalModelId = modelId))
    }

    fun refreshExternalModels() {
        if (_externalProviderState.value.isLoading) return

        val current = settings.value
        if (current.externalApiBaseUrl.isBlank() || current.externalApiKey.isBlank()) {
            _externalProviderState.value = ExternalProviderState(
                error = "Enter endpoint and API key first."
            )
            return
        }

        viewModelScope.launch {
            _externalProviderState.value = _externalProviderState.value.copy(
                isLoading = true,
                error = null
            )

            externalInferenceEngine.listModels()
                .onSuccess { models ->
                    val latest = settings.value
                    val selected = latest.externalModelId
                    val selectedStillExists = selected.isNotBlank() && models.contains(selected)
                    if (!selectedStillExists && models.isNotEmpty()) {
                        settingsRepo.updateSettings(latest.copy(externalModelId = models.first()))
                    }

                    _externalProviderState.value = ExternalProviderState(
                        isLoading = false,
                        models = models,
                        error = null,
                        lastRefreshEpochMs = System.currentTimeMillis()
                    )
                }
                .onFailure { error ->
                    _externalProviderState.value = ExternalProviderState(
                        isLoading = false,
                        models = emptyList(),
                        error = error.localizedMessage ?: error.javaClass.simpleName
                    )
                }
        }
    }

    fun toggleApiServer(enabled: Boolean, context: Context) {
        val current = settings.value
        val updated = current.copy(isApiServerEnabled = enabled)
        settingsRepo.updateSettings(updated)

        if (enabled) {
            OpenAiApiService.start(context)
        } else {
            OpenAiApiService.stop(context)
        }
    }

    fun setApiServerBindLan(bindLan: Boolean, context: Context) {
        val current = settings.value
        val updated = current.copy(apiServerBindLan = bindLan)
        settingsRepo.updateSettings(updated)
        if (apiServerState.value.isRunning) {
            OpenAiApiService.start(context)
        }
    }

    fun setApiServerPort(port: Int, context: Context) {
        val current = settings.value
        val updated = current.copy(apiServerPort = port)
        settingsRepo.updateSettings(updated)
        if (apiServerState.value.isRunning) {
            OpenAiApiService.start(context)
        }
    }

    fun setApiServerApiKey(key: String, context: Context) {
        val current = settings.value
        val updated = current.copy(apiServerApiKey = key)
        settingsRepo.updateSettings(updated)
        if (apiServerState.value.isRunning) {
            OpenAiApiService.start(context)
        }
    }

    fun regenerateApiKey(context: Context) {
        val newKey = "sk-angi-" + UUID.randomUUID().toString().replace("-", "").take(16)
        setApiServerApiKey(newKey, context)
    }
}
