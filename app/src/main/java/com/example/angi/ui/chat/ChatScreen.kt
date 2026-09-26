package com.example.angi.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.angi.domain.conversation.GenerationState
import com.example.angi.domain.conversation.Message
import com.example.ui.theme.AngiDarkBackground
import com.example.ui.theme.AngiDarkSurface
import com.example.ui.theme.AngiDarkSurfaceElevated
import com.example.ui.theme.AngiDarkSurfaceVariant
import com.example.ui.theme.AngiOnPrimary
import com.example.ui.theme.AngiPrimary
import com.example.ui.theme.AngiSecondary
import com.example.ui.theme.AngiSuccess
import com.example.ui.theme.AngiTertiary
import com.example.ui.theme.AngiTextPrimary
import com.example.ui.theme.AngiTextSecondary
import com.example.ui.theme.AngiTextTertiary

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    viewModel: ChatViewModel,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsState()
    val listState = rememberLazyListState()

    // Auto-scroll on new messages or during generation
    LaunchedEffect(uiState.messages.size, uiState.generationState) {
        if (uiState.messages.isNotEmpty()) {
            listState.animateScrollToItem(uiState.messages.size - 1)
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
                    val isLoaded = uiState.runtimeInfo?.isModelLoaded == true
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "ANGI",
                                fontWeight = FontWeight.Bold,
                                fontSize = 18.sp,
                                color = AngiPrimary
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            val compute = uiState.runtimeInfo?.computeUnit ?: "NONE"
                            val computeLabel = when {
                                !isLoaded -> "OFFLINE"
                                compute.contains("NPU", ignoreCase = true) -> "HEXAGON NPU"
                                compute.contains("GPU", ignoreCase = true) -> "ADRENO GPU"
                                compute.contains("CPU", ignoreCase = true) -> "CPU"
                                else -> compute.uppercase()
                            }
                            val badgeColor = when {
                                !isLoaded -> AngiTextTertiary
                                compute.contains("NPU", ignoreCase = true) -> AngiSecondary
                                compute.contains("GPU", ignoreCase = true) -> AngiPrimary
                                else -> AngiTextSecondary
                            }
                            Surface(
                                shape = RoundedCornerShape(4.dp),
                                color = badgeColor.copy(alpha = 0.2f),
                                border = androidx.compose.foundation.BorderStroke(1.dp, badgeColor.copy(alpha = 0.4f))
                            ) {
                                Text(
                                    text = computeLabel,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = badgeColor,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                            if (isLoaded) {
                                Spacer(modifier = Modifier.width(8.dp))
                                val isBusy = uiState.generationState !is GenerationState.Idle
                                Surface(
                                    shape = RoundedCornerShape(4.dp),
                                    color = if (isBusy) AngiDarkSurfaceVariant else MaterialTheme.colorScheme.error.copy(alpha = 0.15f),
                                    border = androidx.compose.foundation.BorderStroke(
                                        1.dp,
                                        if (isBusy) AngiTextTertiary.copy(alpha = 0.3f) else MaterialTheme.colorScheme.error.copy(alpha = 0.6f)
                                    ),
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(4.dp))
                                        .clickable(enabled = !isBusy) { viewModel.unloadActiveModel() }
                                        .testTag("unload_model_header_button")
                                ) {
                                    Text(
                                        text = "Unload",
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = if (isBusy) AngiTextTertiary else MaterialTheme.colorScheme.error,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                }
                            }
                        }
                        Text(
                            text = if (isLoaded) (uiState.activeModel?.name ?: "No model loaded") else "No model loaded",
                            fontSize = 12.sp,
                            color = AngiTextSecondary,
                            maxLines = 1
                        )
                    }
                },
                actions = {
                    IconButton(
                        onClick = { viewModel.refreshActiveModel() },
                        modifier = Modifier.testTag("refresh_model_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "Refresh model",
                            tint = AngiTextSecondary
                        )
                    }
                    IconButton(
                        onClick = { viewModel.clearHistory() },
                        modifier = Modifier.testTag("clear_history_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Clear,
                            contentDescription = "Clear conversation history",
                            tint = AngiTextSecondary
                        )
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .imePadding() // Critical: moves entire composer above Android keyboard
        ) {
            // Status bar for generation / preparing states
            AnimatedVisibility(visible = uiState.generationState !is GenerationState.Idle) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = AngiDarkSurfaceVariant,
                    tonalElevation = 2.dp
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color = AngiPrimary
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            val statusLabel = when (val state = uiState.generationState) {
                                is GenerationState.Preparing -> state.status
                                is GenerationState.Generating -> "Streaming (${state.tokensCount} tokens)..."
                                is GenerationState.ExecutingTool -> "Executing tool '${state.toolName}'..."
                                is GenerationState.Stopping -> "Stopping inference..."
                                is GenerationState.Failed -> "Failed: ${state.error}"
                                else -> "Processing"
                            }
                            Text(
                                text = statusLabel,
                                fontSize = 12.sp,
                                color = AngiPrimary
                            )
                        }

                        if (uiState.generationState is GenerationState.Generating) {
                            IconButton(
                                onClick = { viewModel.stopGeneration() },
                                modifier = Modifier
                                    .size(28.dp)
                                    .testTag("stop_generation_badge")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Stop,
                                    contentDescription = "Stop",
                                    tint = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                    }
                }
            }

            // Message List
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                if (uiState.messages.isEmpty()) {
                    item {
                        val isLoaded = uiState.runtimeInfo?.isModelLoaded == true
                        EmptyConversationPlaceholder(
                            activeModelName = if (isLoaded) uiState.activeModel?.name ?: "Snapdragon 8 Gen 2 Model" else null,
                            onChipClick = { prompt ->
                                viewModel.onDraftChanged(prompt)
                                viewModel.sendMessage()
                            }
                        )
                    }
                }

                items(uiState.messages, key = { it.id }) { message ->
                    MessageCard(
                        message = message,
                        onShare = { text -> viewModel.shareMessage(text) }
                    )
                }

                // Temporary streaming item
                if (uiState.generationState is GenerationState.Generating) {
                    val gen = uiState.generationState as GenerationState.Generating
                    item {
                        StreamingAssistantCard(partialText = gen.partialText)
                    }
                }
            }

            val isModelLoaded = uiState.runtimeInfo?.isModelLoaded == true
            // Composer Area
            ChatComposer(
                text = uiState.draftInput,
                onTextChanged = viewModel::onDraftChanged,
                onSend = viewModel::sendMessage,
                onStop = viewModel::stopGeneration,
                isGenerating = uiState.generationState is GenerationState.Generating,
                isModelLoaded = isModelLoaded,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(AngiDarkSurface)
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            )
        }
    }
}

