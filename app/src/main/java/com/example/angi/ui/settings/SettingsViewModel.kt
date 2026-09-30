package com.example.angi.ui.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.AngiApp
import com.example.angi.api.ApiServerState
import com.example.angi.api.OpenAiApiService
import com.example.angi.data.settings.AngiSettings
import com.example.angi.domain.inference.RuntimeInfo
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.UUID

class SettingsViewModel : ViewModel() {

    private val settingsRepo = AngiApp.instance.settingsRepository
    private val inferenceEngine = AngiApp.instance.inferenceEngine

    val settings: StateFlow<AngiSettings> = settingsRepo.settings
    val apiServerState: StateFlow<ApiServerState> = OpenAiApiService.serverState

    private val _runtimeInfo = MutableStateFlow(inferenceEngine.runtimeInfo())
    val runtimeInfo: StateFlow<RuntimeInfo> = _runtimeInfo.asStateFlow()

    init {
        viewModelScope.launch {
            while (isActive) {
                _runtimeInfo.value = inferenceEngine.runtimeInfo()
                delay(1500)
            }
        }
    }

    fun updateSettings(newSettings: AngiSettings) {
        settingsRepo.updateSettings(newSettings)
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
