package com.example.angi.ui.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.angi.data.settings.AngiSettings
import com.example.angi.data.settings.InferenceSource
import com.example.ui.theme.AngiDarkBackground
import com.example.ui.theme.AngiDarkSurface
import com.example.ui.theme.AngiDarkSurfaceVariant
import com.example.ui.theme.AngiPrimary
import com.example.ui.theme.AngiSecondary
import com.example.ui.theme.AngiTextPrimary
import com.example.ui.theme.AngiTextSecondary
import com.example.ui.theme.AngiTextTertiary

@Composable
fun ExternalModelSettingsCard(
    viewModel: SettingsViewModel,
    settings: AngiSettings
) {
    val state by viewModel.externalProviderState.collectAsState()
    var modelMenuExpanded by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("external_provider_card"),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = AngiDarkSurface),
        border = BorderStroke(1.dp, AngiSecondary.copy(alpha = 0.45f))
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Cloud,
                    contentDescription = null,
                    tint = AngiSecondary
                )
                Spacer(modifier = Modifier.width(8.dp))
                Column {
                    Text(
                        text = "Model Source",
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp,
                        color = AngiTextPrimary
                    )
                    Text(
                        text = "Keep local GenieX loaded or route chat to an OpenAI-compatible endpoint.",
                        fontSize = 11.sp,
                        color = AngiTextSecondary
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = settings.inferenceSource == InferenceSource.LOCAL,
                    onClick = { viewModel.setInferenceSource(InferenceSource.LOCAL) },
                    label = { Text("Local") },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = AngiPrimary,
                        selectedLabelColor = AngiDarkBackground,
                        containerColor = AngiDarkSurfaceVariant,
                        labelColor = AngiTextPrimary
                    ),
                    modifier = Modifier.testTag("model_source_local")
                )
                FilterChip(
                    selected = settings.inferenceSource == InferenceSource.EXTERNAL,
                    onClick = { viewModel.setInferenceSource(InferenceSource.EXTERNAL) },
                    label = { Text("External") },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = AngiSecondary,
                        selectedLabelColor = AngiDarkBackground,
                        containerColor = AngiDarkSurfaceVariant,
                        labelColor = AngiTextPrimary
                    ),
                    modifier = Modifier.testTag("model_source_external")
                )
            }

            Spacer(modifier = Modifier.height(14.dp))

            Text(
                text = "External OpenAI-Compatible Provider",
                fontWeight = FontWeight.SemiBold,
                fontSize = 13.sp,
                color = AngiTextPrimary
            )
            Text(
                text = "Enter the API base URL and key, then load /v1/models.",
                fontSize = 11.sp,
                color = AngiTextSecondary
            )

            Spacer(modifier = Modifier.height(8.dp))

            OutlinedTextField(
                value = settings.externalApiBaseUrl,
                onValueChange = viewModel::setExternalApiBaseUrl,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("external_provider_url"),
                singleLine = true,
                label = { Text("Endpoint") },
                placeholder = { Text("https://host.example/v1") },
                colors = externalTextFieldColors()
            )

            Spacer(modifier = Modifier.height(8.dp))

            OutlinedTextField(
                value = settings.externalApiKey,
                onValueChange = viewModel::setExternalApiKey,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("external_provider_key"),
                singleLine = true,
                label = { Text("API key") },
                visualTransformation = PasswordVisualTransformation(),
                colors = externalTextFieldColors()
            )

            Spacer(modifier = Modifier.height(10.dp))

            Button(
                onClick = viewModel::refreshExternalModels,
                enabled = !state.isLoading &&
                    settings.externalApiBaseUrl.isNotBlank() &&
                    settings.externalApiKey.isNotBlank(),
                modifier = Modifier.testTag("external_provider_refresh")
            ) {
                Icon(Icons.Default.Refresh, contentDescription = null)
                Spacer(modifier = Modifier.width(6.dp))
                Text(if (state.isLoading) "Loading…" else "Load models")
            }

            Spacer(modifier = Modifier.height(10.dp))

            Box(modifier = Modifier.fillMaxWidth()) {
                Button(
                    onClick = { modelMenuExpanded = true },
                    enabled = state.models.isNotEmpty(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("external_model_selector")
                ) {
                    Text(
                        text = settings.externalModelId.ifBlank {
                            if (state.models.isEmpty()) "No models loaded" else "Select model"
                        },
                        fontFamily = FontFamily.Monospace,
                        maxLines = 1
                    )
                }

                DropdownMenu(
                    expanded = modelMenuExpanded,
                    onDismissRequest = { modelMenuExpanded = false }
                ) {
                    state.models.forEach { modelId ->
                        DropdownMenuItem(
                            text = {
                                Text(
                                    text = modelId,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 12.sp
                                )
                            },
                            onClick = {
                                viewModel.setExternalModel(modelId)
                                modelMenuExpanded = false
                            }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            when {
                state.isLoading -> Text(
                    text = "Connecting to provider…",
                    fontSize = 11.sp,
                    color = AngiTextSecondary
                )
                state.error != null -> Text(
                    text = state.error ?: "Provider error",
                    fontSize = 11.sp,
                    color = androidx.compose.material3.MaterialTheme.colorScheme.error
                )
                state.models.isNotEmpty() -> Text(
                    text = "Connected · ${state.models.size} model(s) · selected: ${settings.externalModelId}",
                    fontSize = 11.sp,
                    color = AngiSecondary
                )
                else -> Text(
                    text = "Not connected",
                    fontSize = 11.sp,
                    color = AngiTextTertiary
                )
            }

            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = "External models receive ANGI's enabled tool definitions. linux_write_file writes into the same Debian PRoot /workspace used by local models and the phone bridge.",
                fontSize = 11.sp,
                color = AngiTextSecondary
            )
        }
    }
}

@Composable
private fun externalTextFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = AngiTextPrimary,
    unfocusedTextColor = AngiTextPrimary,
    focusedContainerColor = AngiDarkSurfaceVariant,
    unfocusedContainerColor = AngiDarkSurfaceVariant,
    focusedBorderColor = AngiSecondary,
    unfocusedBorderColor = AngiTextTertiary.copy(alpha = 0.3f)
)