@Composable
fun MessageCard(
    message: Message,
    onShare: (String) -> Unit
) {
    when (message) {
        is Message.User -> {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.85f)
                        .clip(RoundedCornerShape(topStart = 16.dp, topEnd = 4.dp, bottomStart = 16.dp, bottomEnd = 16.dp))
                        .background(AngiDarkSurfaceElevated)
                        .border(1.dp, AngiSecondary.copy(alpha = 0.3f), RoundedCornerShape(topStart = 16.dp, topEnd = 4.dp, bottomStart = 16.dp, bottomEnd = 16.dp))
                        .padding(14.dp)
                ) {
                    Text(
                        text = message.text,
                        fontSize = 15.sp,
                        color = AngiTextPrimary,
                        lineHeight = 22.sp
                    )
                }
            }
        }
        is Message.Assistant -> {
            Column(
                modifier = Modifier
                    .fillMaxWidth(0.92f)
                    .clip(RoundedCornerShape(topStart = 4.dp, topEnd = 16.dp, bottomStart = 16.dp, bottomEnd = 16.dp))
                    .background(AngiDarkSurface)
                    .border(1.dp, AngiPrimary.copy(alpha = 0.25f), RoundedCornerShape(topStart = 4.dp, topEnd = 16.dp, bottomStart = 16.dp, bottomEnd = 16.dp))
                    .padding(14.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(AngiPrimary)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "ANGI ASSISTANT",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = AngiPrimary
                        )
                    }

                    // Share button wired to Android ACTION_SEND
                    IconButton(
                        onClick = { onShare(message.text) },
                        modifier = Modifier
                            .size(28.dp)
                            .testTag("share_message_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Share,
                            contentDescription = "Share message to other apps",
                            tint = AngiTextSecondary,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(6.dp))

                Text(
                    text = message.text,
                    fontSize = 15.sp,
                    color = AngiTextPrimary,
                    lineHeight = 22.sp
                )

                if (message.toolCall != null) {
                    Spacer(modifier = Modifier.height(8.dp))
                    ToolCallBadge(toolName = message.toolCall.name)
                }

                if (message.metrics != null) {
                    Spacer(modifier = Modifier.height(10.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(6.dp))
                            .background(AngiDarkBackground.copy(alpha = 0.6f))
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            text = "⚡ TTFT: ${message.metrics.ttftMs.toInt()}ms",
                            fontSize = 10.sp,
                            color = AngiTextTertiary,
                            fontFamily = FontFamily.Monospace
                        )
                        Text(
                            text = "🚀 ${String.format("%.1f", message.metrics.decodeTokensPerSec)} t/s",
                            fontSize = 10.sp,
                            color = AngiSuccess,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = message.metrics.computeUnit.ifBlank { "Local" },
                            fontSize = 10.sp,
                            color = AngiSecondary,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }
        }
        is Message.Tool -> {
            Card(
                modifier = Modifier
                    .fillMaxWidth(0.92f)
                    .padding(vertical = 2.dp),
                shape = RoundedCornerShape(8.dp),
                colors = CardDefaults.cardColors(
                    containerColor = AngiDarkSurfaceVariant
                ),
                border = androidx.compose.foundation.BorderStroke(1.dp, AngiTertiary.copy(alpha = 0.3f))
            ) {
                Column(modifier = Modifier.padding(10.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Build,
                            contentDescription = "Tool Result",
                            tint = AngiTertiary,
                            modifier = Modifier.size(14.dp)
                        )
                        Text(
                            text = "Tool Executed: ${message.result.toolName}",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = AngiTertiary
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = message.result.output,
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        color = AngiTextSecondary,
                        lineHeight = 16.sp
                    )
                }
            }
        }
    }
}

