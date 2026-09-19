package com.example.angi.ui.diagnostics

import android.app.ActivityManager
import android.content.Context
import android.net.Uri
import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.AngiApp
import com.example.angi.domain.environment.LinuxEnvironment
import com.example.angi.domain.environment.LinuxEnvironmentStatus
import com.example.angi.domain.environment.LinuxRwSelfTestResult
import com.example.angi.domain.environment.PinnedLinuxEnvironments
import com.example.angi.domain.inference.RuntimeInfo
import com.example.angi.domain.models.ModelDescriptor
import com.example.angi.domain.saf.AndroidSharedResource
import com.example.angi.domain.saf.SafCapability
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class DiagnosticsUiState(
    val runtimeInfo: RuntimeInfo? = null,
    val activeModel: ModelDescriptor? = null,
    val totalRam: String = "",
    val availRam: String = "",
    val socModel: String = "",
    val deviceHardware: String = "",
    val androidVersion: String = "",
    val supportedAbis: String = "",
    val isSnapdragonTarget: Boolean = false,
    // Checkpoint B states
    val environments: List<LinuxEnvironment> = emptyList(),
    val isEnvironmentActionRunning: Boolean = false,
    val environmentActionMessage: String? = null,
    val selfTestResult: LinuxRwSelfTestResult? = null,
    val sharedResources: List<AndroidSharedResource> = emptyList()
)

class DiagnosticsViewModel : ViewModel() {

    private val inferenceEngine = AngiApp.instance.inferenceEngine
    private val modelRepo = AngiApp.instance.modelRepository
    private val linuxEnvManager = AngiApp.instance.linuxEnvironmentManager
    private val safRegistry = AngiApp.instance.androidSharedResourceRegistry

    private val _uiState = MutableStateFlow(DiagnosticsUiState())
    val uiState: StateFlow<DiagnosticsUiState> = _uiState.asStateFlow()

    init {
        refreshDiagnostics()
        observeEnvironmentsAndResources()
    }

    private fun observeEnvironmentsAndResources() {
        viewModelScope.launch {
            linuxEnvManager.environments().collect { envs ->
                _uiState.value = _uiState.value.copy(environments = envs)
            }
        }
        viewModelScope.launch {
            safRegistry.getResources().collect { res ->
                _uiState.value = _uiState.value.copy(sharedResources = res)
            }
        }
    }

    fun refreshDiagnostics() {
        viewModelScope.launch {
            val context = AngiApp.instance.applicationContext
            val actManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            val memInfo = ActivityManager.MemoryInfo()
            actManager?.getMemoryInfo(memInfo)

            val totalGb = memInfo.totalMem / (1024.0 * 1024.0 * 1024.0)
            val availGb = memInfo.availMem / (1024.0 * 1024.0 * 1024.0)
            val soc = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Build.SOC_MODEL else "SM8550"

            val isSnapdragon = Build.HARDWARE.contains("qcom", ignoreCase = true) ||
                    Build.MODEL.contains("S918", ignoreCase = true) ||
                    soc.contains("SM8550", ignoreCase = true)

            _uiState.value = _uiState.value.copy(
                runtimeInfo = inferenceEngine.runtimeInfo(),
                activeModel = modelRepo.getActiveModel(),
                totalRam = String.format("%.2f GB", totalGb),
                availRam = String.format("%.2f GB", availGb),
                socModel = if (soc.isNotBlank()) soc else "Snapdragon 8 Gen 2",
                deviceHardware = "${Build.MANUFACTURER} ${Build.MODEL} (${Build.HARDWARE})",
                androidVersion = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
                supportedAbis = Build.SUPPORTED_ABIS.joinToString(", "),
                isSnapdragonTarget = isSnapdragon
            )
        }
    }

    fun downloadEnvironment(definitionId: String) {
        val def = PinnedLinuxEnvironments.DEFAULT_DEFINITIONS.find { it.id == definitionId } ?: return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                isEnvironmentActionRunning = true,
                environmentActionMessage = "Downloading ${def.distribution} archive..."
            )
            val res = linuxEnvManager.download(def)
            _uiState.value = _uiState.value.copy(
                isEnvironmentActionRunning = false,
                environmentActionMessage = if (res.isSuccess) "Download and SHA-256 verification complete!" else "Download failed: ${res.exceptionOrNull()?.message}"
            )
        }
    }

    fun installEnvironment(environmentId: String) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                isEnvironmentActionRunning = true,
                environmentActionMessage = "Extracting isolated Linux filesystem..."
            )
            val res = linuxEnvManager.install(environmentId)
            _uiState.value = _uiState.value.copy(
                isEnvironmentActionRunning = false,
                environmentActionMessage = if (res.isSuccess) "Linux filesystem environment installed!" else "Installation failed: ${res.exceptionOrNull()?.message}"
            )
        }
    }

    fun deleteEnvironment(environmentId: String) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                isEnvironmentActionRunning = true,
                environmentActionMessage = "Deleting environment..."
            )
            linuxEnvManager.delete(environmentId)
            _uiState.value = _uiState.value.copy(
                isEnvironmentActionRunning = false,
                environmentActionMessage = "Environment deleted.",
                selfTestResult = null
            )
        }
    }

    fun runRwSelfTest(environmentId: String) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                isEnvironmentActionRunning = true,
                environmentActionMessage = "Executing direct filesystem R/W self-test..."
            )
            val result = linuxEnvManager.runSelfTest(environmentId)
            _uiState.value = _uiState.value.copy(
                isEnvironmentActionRunning = false,
                environmentActionMessage = null,
                selfTestResult = result
            )
        }
    }

    fun registerSafResource(treeUri: Uri, resourceId: String = "user_documents", displayName: String = "Documents") {
        viewModelScope.launch {
            safRegistry.register(
                AndroidSharedResource(
                    resourceId = resourceId,
                    displayName = displayName,
                    treeUriString = treeUri.toString(),
                    capability = SafCapability.READ_WRITE
                )
            )
        }
    }

    fun revokeSafResource(resourceId: String) {
        viewModelScope.launch {
            safRegistry.unregister(resourceId)
        }
    }
}
