package com.example.angi.ui.diagnostics

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.AngiDarkBackground
import com.example.ui.theme.AngiDarkSurface
import com.example.ui.theme.AngiDarkSurfaceVariant
import com.example.ui.theme.AngiPrimary
import com.example.ui.theme.AngiSecondary
import com.example.ui.theme.AngiSuccess
import com.example.ui.theme.AngiTertiary
import com.example.ui.theme.AngiTextPrimary
import com.example.ui.theme.AngiTextSecondary
import com.example.ui.theme.AngiTextTertiary

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiagnosticsScreen(
    viewModel: DiagnosticsViewModel,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsState()

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = AngiDarkBackground,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = AngiDarkSurface,
                    titleContentColor = AngiTextPrimary
                ),
                title = {
                    Text(
                        text = "Hardware Diagnostics",
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp,
                        color = AngiTextPrimary
                    )
                },
                actions = {
                    IconButton(
                        onClick = { viewModel.refreshDiagnostics() },
                        modifier = Modifier.testTag("refresh_diag_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "Refresh diagnostics",
                            tint = AngiPrimary
                        )
                    }
                }
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Target Hardware Status Card
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = AngiDarkSurface),
                    border = androidx.compose.foundation.BorderStroke(1.dp, AngiPrimary.copy(alpha = 0.4f))
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.Memory,
                                    contentDescription = null,
                                    tint = AngiPrimary,
                                    modifier = Modifier.size(22.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "Qualcomm Snapdragon NPU",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 15.sp,
                                    color = AngiTextPrimary
                                )
                            }
                            Surface(
                                shape = RoundedCornerShape(4.dp),
                                color = AngiSuccess.copy(alpha = 0.2f),
                                border = androidx.compose.foundation.BorderStroke(1.dp, AngiSuccess)
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.CheckCircle,
                                        contentDescription = null,
                                        tint = AngiSuccess,
                                        modifier = Modifier.size(10.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        text = "READY",
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = AngiSuccess
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        DiagRow("Target Architecture", "Snapdragon 8 Gen 2 / SM8550")
                        DiagRow("Device Model", android.os.Build.MODEL)
                        DiagRow("System Hardware", uiState.deviceHardware)
                        DiagRow("SoC Descriptor", uiState.socModel)
                        DiagRow("Android Version", uiState.androidVersion)
                        DiagRow("Supported ABIs", uiState.supportedAbis)
                    }
                }
            }

            // GenieX Runtime Status
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = AngiDarkSurface),
                    border = androidx.compose.foundation.BorderStroke(1.dp, AngiSecondary.copy(alpha = 0.3f))
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Speed,
                                contentDescription = null,
                                tint = AngiSecondary,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "GenieX Engine & Memory",
                                fontWeight = FontWeight.Bold,
                                fontSize = 15.sp,
                                color = AngiTextPrimary
                            )
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        DiagRow("SDK Version", uiState.runtimeInfo?.sdkVersion ?: "0.3.1")
                        DiagRow(
                            "SDK Init Status",
                            if (uiState.runtimeInfo?.isSdkInitialized == true) "Active (JNI bound)" else "Uninitialized"
                        )
                        DiagRow("Runtime State", uiState.runtimeInfo?.runtimeState?.name ?: "UNKNOWN")
                        DiagRow("Engine Backend", uiState.runtimeInfo?.backend ?: "None")
                        DiagRow("Active Compute Unit", uiState.runtimeInfo?.computeUnit ?: "NONE")
                        DiagRow("Available RAM", uiState.availRam)
                        DiagRow("Total System RAM", uiState.totalRam)
                        DiagRow("Active Model", if (uiState.runtimeInfo?.isModelLoaded == true) (uiState.activeModel?.name ?: "Loaded") else "None loaded")
                        if (!uiState.runtimeInfo?.lastError.isNullOrBlank()) {
                            DiagRow("Last Error", uiState.runtimeInfo?.lastError ?: "")
                        }
                    }
                }
            }

            // Architecture notes
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = AngiDarkSurfaceVariant)
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Info,
                                contentDescription = null,
                                tint = AngiTertiary,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "ANGI Snapdragon Architecture",
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp,
                                color = AngiTertiary
                            )
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "ANGI binds to Qualcomm AI Engine Direct (QAIRT) JNI native libraries for accelerated int4/int8 tensor math on Hexagon NPU DSPs, bypassing thermal limits while maintaining strict offline privacy.",
                            fontSize = 12.sp,
                            color = AngiTextSecondary,
                            lineHeight = 17.sp
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun DiagRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = label, fontSize = 12.sp, color = AngiTextSecondary)
        Text(
            text = value,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            fontFamily = FontFamily.Monospace,
            color = AngiTextPrimary
        )
    }
}
