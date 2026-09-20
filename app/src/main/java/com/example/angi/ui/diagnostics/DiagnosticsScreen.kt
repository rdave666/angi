package com.example.angi.ui.diagnostics

import android.app.Activity
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.angi.domain.environment.LinuxEnvironmentStatus
import com.example.ui.theme.AngiDarkBackground
import com.example.ui.theme.AngiDarkSurface
import com.example.ui.theme.AngiDarkSurfaceElevated
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
    val context = LocalContext.current

    val safFolderLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            try {
                val takeFlags: Int = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                context.contentResolver.takePersistableUriPermission(uri, takeFlags)
            } catch (_: Throwable) {}
            val folderName = uri.lastPathSegment?.substringAfterLast(':')?.substringAfterLast('/') ?: "documents"
            val sanitizedId = folderName.lowercase().replace(Regex("[^a-z0-9_]"), "_").take(24).ifEmpty { "folder" }
            val uniqueResourceId = "${sanitizedId}_${System.currentTimeMillis() % 10000}"
            viewModel.registerSafResource(
                treeUri = uri,
                resourceId = uniqueResourceId,
                displayName = folderName.ifEmpty { "Granted Folder" }
            )
        }
    }

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
                        text = "Diagnostics & Environments",
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
            // Checkpoint B: Isolated Linux Filesystem Environment Card
            item {
                Card(
                    modifier = Modifier.fillMaxWidth().testTag("linux_environment_card"),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = AngiDarkSurface),
                    border = androidx.compose.foundation.BorderStroke(1.dp, AngiSecondary.copy(alpha = 0.5f))
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.Terminal,
                                    contentDescription = null,
                                    tint = AngiSecondary,
                                    modifier = Modifier.size(22.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "Linux Filesystem Environment",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 15.sp,
                                    color = AngiTextPrimary
                                )
                            }

                            val env = uiState.environments.firstOrNull()
                            val status = env?.status ?: LinuxEnvironmentStatus.NOT_INSTALLED
                            val statusColor = when (status) {
                                LinuxEnvironmentStatus.INSTALLED -> AngiSuccess
                                LinuxEnvironmentStatus.FAILED -> androidx.compose.ui.graphics.Color(0xFFFF5252)
                                LinuxEnvironmentStatus.DOWNLOADING, LinuxEnvironmentStatus.VERIFYING, LinuxEnvironmentStatus.EXTRACTING -> AngiSecondary
                                LinuxEnvironmentStatus.NOT_INSTALLED -> AngiTextTertiary
                            }
                            val statusLabel = when (status) {
                                LinuxEnvironmentStatus.INSTALLED -> "INSTALLED"
                                LinuxEnvironmentStatus.FAILED -> "FAILED"
                                LinuxEnvironmentStatus.DOWNLOADING -> "DOWNLOADING"
                                LinuxEnvironmentStatus.VERIFYING -> "VERIFYING"
                                LinuxEnvironmentStatus.EXTRACTING -> "EXTRACTING"
                                LinuxEnvironmentStatus.NOT_INSTALLED -> "NOT INSTALLED"
                            }

                            Surface(
                                shape = RoundedCornerShape(4.dp),
                                color = statusColor.copy(alpha = 0.15f),
                                border = androidx.compose.foundation.BorderStroke(1.dp, statusColor)
                            ) {
                                Text(
                                    text = statusLabel,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = statusColor
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        val currentEnv = uiState.environments.firstOrNull()
                        val def = currentEnv?.definition
                        val meta = currentEnv?.metadata

                        DiagRow("Distribution", def?.distribution ?: "Alpine Linux")
                        DiagRow("Version", def?.version ?: "3.21.8")
                        DiagRow("Architecture", def?.architecture ?: "aarch64")
                        DiagRow("Virtual Workspace", "/workspace")
                        DiagRow("Expected SHA-256", (def?.expectedSha256?.take(16) ?: "") + "...")
                        if (meta?.actualSha256 != null) {
                            DiagRow("Actual SHA-256", (meta.actualSha256.take(16)) + "...")
                        }
                        if (meta != null && meta.archiveSizeBytes > 0) {
                            DiagRow("Archive Size", "${meta.archiveSizeBytes / 1024} KB")
                        }
                        if (meta != null && meta.installedSizeBytes > 0) {
                            DiagRow("Installed Rootfs", "${meta.installedSizeBytes / (1024 * 1024)} MB")
                        }
                        if (!meta?.lastError.isNullOrBlank()) {
                            DiagRow("Last Error", meta?.lastError ?: "")
                        }

                        if (uiState.environmentActionMessage != null) {
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = uiState.environmentActionMessage ?: "",
                                fontSize = 11.sp,
                                color = AngiSecondary
                            )
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        // Actions
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            if (currentEnv?.status != LinuxEnvironmentStatus.INSTALLED) {
                                Button(
                                    onClick = {
                                        def?.let { viewModel.downloadEnvironment(it.id) }
                                    },
                                    modifier = Modifier.weight(1f).testTag("btn_download_env"),
                                    enabled = !uiState.isEnvironmentActionRunning,
                                    colors = ButtonDefaults.buttonColors(containerColor = AngiPrimary)
                                ) {
                                    Icon(imageVector = Icons.Default.Download, contentDescription = null, modifier = Modifier.size(14.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Download", fontSize = 11.sp)
                                }

                                Button(
                                    onClick = {
                                        def?.let { viewModel.installEnvironment(it.id) }
                                    },
                                    modifier = Modifier.weight(1f).testTag("btn_install_env"),
                                    enabled = !uiState.isEnvironmentActionRunning,
                                    colors = ButtonDefaults.buttonColors(containerColor = AngiSecondary)
                                ) {
                                    Text("Install", fontSize = 11.sp)
                                }
                            } else {
                                Button(
                                    onClick = {
                                        def?.let { viewModel.runRwSelfTest(it.id) }
                                    },
                                    modifier = Modifier.weight(1f).testTag("btn_selftest_env"),
                                    enabled = !uiState.isEnvironmentActionRunning,
                                    colors = ButtonDefaults.buttonColors(containerColor = AngiSecondary)
                                ) {
                                    Icon(imageVector = Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(14.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Run R/W Test", fontSize = 11.sp)
                                }

                                OutlinedButton(
                                    onClick = {
                                        def?.let { viewModel.deleteEnvironment(it.id) }
                                    },
                                    modifier = Modifier.weight(1f).testTag("btn_delete_env"),
                                    enabled = !uiState.isEnvironmentActionRunning
                                ) {
                                    Icon(imageVector = Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(14.dp), tint = androidx.compose.ui.graphics.Color(0xFFFF5252))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Delete", fontSize = 11.sp, color = androidx.compose.ui.graphics.Color(0xFFFF5252))
                                }
                            }
                        }

                        // R/W Self test result
                        uiState.selfTestResult?.let { st ->
                            Spacer(modifier = Modifier.height(10.dp))
                            Surface(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(8.dp),
                                color = AngiDarkSurfaceVariant
                            ) {
                                Column(modifier = Modifier.padding(10.dp)) {
                                    Text("Direct Filesystem Self-Test Results:", fontWeight = FontWeight.Bold, fontSize = 12.sp, color = AngiTextPrimary)
                                    Spacer(modifier = Modifier.height(4.dp))
                                    DiagRow("Write Test", if (st.writeSuccess) "PASS" else "FAIL")
                                    DiagRow("Read Test", if (st.readSuccess) "PASS" else "FAIL")
                                    DiagRow("Modify Test", if (st.modifySuccess) "PASS" else "FAIL")
                                    Text(st.details, fontSize = 11.sp, color = AngiTextSecondary, fontFamily = FontFamily.Monospace)
                                }
                            }
                        }
                    }
                }
            }

            // Checkpoint B: Android Shared Storage (SAF Grants) Card
            item {
                Card(
                    modifier = Modifier.fillMaxWidth().testTag("android_saf_card"),
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
                                    imageVector = Icons.Default.FolderOpen,
                                    contentDescription = null,
                                    tint = AngiPrimary,
                                    modifier = Modifier.size(22.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "Android Shared Storage (SAF)",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 15.sp,
                                    color = AngiTextPrimary
                                )
                            }

                            Button(
                                onClick = { safFolderLauncher.launch(null) },
                                colors = ButtonDefaults.buttonColors(containerColor = AngiPrimary),
                                modifier = Modifier.testTag("btn_grant_folder")
                            ) {
                                Icon(imageVector = Icons.Default.Folder, contentDescription = null, modifier = Modifier.size(14.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Grant Folder", fontSize = 11.sp)
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Model accesses shared files ONLY via explicit user grants. Physical host paths (/sdcard) are forbidden.",
                            fontSize = 11.sp,
                            color = AngiTextSecondary
                        )

                        Spacer(modifier = Modifier.height(10.dp))

                        if (uiState.sharedResources.isEmpty()) {
                            Surface(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(8.dp),
                                color = AngiDarkSurfaceVariant
                            ) {
                                Text(
                                    text = "No shared storage folders granted yet. Click 'Grant Folder' to allow model access to a specific directory.",
                                    modifier = Modifier.padding(10.dp),
                                    fontSize = 12.sp,
                                    color = AngiTextTertiary
                                )
                            }
                        } else {
                            uiState.sharedResources.forEach { res ->
                                Surface(
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                    shape = RoundedCornerShape(8.dp),
                                    color = AngiDarkSurfaceElevated
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth().padding(10.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = res.displayName,
                                                fontSize = 13.sp,
                                                fontWeight = FontWeight.SemiBold,
                                                color = AngiTextPrimary
                                            )
                                            Text(
                                                text = "ID: ${res.resourceId} • ${res.capability.name}",
                                                fontSize = 11.sp,
                                                fontFamily = FontFamily.Monospace,
                                                color = AngiTextSecondary
                                            )
                                        }
                                        IconButton(
                                            onClick = { viewModel.revokeSafResource(res.resourceId) }
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Delete,
                                                contentDescription = "Revoke grant",
                                                tint = androidx.compose.ui.graphics.Color(0xFFFF5252),
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

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
                        DiagRow("SoC Chipset", uiState.socModel)
                        DiagRow("Target ABI", uiState.supportedAbis)
                        DiagRow("Android OS", uiState.androidVersion)
                        DiagRow("SM8550 Match", if (uiState.isSnapdragonTarget) "TRUE" else "EMULATED / COMPAT")
                    }
                }
            }

            // Engine & Execution State Card
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = AngiDarkSurfaceElevated)
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
                                text = "GenieX Engine & Runtime State",
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp,
                                color = AngiTextPrimary
                            )
                        }

                        Spacer(modifier = Modifier.height(10.dp))

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
                                text = "ANGI Security & Architecture",
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp,
                                color = AngiTertiary
                            )
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "ANGI strictly separates the model-owned isolated Linux filesystem (/workspace/**) from Android shared storage. Android shared storage is accessible ONLY through user-approved Storage Access Framework (SAF) grants, protecting private app storage and device credentials.",
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