@Composable
fun StreamingAssistantCard(partialText: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth(0.92f)
            .clip(RoundedCornerShape(topStart = 4.dp, topEnd = 16.dp, bottomStart = 16.dp, bottomEnd = 16.dp))
            .background(AngiDarkSurface)
            .border(1.dp, AngiPrimary.copy(alpha = 0.4f), RoundedCornerShape(topStart = 4.dp, topEnd = 16.dp, bottomStart = 16.dp, bottomEnd = 16.dp))
            .padding(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(AngiPrimary)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = "STREAMING FROM HEXAGON NPU...",
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = AngiPrimary
            )
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = if (partialText.isNotEmpty()) partialText + " ▍" else "Generating tokens... ▍",
            fontSize = 15.sp,
            color = AngiTextPrimary,
            lineHeight = 22.sp
        )
    }
}

@Composable
fun ToolCallBadge(toolName: String) {
    Surface(
        shape = RoundedCornerShape(4.dp),
        color = AngiTertiary.copy(alpha = 0.15f),
        border = androidx.compose.foundation.BorderStroke(1.dp, AngiTertiary.copy(alpha = 0.3f))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.Build,
                contentDescription = null,
                tint = AngiTertiary,
                modifier = Modifier.size(10.dp)
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = "Requested tool: $toolName",
                fontSize = 10.sp,
                color = AngiTertiary,
                fontFamily = FontFamily.Monospace
            )
        }
    }
}

