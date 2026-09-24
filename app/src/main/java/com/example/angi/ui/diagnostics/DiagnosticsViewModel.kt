package com.example.angi.ui.diagnostics

import android.app.ActivityManager
import android.content.Context
import android.net.Uri
import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.AngiApp
import com.example.angi.data.models.ModelRepository
import com.example.angi.domain.environment.LinuxEnvironment
import com.example.angi.domain.environment.LinuxEnvironmentStatus
import com.example.angi.domain.environment.LinuxRwSelfTestResult
import com.example.angi.domain.environment.PinnedLinuxEnvironments
import com.example.angi.domain.inference.InferenceEngine
import com.example.angi.domain.inference.RuntimeInfo
import com.example.angi.domain.models.ModelDescriptor
import com.example.angi.domain.saf.AndroidSharedResource
import com.example.angi.domain.saf.AndroidSharedResourceRegistry
import com.example.angi.domain.saf.SafCapability
import com.example.angi.runtime.proot.InstallStep
import com.example.angi.runtime.proot.LinuxSandboxManager
import kotlinx.coroutines.Job
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
    val sharedResources: List<AndroidSharedResource> = emptyList(),
    // Install live status
    val currentInstallStage: String? = null,
    val currentInstallMessage: String? = null,
    val downloadProgress: Float? = null,
    val installRunning: Boolean = false
)

class DiagnosticsViewModel(
    private val inferenceEngine: InferenceEngine = AngiApp.instance.inferenceEngine,
    private val modelRepo: ModelRepository = AngiApp.instance.modelRepository,
    private val linuxSandboxManager: LinuxSandboxManager = AngiApp.instance.linuxSandboxManager,
    private val safRegistry: AndroidSharedResourceRegistry = AngiApp.instance.androidSharedResourceRegistry
) : ViewModel() {

    private val _uiState = MutableStateFlow(DiagnosticsUiState())
    val uiState: StateFlow<DiagnosticsUiState> = _uiState.asStateFlow()

    init {
        refreshDiagnostics()
        observeEnvironmentsAndResources()
    }

    private fun observeEnvironmentsAndResources() {
        viewModelScope.launch {
            linuxSandboxManager.environments().collect { envs ->
                _uiState.value = _uiState.value.copy(environments = envs)
            }
        }
        viewModelScope.launch {
            safRegistry.getResources().collect { res ->
                _uiState.value = _uiState.value.copy(sharedResources = res)
            }
        }
        viewModelScope.launch {
            linuxSandboxManager.installStep.collect { step ->
                if (step != null) {
                    val stage = step::class.simpleName ?: "Unknown"
                    val message = mapInstallStepToMessage(step)
                    val progress = if (step is InstallStep.Download) step.fraction else null
                    val isRunning = step !is InstallStep.Complete
                    _uiState.value = _uiState.value.copy(
                        currentInstallStage = stage,
                        currentInstallMessage = message,
                        downloadProgress = progress,
                        installRunning = isRunning,
                        isEnvironmentActionRunning = isRunning,
                        environmentActionMessage = message
                    )
                }
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

    fun installDebian(): Job {
        if (_uiState.value.installRunning) return Job().apply { complete() }
        return viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                installRunning = true,
                isEnvironmentActionRunning = true,
                currentInstallStage = "ResolveImage",
                currentInstallMessage = "Resolving Debian ARM64 image…",
                downloadProgress = null
            )
            val res = linuxSandboxManager.install()
            if (res.isFailure) {
                val err = res.exceptionOrNull()?.message ?: "Unknown error"
                _uiState.value = _uiState.value.copy(
                    installRunning = false,
                    isEnvironmentActionRunning = false,
                    currentInstallStage = "Failed",
                    currentInstallMessage = "Installation failed: $err",
                    downloadProgress = null,
                    environmentActionMessage = "Installation failed: $err"
                )
            } else {
                _uiState.value = _uiState.value.copy(
                    installRunning = false,
                    isEnvironmentActionRunning = false,
                    currentInstallStage = "Complete",
                    currentInstallMessage = "Debian ready.",
                    downloadProgress = null,
                    environmentActionMessage = "Debian ready."
                )
            }
        }
    }

    fun downloadEnvironment(definitionId: String): Job {
        return installDebian()
    }

    fun installEnvironment(environmentId: String): Job {
        return installDebian()
    }

    fun deleteEnvironment(environmentId: String = PinnedLinuxEnvironments.DEBIAN_12_ARM64.id): Job {
        return viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                isEnvironmentActionRunning = true,
                environmentActionMessage = "Deleting environment..."
            )
            linuxSandboxManager.delete(environmentId)
            _uiState.value = _uiState.value.copy(
                isEnvironmentActionRunning = false,
                environmentActionMessage = "Environment deleted.",
                currentInstallStage = null,
                currentInstallMessage = null,
                downloadProgress = null,
                installRunning = false,
                selfTestResult = null
            )
        }
    }

    fun runRwSelfTest(environmentId: String = PinnedLinuxEnvironments.DEBIAN_12_ARM64.id): Job {
        return viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                isEnvironmentActionRunning = true,
                environmentActionMessage = "Executing direct filesystem R/W self-test..."
            )
            val result = linuxSandboxManager.runSelfTest(environmentId)
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

    companion object {
        fun mapInstallStepToMessage(step: InstallStep): String {
            return when (step) {
                is InstallStep.ResolveImage -> "Resolving Debian ARM64 image…"
                is InstallStep.Download -> {
                    val pct = (step.fraction * 100).toInt()
                    "Downloading rootfs — $pct%"
                }
                is InstallStep.Extract -> "Extracting Debian filesystem…"
                is InstallStep.Configure -> "Configuring DNS / dpkg…"
                is InstallStep.ProotTest -> "Starting PRoot…"
                is InstallStep.AptUpdate -> "Updating apt package index…"
                is InstallStep.Packages -> "Installing base packages…"
                is InstallStep.Finalize -> "Finalizing installation…"
                is InstallStep.Complete -> "Debian ready."
            }
        }
    }
}
