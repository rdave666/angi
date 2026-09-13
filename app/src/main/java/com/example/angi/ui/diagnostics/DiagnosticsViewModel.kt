package com.example.angi.ui.diagnostics

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.AngiApp
import com.example.angi.domain.inference.RuntimeInfo
import com.example.angi.domain.models.ModelDescriptor
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
    val isSnapdragonTarget: Boolean = false
)

class DiagnosticsViewModel : ViewModel() {

    private val inferenceEngine = AngiApp.instance.inferenceEngine
    private val modelRepo = AngiApp.instance.modelRepository

    private val _uiState = MutableStateFlow(DiagnosticsUiState())
    val uiState: StateFlow<DiagnosticsUiState> = _uiState.asStateFlow()

    init {
        refreshDiagnostics()
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

            _uiState.value = DiagnosticsUiState(
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
}
