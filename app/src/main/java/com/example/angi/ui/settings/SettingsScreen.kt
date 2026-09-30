package com.example.angi.ui.settings

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
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.widget.Toast
import com.example.angi.domain.models.ComputeUnit
import com.example.angi.domain.models.RuntimeType
import com.example.ui.theme.AngiDarkBackground
import com.example.ui.theme.AngiDarkSurface
import com.example.ui.theme.AngiDarkSurfaceVariant
import com.example.ui.theme.AngiPrimary
import com.example.ui.theme.AngiSecondary
import com.example.ui.theme.AngiTertiary
import com.example.ui.theme.AngiTextPrimary
import com.example.ui.theme.AngiTextSecondary
import com.example.ui.theme.AngiTextTertiary

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    modifier: Modifier = Modifier
) {
    val settings by viewModel.settings.collectAsState()
    val apiServerState by viewModel.apiServerState.collectAsState()
    val runtimeInfo by viewModel.runtimeInfo.collectAsState()
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current

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
                        text = "Inference & Policy Settings",
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp,
                        color = AngiTextPrimary
                    )
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
            // Compute Unit Selection
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = AngiDarkSurface)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Memory,
                                contentDescription = null,
                                tint = AngiPrimary,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Preferred Compute Unit",
                                fontWeight = FontWeight.Bold,
                                fontSize = 15.sp,
                                color = AngiTextPrimary
                            )
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "Select Snapdragon accelerator target. Hexagon NPU gives peak throughput with lowest power consumption.",
                            fontSize = 12.sp,
                            color = AngiTextSecondary
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            ComputeUnit.values().forEach { unit ->
                                val isSelected = settings.computeUnit == unit
                                FilterChip(
                                    selected = isSelected,
                                    onClick = { viewModel.updateSettings(settings.copy(computeUnit = unit)) },
                                    label = { Text(unit.name, fontSize = 12.sp, fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal) },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = AngiPrimary,
                                        selectedLabelColor = AngiDarkBackground,
                                        containerColor = AngiDarkSurfaceVariant,
                                        labelColor = AngiTextPrimary
                                    ),
                                    modifier = Modifier.testTag("compute_chip_${unit.name}")
                                )
                            }
                        }
                    }
                }
            }

            // Runtime selection
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = AngiDarkSurface)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Tune,
                                contentDescription = null,
                                tint = AngiSecondary,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Runtime Backend",
                                fontWeight = FontWeight.Bold,
                                fontSize = 15.sp,
                                color = AngiTextPrimary
                            )
                        }
                        Spacer(modifier = Modifier.height(12.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            RuntimeType.values().forEach { runtime ->
                                val isSelected = settings.runtimeType == runtime
                                FilterChip(
                                    selected = isSelected,
                                    onClick = { viewModel.updateSettings(settings.copy(runtimeType = runtime)) },
                                    label = { Text(runtime.name, fontSize = 12.sp, fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal) },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = AngiSecondary,
                                        selectedLabelColor = AngiDarkBackground,
                                        containerColor = AngiDarkSurfaceVariant,
                                        labelColor = AngiTextPrimary
                                    ),
                                    modifier = Modifier.testTag("runtime_chip_${runtime.name}")
                                )
                            }
                        }
                    }
                }
            }

            // Sampling parameters
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = AngiDarkSurface)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "Sampling & Generation Controls",
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp,
                            color = AngiTextPrimary
                        )

                        Spacer(modifier = Modifier.height(12.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("Temperature", fontSize = 13.sp, color = AngiTextSecondary)
                            Text(
                                String.format("%.2f", settings.temperature),
                                fontSize = 13.sp,
                                color = AngiPrimary,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                        Slider(
                            value = settings.temperature,
                            onValueChange = { viewModel.updateSettings(settings.copy(temperature = it)) },
                            valueRange = 0.0f..1.5f,
                            colors = SliderDefaults.colors(
                                thumbColor = AngiPrimary,
                                activeTrackColor = AngiPrimary,
                                inactiveTrackColor = AngiDarkSurfaceVariant
                            )
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("Top-P", fontSize = 13.sp, color = AngiTextSecondary)
                            Text(
                                String.format("%.2f", settings.topP),
                                fontSize = 13.sp,
                                color = AngiPrimary,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                        Slider(
                            value = settings.topP,
                            onValueChange = { viewModel.updateSettings(settings.copy(topP = it)) },
                            valueRange = 0.1f..1.0f,
                            colors = SliderDefaults.colors(
                                thumbColor = AngiPrimary,
                                activeTrackColor = AngiPrimary,
                                inactiveTrackColor = AngiDarkSurfaceVariant
                            )
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("Max Tokens", fontSize = 13.sp, color = AngiTextSecondary)
                            Text(
                                "${settings.maxTokens}",
                                fontSize = 13.sp,
                                color = AngiPrimary,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                        Slider(
                            value = settings.maxTokens.toFloat(),
                            onValueChange = { viewModel.updateSettings(settings.copy(maxTokens = it.toInt())) },
                            valueRange = 128f..4096f,
                            steps = 30,
                            colors = SliderDefaults.colors(
                                thumbColor = AngiPrimary,
                                activeTrackColor = AngiPrimary,
                                inactiveTrackColor = AngiDarkSurfaceVariant
                            )
                        )
                    }
                }
            }

            // System prompt
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = AngiDarkSurface)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "System Instruction",
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp,
                            color = AngiTextPrimary
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedTextField(
                            value = settings.systemPrompt,
                            onValueChange = { viewModel.updateSettings(settings.copy(systemPrompt = it)) },
                            modifier = Modifier.fillMaxWidth(),
                            minLines = 3,
                            maxLines = 6,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = AngiTextPrimary,
                                unfocusedTextColor = AngiTextPrimary,
                                focusedContainerColor = AngiDarkSurfaceVariant,
                                unfocusedContainerColor = AngiDarkSurfaceVariant,
                                focusedBorderColor = AngiPrimary,
                                unfocusedBorderColor = androidx.compose.ui.graphics.Color.Transparent
                            ),
                            shape = RoundedCornerShape(8.dp)
                        )
                    }
                }
            }

            // Capability policy
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = AngiDarkSurface)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Security,
                                contentDescription = null,
                                tint = AngiTertiary,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Capability Policy & Permissions",
                                fontWeight = FontWeight.Bold,
                                fontSize = 15.sp,
                                color = AngiTextPrimary
                            )
                        }
                        Spacer(modifier = Modifier.height(12.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Auto-execute network tools", fontSize = 13.sp, color = AngiTextPrimary)
                                Text(
                                    "Allow web_fetch without explicit user prompt",
                                    fontSize = 11.sp,
                                    color = AngiTextSecondary
                                )
                            }
                            Switch(
                                checked = settings.allowUnconfirmedNetworkTools,
                                onCheckedChange = { viewModel.updateSettings(settings.copy(allowUnconfirmedNetworkTools = it)) },
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = AngiDarkBackground,
                                    checkedTrackColor = AngiPrimary
                                )
                            )
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Linux Execution (PRoot)", fontSize = 13.sp, color = AngiTextPrimary)
                                Text(
                                    "Enable linux_exec and process tools in Debian ARM64",
                                    fontSize = 11.sp,
                                    color = AngiTextSecondary
                                )
                            }
                            Switch(
                                checked = settings.isLinuxExecEnabled && settings.isLinuxProcessToolsEnabled,
                                onCheckedChange = {
                                    viewModel.updateSettings(
                                        settings.copy(
                                            isLinuxExecEnabled = it,
                                            isLinuxProcessToolsEnabled = it
                                        )
                                    )
                                },
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = AngiDarkBackground,
                                    checkedTrackColor = AngiPrimary
                                )
                            )
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Linux Workspace Tools", fontSize = 13.sp, color = AngiTextPrimary)
                                Text(
                                    "Enable linux_read_file and linux_write_file in /workspace/**",
                                    fontSize = 11.sp,
                                    color = AngiTextSecondary
                                )
                            }
                            Switch(
                                checked = settings.isLinuxReadFileEnabled && settings.isLinuxWriteFileEnabled,
                                onCheckedChange = {
                                    viewModel.updateSettings(
                                        settings.copy(
                                            isLinuxReadFileEnabled = it,
                                            isLinuxWriteFileEnabled = it,
                                            isLinuxListDirectoryEnabled = it
                                        )
                                    )
                                },
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = AngiDarkBackground,
                                    checkedTrackColor = AngiPrimary
                                )
                            )
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Android Shared File Access", fontSize = 13.sp, color = AngiTextPrimary)
                                Text(
                                    "Enable android_read_file and android_write_file via SAF",
                                    fontSize = 11.sp,
                                    color = AngiTextSecondary
                                )
                            }
                            Switch(
                                checked = settings.isAndroidReadFileEnabled && settings.isAndroidWriteFileEnabled,
                                onCheckedChange = {
                                    viewModel.updateSettings(
                                        settings.copy(
                                            isAndroidReadFileEnabled = it,
                                            isAndroidWriteFileEnabled = it
                                        )
                                    )
                                },
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = AngiDarkBackground,
                                    checkedTrackColor = AngiPrimary
                                )
                            )
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Auto-execute Android SAF Writes", fontSize = 13.sp, color = AngiTextPrimary)
                                Text(
                                    "Allow android_write_file without confirmation prompt",
                                    fontSize = 11.sp,
                                    color = AngiTextSecondary
                                )
                            }
                            Switch(
                                checked = settings.allowUnconfirmedAndroidWrite,
                                onCheckedChange = { viewModel.updateSettings(settings.copy(allowUnconfirmedAndroidWrite = it)) },
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = AngiDarkBackground,
                                    checkedTrackColor = AngiPrimary
                                )
                            )
                        }
                    }
                }
            }

            // OpenAI-Compatible Local HTTP API
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("api_server_card"),
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
                                    imageVector = Icons.Default.Dns,
                                    contentDescription = null,
                                    tint = AngiPrimary,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "OpenAI-Compatible API",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 15.sp,
                                    color = AngiTextPrimary
                                )
                            }
                            Switch(
                                checked = settings.isApiServerEnabled,
                                onCheckedChange = { viewModel.toggleApiServer(it, context) },
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = AngiDarkBackground,
                                    checkedTrackColor = AngiPrimary
                                ),
                                modifier = Modifier.testTag("api_server_enable_switch")
                            )
                        }

                        Text(
                            text = "Expose currently loaded on-device model over standard OpenAI endpoints (/v1/chat/completions, /v1/models, /health).",
                            fontSize = 12.sp,
                            color = AngiTextSecondary,
                            modifier = Modifier.padding(top = 4.dp, bottom = 12.dp)
                        )

                        // Mode: Device Only vs Local Network
                        Text("Mode", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = AngiTextPrimary)
                        Spacer(modifier = Modifier.height(4.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(
                                selected = !settings.apiServerBindLan,
                                onClick = { viewModel.setApiServerBindLan(false, context) },
                                label = { Text("Device Only (127.0.0.1)", fontSize = 11.sp) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = AngiPrimary,
                                    selectedLabelColor = AngiDarkBackground,
                                    containerColor = AngiDarkSurfaceVariant,
                                    labelColor = AngiTextPrimary
                                ),
                                modifier = Modifier.testTag("api_server_mode_device")
                            )
                            FilterChip(
                                selected = settings.apiServerBindLan,
                                onClick = { viewModel.setApiServerBindLan(true, context) },
                                label = { Text("Local Network (0.0.0.0)", fontSize = 11.sp) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = AngiSecondary,
                                    selectedLabelColor = AngiDarkBackground,
                                    containerColor = AngiDarkSurfaceVariant,
                                    labelColor = AngiTextPrimary
                                ),
                                modifier = Modifier.testTag("api_server_mode_lan")
                            )
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        // Port Configuration
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("Port", fontSize = 13.sp, color = AngiTextPrimary)
                            OutlinedTextField(
                                value = settings.apiServerPort.toString(),
                                onValueChange = { input ->
                                    val portNum = input.filter { it.isDigit() }.toIntOrNull()
                                    if (portNum != null && portNum in 1024..65535) {
                                        viewModel.setApiServerPort(portNum, context)
                                    }
                                },
                                singleLine = true,
                                modifier = Modifier
                                    .width(110.dp)
                                    .testTag("api_server_port_input"),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedContainerColor = AngiDarkSurfaceVariant,
                                    unfocusedContainerColor = AngiDarkSurfaceVariant,
                                    focusedBorderColor = AngiPrimary,
                                    unfocusedBorderColor = AngiTextTertiary.copy(alpha = 0.3f),
                                    focusedTextColor = AngiTextPrimary,
                                    unfocusedTextColor = AngiTextPrimary
                                ),
                                textStyle = androidx.compose.ui.text.TextStyle(
                                    fontSize = 13.sp,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Medium
                                )
                            )
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        // Endpoint & Copy Endpoint
                        val currentHost = if (settings.apiServerBindLan) "0.0.0.0" else "127.0.0.1"
                        val endpoint = "http://$currentHost:${settings.apiServerPort}"
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(AngiDarkSurfaceVariant, RoundedCornerShape(8.dp))
                                .padding(10.dp)
                        ) {
                            Text("Endpoint", fontSize = 11.sp, color = AngiTextSecondary)
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "$endpoint/v1",
                                    fontSize = 12.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = AngiPrimary,
                                    modifier = Modifier
                                        .weight(1f)
                                        .testTag("api_server_endpoint_text")
                                )
                                OutlinedButton(
                                    onClick = {
                                        clipboardManager.setText(AnnotatedString("$endpoint/v1"))
                                        Toast.makeText(context, "Endpoint copied", Toast.LENGTH_SHORT).show()
                                    },
                                    modifier = Modifier.testTag("btn_copy_endpoint")
                                ) {
                                    Icon(Icons.Default.ContentCopy, contentDescription = "Copy Endpoint", modifier = Modifier.size(12.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Copy Endpoint", fontSize = 10.sp)
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        // API Key & Copy API Key
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(AngiDarkSurfaceVariant, RoundedCornerShape(8.dp))
                                .padding(10.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("API Key (Required for LAN mode)", fontSize = 11.sp, color = AngiTextSecondary)
                                IconButton(
                                    onClick = { viewModel.regenerateApiKey(context) },
                                    modifier = Modifier.size(24.dp).testTag("btn_regenerate_api_key")
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Refresh,
                                        contentDescription = "Regenerate Key",
                                        tint = AngiTextSecondary,
                                        modifier = Modifier.size(14.dp)
                                    )
                                }
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = settings.apiServerApiKey,
                                    fontSize = 12.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = AngiTextPrimary,
                                    modifier = Modifier
                                        .weight(1f)
                                        .testTag("api_server_key_text")
                                )
                                OutlinedButton(
                                    onClick = {
                                        clipboardManager.setText(AnnotatedString(settings.apiServerApiKey))
                                        Toast.makeText(context, "API Key copied", Toast.LENGTH_SHORT).show()
                                    },
                                    modifier = Modifier.testTag("btn_copy_api_key")
                                ) {
                                    Icon(Icons.Default.ContentCopy, contentDescription = "Copy API Key", modifier = Modifier.size(12.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Copy API Key", fontSize = 10.sp)
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        // Server Status, Loaded model & Compute unit
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 3.dp),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("Server status", fontSize = 12.sp, color = AngiTextSecondary)
                            val isRunning = apiServerState.isRunning
                            Surface(
                                shape = RoundedCornerShape(4.dp),
                                color = if (isRunning) AngiSecondary.copy(alpha = 0.2f) else AngiDarkSurfaceVariant,
                                border = androidx.compose.foundation.BorderStroke(
                                    1.dp,
                                    if (isRunning) AngiSecondary else AngiTextTertiary.copy(alpha = 0.4f)
                                )
                            ) {
                                Text(
                                    text = if (isRunning) "RUNNING" else "STOPPED",
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (isRunning) AngiSecondary else AngiTextTertiary,
                                    modifier = Modifier
                                        .padding(horizontal = 6.dp, vertical = 2.dp)
                                        .testTag("api_server_status_text")
                                )
                            }
                        }

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 3.dp),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("Loaded model", fontSize = 12.sp, color = AngiTextSecondary)
                            Text(
                                text = if (runtimeInfo.isModelLoaded) (runtimeInfo.loadedModelId ?: "Active Model") else "No model loaded",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium,
                                fontFamily = FontFamily.Monospace,
                                color = if (runtimeInfo.isModelLoaded) AngiPrimary else AngiTextTertiary,
                                modifier = Modifier.testTag("api_server_model_text")
                            )
                        }

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 3.dp),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("Compute unit", fontSize = 12.sp, color = AngiTextSecondary)
                            Text(
                                text = if (runtimeInfo.isModelLoaded) runtimeInfo.computeUnit else "NONE",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium,
                                fontFamily = FontFamily.Monospace,
                                color = AngiTextPrimary,
                                modifier = Modifier.testTag("api_server_compute_text")
                            )
                        }

                        if (apiServerState.errorMessage != null) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "Error: ${apiServerState.errorMessage}",
                                fontSize = 11.sp,
                                color = androidx.compose.material3.MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }
            }

            // Version & Build Information
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("settings_version_card"),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = AngiDarkSurface),
                    border = androidx.compose.foundation.BorderStroke(1.dp, AngiPrimary.copy(alpha = 0.3f))
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "Application Information",
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp,
                            color = AngiTextPrimary
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(text = "Version", fontSize = 12.sp, color = AngiTextSecondary)
                            Text(
                                text = com.example.BuildConfig.VERSION_NAME,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium,
                                fontFamily = FontFamily.Monospace,
                                color = AngiTextPrimary
                            )
                        }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(text = "Build", fontSize = 12.sp, color = AngiTextSecondary)
                            Text(
                                text = "${com.example.BuildConfig.VERSION_CODE}",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium,
                                fontFamily = FontFamily.Monospace,
                                color = AngiTextPrimary
                            )
                        }
                    }
                }
            }

            // Subtle Footer
            item {
                androidx.compose.foundation.layout.Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp)
                        .testTag("settings_version_footer"),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "ANGI v${com.example.BuildConfig.VERSION_NAME} (build ${com.example.BuildConfig.VERSION_CODE})",
                        fontSize = 11.sp,
                        color = AngiTextTertiary,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        }
    }
}
