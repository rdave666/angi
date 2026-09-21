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
    val allowUnconfirmedNetworkTools: Boolean = false,
    // Checkpoint B: Filesystem & Shared Storage Tool Policies
    val isLinuxReadFileEnabled: Boolean = true,
    val isLinuxWriteFileEnabled: Boolean = true,
    val isLinuxListDirectoryEnabled: Boolean = true,
    val isLinuxExecEnabled: Boolean = true,
    val isLinuxProcessToolsEnabled: Boolean = true,
    val isAndroidReadFileEnabled: Boolean = true,
    val isAndroidWriteFileEnabled: Boolean = true,
    val allowUnconfirmedAndroidWrite: Boolean = false
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
            allowUnconfirmedNetworkTools = prefs.getBoolean("tool_auto_network", false),
            isLinuxReadFileEnabled = prefs.getBoolean("tool_linux_read", true),
            isLinuxWriteFileEnabled = prefs.getBoolean("tool_linux_write", true),
            isLinuxListDirectoryEnabled = prefs.getBoolean("tool_linux_list", true),
            isLinuxExecEnabled = prefs.getBoolean("tool_linux_exec", true),
            isLinuxProcessToolsEnabled = prefs.getBoolean("tool_linux_process", true),
            isAndroidReadFileEnabled = prefs.getBoolean("tool_android_read", true),
            isAndroidWriteFileEnabled = prefs.getBoolean("tool_android_write", true),
            allowUnconfirmedAndroidWrite = prefs.getBoolean("tool_auto_android_write", false)
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
            .putBoolean("tool_linux_read", newSettings.isLinuxReadFileEnabled)
            .putBoolean("tool_linux_write", newSettings.isLinuxWriteFileEnabled)
            .putBoolean("tool_linux_list", newSettings.isLinuxListDirectoryEnabled)
            .putBoolean("tool_linux_exec", newSettings.isLinuxExecEnabled)
            .putBoolean("tool_linux_process", newSettings.isLinuxProcessToolsEnabled)
            .putBoolean("tool_android_read", newSettings.isAndroidReadFileEnabled)
            .putBoolean("tool_android_write", newSettings.isAndroidWriteFileEnabled)
            .putBoolean("tool_auto_android_write", newSettings.allowUnconfirmedAndroidWrite)
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
            "linux_read_file" -> _settings.value.isLinuxReadFileEnabled
            "linux_write_file" -> _settings.value.isLinuxWriteFileEnabled
            "linux_list_directory" -> _settings.value.isLinuxListDirectoryEnabled
            "linux_exec" -> _settings.value.isLinuxExecEnabled
            "linux_process_status",
            "linux_process_output",
            "linux_process_kill" -> _settings.value.isLinuxProcessToolsEnabled
            "android_read_file" -> _settings.value.isAndroidReadFileEnabled
            "android_write_file" -> _settings.value.isAndroidWriteFileEnabled
            "android_list_directory" -> true
            else -> false
        }
    }

    override fun canExecuteWithoutPrompt(toolName: String): Boolean {
        return when (toolName) {
            "web_fetch" -> _settings.value.allowUnconfirmedNetworkTools
            "share_text" -> true
            "device_info" -> true
            "open_url" -> false // Opening external URLs should confirm
            "linux_read_file" -> true // Auto allowed inside /workspace
            "linux_write_file" -> true // Auto allowed inside /workspace
            "linux_list_directory" -> true // Auto allowed inside /workspace
            "linux_exec" -> true // Zero-confirmation persistent Linux execution
            "linux_process_status",
            "linux_process_output",
            "linux_process_kill" -> true
            "android_read_file" -> true // Allowed for registered user grants
            "android_write_file" -> _settings.value.allowUnconfirmedAndroidWrite // User policy explicitly allows or prompts
            "android_list_directory" -> true
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
            "linux_read_file" -> current.copy(isLinuxReadFileEnabled = enabled)
            "linux_write_file" -> current.copy(isLinuxWriteFileEnabled = enabled)
            "linux_list_directory" -> current.copy(isLinuxListDirectoryEnabled = enabled)
            "linux_exec" -> current.copy(isLinuxExecEnabled = enabled)
            "linux_process_status",
            "linux_process_output",
            "linux_process_kill" -> current.copy(isLinuxProcessToolsEnabled = enabled)
            "android_read_file" -> current.copy(isAndroidReadFileEnabled = enabled)
            "android_write_file" -> current.copy(isAndroidWriteFileEnabled = enabled)
            else -> current
        }
        updateSettings(updated)
    }
}