@Composable
fun ChatComposer(
    text: String,
    onTextChanged: (String) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    isGenerating: Boolean,
    isModelLoaded: Boolean = true,
    modifier: Modifier = Modifier
) {
    val canSend = isModelLoaded && !isGenerating && text.isNotBlank()
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically
    ) {
        OutlinedTextField(
            value = text,
            onValueChange = onTextChanged,
            enabled = isModelLoaded,
            placeholder = {
                Text(
                    text = if (isModelLoaded) "Ask ANGI or run a tool..." else "No model loaded. Load a model to chat.",
                    fontSize = 14.sp,
                    color = AngiTextTertiary
                )
            },
            maxLines = 4,
            modifier = Modifier
                .weight(1f)
                .testTag("chat_input_field"),
            shape = RoundedCornerShape(24.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = AngiDarkSurfaceVariant,
                unfocusedContainerColor = AngiDarkSurfaceVariant,
                disabledContainerColor = AngiDarkSurfaceVariant.copy(alpha = 0.5f),
                disabledTextColor = AngiTextTertiary,
                disabledBorderColor = Color.Transparent,
                focusedBorderColor = AngiPrimary,
                unfocusedBorderColor = Color.Transparent,
                focusedTextColor = AngiTextPrimary,
                unfocusedTextColor = AngiTextPrimary
            )
        )

        Spacer(modifier = Modifier.width(8.dp))

        if (isGenerating) {
            IconButton(
                onClick = onStop,
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.error)
                    .testTag("stop_inference_button")
            ) {
                Icon(
                    imageVector = Icons.Default.Stop,
                    contentDescription = "Stop Generation",
                    tint = Color.White
                )
            }
        } else {
            IconButton(
                onClick = onSend,
                enabled = canSend,
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(if (canSend) AngiPrimary else AngiDarkSurfaceElevated)
                    .testTag("send_message_button")
            ) {
                Icon(
                    imageVector = Icons.Default.Send,
                    contentDescription = "Send Message",
                    tint = if (canSend) AngiOnPrimary else AngiTextTertiary
                )
            }
        }
    }
}

@Composable
fun EmptyConversationPlaceholder(
    activeModelName: String?,
    onChipClick: (String) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 32.dp, horizontal = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(64.dp)
                .clip(CircleShape)
                .background(AngiDarkSurface)
                .border(1.dp, AngiPrimary.copy(alpha = 0.3f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.Memory,
                contentDescription = null,
                tint = if (activeModelName != null) AngiPrimary else AngiTextTertiary,
                modifier = Modifier.size(32.dp)
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "ANGI Snapdragon Intelligence",
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            color = AngiTextPrimary
        )

        Spacer(modifier = Modifier.height(4.dp))

        Text(
            text = if (activeModelName != null) "On-Device local inference active: $activeModelName" else "No model loaded. Import or select a model in Model Management.",
            fontSize = 12.sp,
            color = if (activeModelName != null) AngiTextSecondary else AngiTextTertiary
        )

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = "Quick Starter Actions",
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            color = AngiTextTertiary
        )

        Spacer(modifier = Modifier.height(10.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center
        ) {
            FilterChip(
                selected = false,
                onClick = { onChipClick("What are the hardware device specs of this phone?") },
                label = { Text("Device Specs Tool", fontSize = 12.sp) },
                colors = FilterChipDefaults.filterChipColors(
                    labelColor = AngiTextPrimary,
                    containerColor = AngiDarkSurface
                )
            )
            Spacer(modifier = Modifier.width(8.dp))
            FilterChip(
                selected = false,
                onClick = { onChipClick("Share a message about ANGI running on Snapdragon NPU") },
                label = { Text("Share Tool", fontSize = 12.sp) },
                colors = FilterChipDefaults.filterChipColors(
                    labelColor = AngiTextPrimary,
                    containerColor = AngiDarkSurface
                )
            )
        }
    }
}
