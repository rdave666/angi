package com.example.angi.ui.tools

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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.AngiDarkBackground
import com.example.ui.theme.AngiDarkSurface
import com.example.ui.theme.AngiDarkSurfaceElevated
import com.example.ui.theme.AngiDarkSurfaceVariant
import com.example.ui.theme.AngiPrimary
import com.example.ui.theme.AngiSecondary
import com.example.ui.theme.AngiTertiary
import com.example.ui.theme.AngiTextPrimary
import com.example.ui.theme.AngiTextSecondary
import com.example.ui.theme.AngiTextTertiary

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ToolsScreen(
    viewModel: ToolsViewModel,
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
                        text = "Capability & Tool Registry",
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
            // Explanation card
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = AngiDarkSurface),
                    border = androidx.compose.foundation.BorderStroke(1.dp, AngiTertiary.copy(alpha = 0.3f))
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Build,
                                contentDescription = null,
                                tint = AngiTertiary,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Real Executable Capabilities",
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp,
                                color = AngiTextPrimary
                            )
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "ANGI does not simulate tools with fake system prompts. When the on-device model invokes a tool, ANGI parses the JSON schema, checks the capability policy, and executes real Android platform APIs.",
                            fontSize = 12.sp,
                            color = AngiTextSecondary,
                            lineHeight = 18.sp
                        )
                    }
                }
            }

            // Test output console
            if (uiState.testOutput != null) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp),
                        colors = CardDefaults.cardColors(containerColor = AngiDarkSurfaceElevated),
                        border = androidx.compose.foundation.BorderStroke(1.dp, AngiPrimary.copy(alpha = 0.4f))
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(
                                text = "TOOL EXECUTION CONSOLE",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = AngiPrimary,
                                fontFamily = FontFamily.Monospace
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = uiState.testOutput!!,
                                fontSize = 12.sp,
                                color = AngiTextPrimary,
                                fontFamily = FontFamily.Monospace,
                                lineHeight = 16.sp
                            )
                        }
                    }
                }
            }

            // Tools list
            items(uiState.tools, key = { it.definition.name }) { toolItem ->
                ToolCard(
                    item = toolItem,
                    onToggle = { enabled -> viewModel.toggleTool(toolItem.definition.name, enabled) },
                    onTest = { viewModel.testTool(toolItem.definition.name) }
                )
            }
        }
    }
}

@Composable
fun ToolCard(
    item: ToolItemState,
    onToggle: (Boolean) -> Unit,
    onTest: () -> Unit
) {
    val icon = when (item.definition.name) {
        "share_text" -> Icons.Default.Share
        "device_info" -> Icons.Default.PhoneAndroid
        "web_fetch" -> Icons.Default.Language
        "open_url" -> Icons.Default.OpenInBrowser
        else -> Icons.Default.Build
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = AngiDarkSurface),
        border = androidx.compose.foundation.BorderStroke(1.dp, AngiDarkSurfaceVariant)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = icon,
                        contentDescription = item.definition.name,
                        tint = if (item.isEnabled) AngiPrimary else AngiTextTertiary,
                        modifier = Modifier.size(22.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Text(
                            text = item.definition.name,
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp,
                            fontFamily = FontFamily.Monospace,
                            color = AngiTextPrimary
                        )
                        Text(
                            text = "Category: ${item.definition.category.name}",
                            fontSize = 11.sp,
                            color = AngiTextSecondary
                        )
                    }
                }

                Switch(
                    checked = item.isEnabled,
                    onCheckedChange = onToggle,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = AngiDarkBackground,
                        checkedTrackColor = AngiPrimary,
                        uncheckedThumbColor = AngiTextTertiary,
                        uncheckedTrackColor = AngiDarkSurfaceVariant
                    ),
                    modifier = Modifier.testTag("toggle_tool_${item.definition.name}")
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = item.definition.description,
                fontSize = 13.sp,
                color = AngiTextSecondary,
                lineHeight = 18.sp
            )

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                OutlinedButton(
                    onClick = onTest,
                    enabled = item.isEnabled,
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = AngiSecondary),
                    border = androidx.compose.foundation.BorderStroke(1.dp, if (item.isEnabled) AngiSecondary else AngiTextTertiary),
                    modifier = Modifier.testTag("test_tool_${item.definition.name}")
                ) {
                    Icon(
                        imageVector = Icons.Default.PlayArrow,
                        contentDescription = "Test Tool",
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Test Tool", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}
