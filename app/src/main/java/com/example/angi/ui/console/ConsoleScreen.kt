package com.example.angi.ui.console

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * ANGI Terminal First — Frosted Slate.
 *
 * Keep these tokens local to the Console. The other ANGI tabs continue using
 * the existing application theme. Layered translucent surfaces intentionally
 * avoid expensive real-time backdrop blur over streaming terminal output.
 */
private object FrostedSlate {
    val background = Color(0xFF101C29)
    val backgroundDeep = Color(0xFF0C1622)
    val surface = Color(0xFF1B2B3B)
    val surfaceMuted = Color(0xFF26394A)
    val foreground = Color(0xFFE5EFF2)
    val foregroundMuted = Color(0xFFA5B8C3)
    val primary = Color(0xFF97D2DC)
    val onPrimary = Color(0xFF11232D)
    val secondary = Color(0xFF314758)
    val accent = Color(0xFF9ACDB3)
    val border = Color(0xFF435A6A)
    val error = Color(0xFFE3A2A4)
    val terminal = Color(0xFF0C1925)
}

@Composable
fun ConsoleScreen(viewModel: ConsoleViewModel, modifier: Modifier = Modifier) {
    val state by viewModel.uiState.collectAsState()
    val keyboard = LocalSoftwareKeyboardController.current
    val scroll = rememberScrollState()
    var menuExpanded by remember { mutableStateOf(false) }
    var showHelp by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { viewModel.refresh() }
    LaunchedEffect(state.output.length) {
        if (state.output.isNotEmpty()) scroll.scrollTo(scroll.maxValue)
    }

    fun submit() {
        if (state.input.isNotEmpty()) {
            viewModel.submit()
            keyboard?.hide()
        }
    }

    MaterialTheme(
        colorScheme = MaterialTheme.colorScheme.copy(
            primary = FrostedSlate.primary,
            onPrimary = FrostedSlate.onPrimary,
            surface = FrostedSlate.surface,
            surfaceVariant = FrostedSlate.surfaceMuted,
            onSurface = FrostedSlate.foreground,
            onSurfaceVariant = FrostedSlate.foregroundMuted,
            outline = FrostedSlate.border,
            background = FrostedSlate.background,
            onBackground = FrostedSlate.foreground
        )
    ) {
        Column(
            modifier = modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(FrostedSlate.background, FrostedSlate.backgroundDeep)
                    )
                )
                .imePadding()
                .testTag("console_screen")
        ) {
            // Small frosted toolbar: status remains available without stealing
            // vertical space from the output.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .background(
                        Brush.horizontalGradient(
                            listOf(
                                FrostedSlate.surface.copy(alpha = 0.90f),
                                FrostedSlate.surfaceMuted.copy(alpha = 0.52f),
                                FrostedSlate.surface.copy(alpha = 0.74f)
                            )
                        )
                    )
                    .padding(start = 12.dp, end = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(9.dp)
            ) {
                Text(
                    "Console",
                    color = FrostedSlate.foreground,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 16.sp
                )

                Text(
                    if (state.isDebianInstalled) "●" else "○",
                    color = if (state.isDebianInstalled) FrostedSlate.accent else FrostedSlate.error,
                    fontSize = 11.sp
                )

                Text(
                    state.cwd,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    color = FrostedSlate.foregroundMuted,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace
                )

                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(FrostedSlate.secondary.copy(alpha = 0.55f))
                        .border(
                            1.dp,
                            FrostedSlate.border.copy(alpha = 0.50f),
                            RoundedCornerShape(12.dp)
                        )
                        .clickable(
                            enabled = !state.isRunning,
                            role = Role.Switch
                        ) { viewModel.toggleTtyMode() }
                        .semantics {
                            stateDescription =
                                if (state.ttyMode) "TTY enabled" else "TTY disabled"
                        }
                        .testTag("console_tty_mode")
                        .padding(horizontal = 9.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    Text(
                        "●",
                        color = if (state.ttyMode) FrostedSlate.accent else FrostedSlate.error,
                        fontSize = 10.sp
                    )
                    Text("TTY", color = FrostedSlate.foreground, fontSize = 11.sp)
                }

                Box {
                    IconButton(
                        onClick = { menuExpanded = true },
                        modifier = Modifier.size(38.dp).testTag("console_menu")
                    ) {
                        Icon(
                            Icons.Default.MoreVert,
                            contentDescription = "Console options",
                            tint = FrostedSlate.foregroundMuted
                        )
                    }
                    DropdownMenu(
                        expanded = menuExpanded,
                        onDismissRequest = { menuExpanded = false },
                        modifier = Modifier.background(FrostedSlate.surface)
                    ) {
                        DropdownMenuItem(
                            text = { Text("Clear output") },
                            onClick = { menuExpanded = false; viewModel.clearOutput() }
                        )
                        DropdownMenuItem(
                            text = { Text("Reset shell") },
                            onClick = { menuExpanded = false; viewModel.resetShell() }
                        )
                        DropdownMenuItem(
                            text = { Text("Help") },
                            onClick = { menuExpanded = false; showHelp = true }
                        )
                    }
                }
            }

            HorizontalDivider(
                color = FrostedSlate.border.copy(alpha = 0.58f),
                thickness = 1.dp
            )

            // Most of the screen still belongs to selectable, scrollable
            // terminal output. Keep its surface dark enough for text contrast.
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(start = 6.dp, end = 6.dp, top = 6.dp, bottom = 3.dp)
                    .shadow(4.dp, RoundedCornerShape(14.dp))
                    .clip(RoundedCornerShape(14.dp))
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                FrostedSlate.surface.copy(alpha = 0.92f),
                                FrostedSlate.terminal.copy(alpha = 0.98f)
                            )
                        )
                    )
                    .border(
                        1.dp,
                        FrostedSlate.border.copy(alpha = 0.48f),
                        RoundedCornerShape(14.dp)
                    )
                    .testTag("console_output")
            ) {
                SelectionContainer {
                    Text(
                        text = state.output.ifEmpty {
                            if (state.isDebianInstalled) "Ready. Type a command below." else
                                "Install Debian in NPU Diag to use the console."
                        },
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(scroll)
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        color = FrostedSlate.foreground,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        lineHeight = 17.sp
                    )
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 6.dp, vertical = 5.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(
                        Brush.horizontalGradient(
                            listOf(
                                FrostedSlate.surface.copy(alpha = 0.94f),
                                FrostedSlate.surfaceMuted.copy(alpha = 0.75f)
                            )
                        )
                    )
                    .border(
                        1.dp,
                        FrostedSlate.border.copy(alpha = 0.52f),
                        RoundedCornerShape(16.dp)
                    )
                    .padding(horizontal = 6.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                OutlinedTextField(
                    value = state.input,
                    onValueChange = viewModel::setInput,
                    modifier = Modifier.weight(1f).testTag("console_input"),
                    enabled = state.isDebianInstalled,
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    textStyle = TextStyle(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 14.sp,
                        color = FrostedSlate.foreground
                    ),
                    placeholder = {
                        Text(
                            if (state.isRunning) "Send input…" else "Command…",
                            color = FrostedSlate.foregroundMuted,
                            fontSize = 13.sp
                        )
                    },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = FrostedSlate.primary.copy(alpha = 0.82f),
                        unfocusedBorderColor = FrostedSlate.border.copy(alpha = 0.7f),
                        focusedContainerColor = FrostedSlate.background.copy(alpha = 0.46f),
                        unfocusedContainerColor = FrostedSlate.background.copy(alpha = 0.36f),
                        disabledContainerColor = FrostedSlate.background.copy(alpha = 0.20f),
                        cursorColor = FrostedSlate.primary,
                        focusedTextColor = FrostedSlate.foreground,
                        unfocusedTextColor = FrostedSlate.foreground
                    ),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { submit() })
                )

                if (state.isRunning) {
                    IconButton(
                        onClick = viewModel::stop,
                        modifier = Modifier
                            .size(44.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(FrostedSlate.error.copy(alpha = 0.12f))
                            .border(
                                1.dp,
                                FrostedSlate.error.copy(alpha = 0.34f),
                                RoundedCornerShape(12.dp)
                            )
                            .testTag("console_stop")
                    ) {
                        Icon(
                            Icons.Default.Stop,
                            contentDescription = "Stop command",
                            tint = FrostedSlate.error
                        )
                    }
                } else {
                    val canRun = state.isDebianInstalled && state.input.isNotEmpty()
                    IconButton(
                        onClick = { submit() },
                        enabled = canRun,
                        modifier = Modifier
                            .size(44.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(
                                if (canRun) FrostedSlate.primary else
                                    FrostedSlate.secondary.copy(alpha = 0.65f)
                            )
                            .testTag("console_submit")
                    ) {
                        Icon(
                            Icons.Default.ArrowUpward,
                            contentDescription = "Run command",
                            tint = if (canRun) FrostedSlate.onPrimary else
                                FrostedSlate.foregroundMuted
                        )
                    }
                }
            }
        }

        if (showHelp) {
            AlertDialog(
                onDismissRequest = { showHelp = false },
                title = { Text("Console tips") },
                text = {
                    Text(
                        "Enter runs a command and closes the keyboard. " +
                            "Tap TTY to switch interactive terminal mode. " +
                            "Clear output and Reset shell are in the menu."
                    )
                },
                confirmButton = {
                    TextButton(onClick = { showHelp = false }) { Text("Got it") }
                }
            )
        }
    }
}
