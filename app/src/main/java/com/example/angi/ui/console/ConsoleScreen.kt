package com.example.angi.ui.console

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.AngiDarkBackground
import com.example.ui.theme.AngiDarkSurface
import com.example.ui.theme.AngiDarkSurfaceVariant
import com.example.ui.theme.AngiPrimary
import com.example.ui.theme.AngiTextPrimary
import com.example.ui.theme.AngiTextSecondary
import com.example.ui.theme.AngiTextTertiary

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConsoleScreen(
    viewModel: ConsoleViewModel,
    modifier: Modifier = Modifier
) {
    val state by viewModel.uiState.collectAsState()
    val scrollState = rememberScrollState()

    LaunchedEffect(Unit) {
        viewModel.refresh()
    }

    LaunchedEffect(state.output.length) {
        if (state.output.isNotEmpty()) {
            scrollState.animateScrollTo(scrollState.maxValue)
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(AngiDarkBackground)
            .imePadding()
            .testTag("console_screen")
    ) {
        TopAppBar(
            title = {
                Column {
                    Text(
                        text = "Debian Console",
                        color = AngiTextPrimary,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Direct PRoot shell · no model · no VPS",
                        color = AngiTextTertiary,
                        fontSize = 11.sp
                    )
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = AngiDarkSurface
            )
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(12.dp)
        ) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = AngiDarkSurfaceVariant)
            ) {
                Column(modifier = Modifier.padding(10.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = if (state.isDebianInstalled) "Debian ready" else "Debian not installed",
                            color = if (state.isDebianInstalled) AngiPrimary else Color.Red,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = state.status,
                            color = AngiTextSecondary,
                            fontSize = 12.sp
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "cwd: ${state.cwd}",
                        color = AngiTextTertiary,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            Card(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF090B0D))
            ) {
                SelectionContainer {
                    Text(
                        text = if (state.output.isEmpty()) {
                            if (state.isDebianInstalled) {
                                "ANGI Debian console\n$ "
                            } else {
                                "Install Debian from NPU Diag first."
                            }
                        } else {
                            state.output
                        },
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(12.dp)
                            .verticalScroll(scrollState)
                            .testTag("console_output"),
                        color = Color(0xFFD7E4D7),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        lineHeight = 17.sp
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            OutlinedTextField(
                value = state.input,
                onValueChange = viewModel::setInput,
                enabled = state.isDebianInstalled,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("console_input"),
                singleLine = true,
                label = {
                    Text(if (state.isRunning) "Send input to running process" else "Command")
                },
                placeholder = {
                    Text(
                        if (state.isRunning) {
                            "type response / confirmation and Send"
                        } else {
                            "e.g. codex login --device-auth"
                        }
                    )
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { viewModel.submit() }),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = AngiTextPrimary,
                    unfocusedTextColor = AngiTextPrimary,
                    focusedBorderColor = AngiPrimary,
                    unfocusedBorderColor = AngiTextTertiary,
                    cursorColor = AngiPrimary
                )
            )

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = viewModel::submit,
                    enabled = state.isDebianInstalled && state.input.isNotEmpty(),
                    modifier = Modifier
                        .weight(1f)
                        .testTag("console_submit")
                ) {
                    Text(if (state.isRunning) "Send Input" else "Run")
                }

                Button(
                    onClick = viewModel::stop,
                    enabled = state.isRunning,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF8B2F2F)),
                    modifier = Modifier.testTag("console_stop")
                ) {
                    Text("Stop")
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            OutlinedButton(
                onClick = viewModel::toggleTtyMode,
                enabled = !state.isRunning,
                modifier = Modifier.fillMaxWidth().testTag("console_tty_mode")
            ) {
                Text(if (state.ttyMode) "TTY mode: ON" else "TTY mode: OFF")
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = viewModel::clearOutput,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Clear Output")
                }
                OutlinedButton(
                    onClick = viewModel::resetShell,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Reset Shell")
                }
            }

            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = if (state.ttyMode) {
                    "TTY mode is ON: commands run in a pseudo-terminal so interactive CLIs such as Codex behave like a real terminal. Shell-state commands such as cd/export run directly so state persists. Interactive input is not echoed into ANGI's console log."
                } else {
                    "TTY mode is OFF: commands use direct pipes. Use this only when a program explicitly expects piped stdin/stdout."
                },
                color = AngiTextTertiary,
                fontSize = 10.sp
            )
        }
    }
}
