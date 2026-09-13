package com.example.angi.ui.tools

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.AngiApp
import com.example.angi.domain.tools.ToolDefinition
import com.example.angi.domain.tools.ToolExecutor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class ToolItemState(
    val definition: ToolDefinition,
    val isEnabled: Boolean
)

data class ToolsUiState(
    val tools: List<ToolItemState> = emptyList(),
    val testOutput: String? = null
)

class ToolsViewModel : ViewModel() {

    private val toolRegistry = AngiApp.instance.toolRegistry
    private val settingsRepo = AngiApp.instance.settingsRepository
    private val toolExecutor = AngiApp.instance.toolExecutor

    private val _uiState = MutableStateFlow(ToolsUiState())
    val uiState: StateFlow<ToolsUiState> = _uiState.asStateFlow()

    init {
        refreshTools()
    }

    fun refreshTools() {
        val tools = toolRegistry.getAllDefinitions().map { def ->
            ToolItemState(
                definition = def,
                isEnabled = settingsRepo.isToolEnabled(def.name)
            )
        }
        _uiState.value = _uiState.value.copy(tools = tools)
    }

    fun toggleTool(name: String, enabled: Boolean) {
        settingsRepo.setToolEnabled(name, enabled)
        refreshTools()
    }

    fun testTool(name: String) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(testOutput = "Executing $name...")
            val result = when (name) {
                "device_info" -> toolExecutor.execute("device_info", emptyMap<String, Any?>())
                "share_text" -> toolExecutor.execute("share_text", mapOf<String, Any?>("text" to "Diagnostic test message from ANGI Tool Panel."))
                "web_fetch" -> toolExecutor.execute("web_fetch", mapOf<String, Any?>("url" to "https://httpbin.org/status/200"))
                "open_url" -> toolExecutor.execute("open_url", mapOf<String, Any?>("url" to "https://qualcomm.com"))
                else -> toolExecutor.execute(name, emptyMap<String, Any?>())
            }
            _uiState.value = _uiState.value.copy(
                testOutput = if (result.isSuccess) "Success:\n${result.output}" else "Failed:\n${result.error ?: result.output}"
            )
        }
    }
}
