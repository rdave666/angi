package com.example.angi.ui.console

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.AngiDarkBackground
import com.example.ui.theme.AngiDarkSurface
import com.example.ui.theme.AngiPrimary
import com.example.ui.theme.AngiTextPrimary
import com.example.ui.theme.AngiTextSecondary
import com.example.ui.theme.AngiTextTertiary

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

    Column(
        modifier = modifier.fillMaxSize().background(AngiDarkBackground)
            .imePadding().testTag("console_screen")
    ) {
        // All static status and terminal actions fit in a single compact toolbar.
        Row(
            modifier = Modifier.fillMaxWidth().height(48.dp).padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(9.dp)
        ) {
            Text("Console", color = AngiTextPrimary, fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
            Text(
                if (state.isDebianInstalled) "●" else "○",
                color = if (state.isDebianInstalled) Color(0xFF36C781) else Color(0xFFEE7474),
                fontSize = 12.sp
            )
            Text(
                state.cwd,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                color = AngiTextTertiary,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace
            )
            Text(
                if (state.ttyMode) "● TTY" else "○ TTY",
                modifier = Modifier.clickable(enabled = !state.isRunning) { viewModel.toggleTtyMode() }
                    .testTag("console_tty_mode").padding(vertical = 8.dp),
                color = if (state.ttyMode) Color(0xFF36C781) else Color(0xFFEE7474),
                fontSize = 12.sp
            )
            Box {
                IconButton(
                    onClick = { menuExpanded = true },
                    modifier = Modifier.size(38.dp).testTag("console_menu")
                ) { Icon(Icons.Default.MoreVert, contentDescription = "Console options", tint = AngiTextSecondary) }
                DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
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

        HorizontalDivider(color = Color(0xFF26354A), thickness = 1.dp)

        // The terminal owns the remaining screen, even when IME is displayed.
        SelectionContainer {
            Box(
                modifier = Modifier.weight(1f).fillMaxWidth()
                    .background(Color(0xFF060A10)).testTag("console_output")
            ) {
                Text(
                    text = state.output.ifEmpty {
                        if (state.isDebianInstalled) "Ready. Type a command below." else
                            "Install Debian in NPU Diag to use the console."
                    },
                    modifier = Modifier.fillMaxSize().verticalScroll(scroll)
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    color = Color(0xFFD6E7DD),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    lineHeight = 17.sp
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().background(AngiDarkSurface)
                .padding(horizontal = 9.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            OutlinedTextField(
                value = state.input,
                onValueChange = viewModel::setInput,
                modifier = Modifier.weight(1f).testTag("console_input"),
                enabled = state.isDebianInstalled,
                singleLine = true,
                textStyle = androidx.compose.ui.text.TextStyle(
                    fontFamily = FontFamily.Monospace, fontSize = 14.sp, color = AngiTextPrimary
                ),
                placeholder = {
                    Text(
                        if (state.isRunning) "Send input…" else "Command…",
                        fontSize = 13.sp, color = AngiTextTertiary
                    )
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { submit() })
            )
            if (state.isRunning) {
                IconButton(onClick = viewModel::stop, modifier = Modifier
                    .size(46.dp).testTag("console_stop")) {
                    Icon(Icons.Default.Stop, contentDescription = "Stop command", tint = Color(0xFFEE7474))
                }
            } else {
                IconButton(
                    onClick = { submit() },
                    enabled = state.isDebianInstalled && state.input.isNotEmpty(),
                    modifier = Modifier.size(46.dp).testTag("console_submit")
                ) {
                    Icon(Icons.Default.ArrowUpward, contentDescription = "Run command",
                        tint = if (state.input.isNotEmpty()) AngiPrimary else AngiTextTertiary)
                }
            }
        }
    }

    if (showHelp) {
        AlertDialog(
            onDismissRequest = { showHelp = false },
            title = { Text("Console tips") },
            text = { Text("Enter runs a command and closes the keyboard. Tap the TTY indicator to switch interactive terminal mode. The menu contains Clear output and Reset shell. Output stays scrollable while commands run.") },
            confirmButton = { TextButton(onClick = { showHelp = false }) { Text("Got it") } }
        )
    }
}
