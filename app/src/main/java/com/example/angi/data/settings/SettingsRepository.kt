package com.example.angi.data.settings

import android.content.Context
import android.content.SharedPreferences
import com.example.angi.domain.models.ComputeUnit
import com.example.angi.domain.models.RuntimeType
import com.example.angi.domain.tools.CapabilityPolicy
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class AngiSettings(
    val computeUnit: ComputeUnit = ComputeUnit.NPU,
    val runtimeType: RuntimeType = RuntimeType.QAIRT,
    val temperature: Float = 0.7f,
    val topP: Float = 0.9f,
    val maxTokens: Int = 1024,
    val contextLength: Int = 2048,
    val systemPrompt: String = "You are ANGI, an on-device personal intelligence assistant powered by Snapdragon 8 Gen 2 / SM8550 hardware.",
    val isWebFetchEnabled: Boolean = true,
    val isShareEnabled: Boolean = true,
    val isDeviceInfoEnabled: Boolean = true,
    val isOpenUrlEnabled: Boolean = true,
    val allowUnconfirmedNetworkTools: Boolean = false
)

class SettingsRepository(
    context: Context
) : CapabilityPolicy {

    private val prefs: SharedPreferences = context.getSharedPreferences("angi_settings", Context.MODE_PRIVATE)

    private val _settings = MutableStateFlow(loadSettings())
    val settings: StateFlow<AngiSettings> = _settings.asStateFlow()

    private fun loadSettings(): AngiSettings {
        return AngiSettings(
            computeUnit = runCatching {
                ComputeUnit.valueOf(prefs.getString("compute_unit", ComputeUnit.NPU.name)!!)
            }.getOrDefault(ComputeUnit.NPU),
            runtimeType = runCatching {
                RuntimeType.valueOf(prefs.getString("runtime_type", RuntimeType.QAIRT.name)!!)
            }.getOrDefault(RuntimeType.QAIRT),
            temperature = prefs.getFloat("temperature", 0.7f),
            topP = prefs.getFloat("top_p", 0.9f),
            maxTokens = prefs.getInt("max_tokens", 1024),
            contextLength = prefs.getInt("context_length", 2048),
            systemPrompt = prefs.getString("system_prompt", "You are ANGI, an on-device personal intelligence assistant powered by Snapdragon 8 Gen 2 / SM8550 hardware.")!!,
            isWebFetchEnabled = prefs.getBoolean("tool_web_fetch", true),
            isShareEnabled = prefs.getBoolean("tool_share_text", true),
            isDeviceInfoEnabled = prefs.getBoolean("tool_device_info", true),
            isOpenUrlEnabled = prefs.getBoolean("tool_open_url", true),
            allowUnconfirmedNetworkTools = prefs.getBoolean("tool_auto_network", false)
        )
    }

    fun updateSettings(newSettings: AngiSettings) {
        prefs.edit()
            .putString("compute_unit", newSettings.computeUnit.name)
            .putString("runtime_type", newSettings.runtimeType.name)
            .putFloat("temperature", newSettings.temperature)
            .putFloat("top_p", newSettings.topP)
            .putInt("max_tokens", newSettings.maxTokens)
            .putInt("context_length", newSettings.contextLength)
            .putString("system_prompt", newSettings.systemPrompt)
            .putBoolean("tool_web_fetch", newSettings.isWebFetchEnabled)
            .putBoolean("tool_share_text", newSettings.isShareEnabled)
            .putBoolean("tool_device_info", newSettings.isDeviceInfoEnabled)
            .putBoolean("tool_open_url", newSettings.isOpenUrlEnabled)
            .putBoolean("tool_auto_network", newSettings.allowUnconfirmedNetworkTools)
            .apply()
        _settings.value = newSettings
    }

    // CapabilityPolicy implementation
    override fun isToolEnabled(toolName: String): Boolean {
        return when (toolName) {
            "web_fetch" -> _settings.value.isWebFetchEnabled
            "share_text" -> _settings.value.isShareEnabled
            "device_info" -> _settings.value.isDeviceInfoEnabled
            "open_url" -> _settings.value.isOpenUrlEnabled
            else -> false
        }
    }

    override fun canExecuteWithoutPrompt(toolName: String): Boolean {
        return when (toolName) {
            "web_fetch" -> _settings.value.allowUnconfirmedNetworkTools
            "share_text" -> true
            "device_info" -> true
            "open_url" -> false // Opening external URLs should confirm
            else -> false
        }
    }

    override fun setToolEnabled(toolName: String, enabled: Boolean) {
        val current = _settings.value
        val updated = when (toolName) {
            "web_fetch" -> current.copy(isWebFetchEnabled = enabled)
            "share_text" -> current.copy(isShareEnabled = enabled)
            "device_info" -> current.copy(isDeviceInfoEnabled = enabled)
            "open_url" -> current.copy(isOpenUrlEnabled = enabled)
            else -> current
        }
        updateSettings(updated)
    }
}
