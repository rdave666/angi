package com.example.angi.ui.models

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.angi.domain.models.ModelDescriptor
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
fun ModelManagerScreen(
    viewModel: ModelViewModel,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsState()

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let { viewModel.importModel(it) }
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
                        text = "Model Management",
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp,
                        color = AngiTextPrimary
                    )
                },
                actions = {
                    Button(
                        onClick = { filePickerLauncher.launch(arrayOf("*/*")) },
                        colors = ButtonDefaults.buttonColors(containerColor = AngiPrimary),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier
                            .padding(end = 8.dp)
                            .testTag("import_model_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = "Import Model",
                            tint = AngiDarkBackground,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Import", fontSize = 13.sp, color = AngiDarkBackground, fontWeight = FontWeight.Bold)
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
            // Status banner
            if (uiState.statusMessage != null || uiState.isLoading) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = AngiDarkSurfaceVariant),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (uiState.isLoading) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    strokeWidth = 2.dp,
                                    color = AngiPrimary
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                            }
                            Text(
                                text = uiState.statusMessage ?: "Processing...",
                                fontSize = 13.sp,
                                color = AngiPrimary
                            )
                        }
                    }
                }
            }

            // Overview card
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
                                imageVector = Icons.Default.Memory,
                                contentDescription = null,
                                tint = AngiSecondary,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Qualcomm Snapdragon Runtime Paths",
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp,
                                color = AngiTextPrimary
                            )
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "ANGI supports both Qualcomm AI Engine Direct (QAIRT NPU) bundles and GGUF llama.cpp models. Models persist in application-controlled storage and survive app restarts.",
                            fontSize = 12.sp,
                            color = AngiTextSecondary,
                            lineHeight = 18.sp
                        )
                    }
                }
            }

            // Model item cards
            items(uiState.models, key = { it.id }) { model ->
                val isActive = uiState.activeModel?.id == model.id
                ModelCard(
                    model = model,
                    isActive = isActive,
                    onSelect = { viewModel.selectActiveModel(model) },
                    onDelete = { viewModel.deleteModel(model.id) }
                )
            }
        }
    }
}

@Composable
fun ModelCard(
    model: ModelDescriptor,
    isActive: Boolean,
    onSelect: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isActive) AngiDarkSurfaceElevated else AngiDarkSurface
        ),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            if (isActive) AngiPrimary else AngiDarkSurfaceVariant
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = model.name,
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp,
                            color = AngiTextPrimary
                        )
                        if (isActive) {
                            Spacer(modifier = Modifier.width(8.dp))
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
                                        contentDescription = "Active",
                                        tint = AngiSuccess,
                                        modifier = Modifier.size(10.dp)
                                    )
                                    Spacer(modifier = Modifier.width(3.dp))
                                    Text(
                                        text = "ACTIVE",
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = AngiSuccess
                                    )
                                }
                            }
                        }
                    }
                    Text(
                        text = "Family: ${model.family} • Params: ${model.parameterCount} • Ctx: ${model.contextLength}",
                        fontSize = 12.sp,
                        color = AngiTextSecondary
                    )
                }

                if (!model.isBundled) {
                    IconButton(
                        onClick = onDelete,
                        modifier = Modifier
                            .size(32.dp)
                            .testTag("delete_model_${model.id}")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = "Delete Model",
                            tint = AngiTextTertiary,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Tech specification badges
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ModelTag(label = model.format.name, color = AngiPrimary)
                ModelTag(label = model.runtime.name, color = AngiSecondary)
                ModelTag(label = "Compute: ${model.preferredCompute.name}", color = AngiTertiary)
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = model.description,
                fontSize = 12.sp,
                color = AngiTextSecondary,
                lineHeight = 16.sp
            )

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                val sizeMb = model.fileSizeBytes / (1024 * 1024)
                Text(
                    text = if (sizeMb > 0) "$sizeMb MB on disk" else "Optimized weights",
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    color = AngiTextTertiary
                )

                if (!isActive) {
                    OutlinedButton(
                        onClick = onSelect,
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = AngiPrimary),
                        border = androidx.compose.foundation.BorderStroke(1.dp, AngiPrimary),
                        modifier = Modifier.testTag("load_model_${model.id}")
                    ) {
                        Icon(
                            imageVector = Icons.Default.PlayArrow,
                            contentDescription = "Load Model",
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Load Model", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
fun ModelTag(label: String, color: androidx.compose.ui.graphics.Color) {
    Surface(
        shape = RoundedCornerShape(4.dp),
        color = color.copy(alpha = 0.15f),
        border = androidx.compose.foundation.BorderStroke(1.dp, color.copy(alpha = 0.3f))
    ) {
        Text(
            text = label,
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
            color = color,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
        )
    }
}
