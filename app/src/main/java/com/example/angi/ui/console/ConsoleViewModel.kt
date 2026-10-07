package com.example.angi.ui.console

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.AngiApp
import com.example.angi.runtime.proot.LinuxSandboxManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

data class ConsoleUiState(
    val isDebianInstalled: Boolean = false,
    val input: String = "",
    val output: String = "",
    val isRunning: Boolean = false,
    val cwd: String = "/workspace",
    val lastExitCode: Int? = null,
    val status: String = "Ready",
    val ttyMode: Boolean = true
)

class ConsoleViewModel(
    private val sandboxManager: LinuxSandboxManager = AngiApp.instance.linuxSandboxManager
) : ViewModel() {

    companion object {
        private const val SESSION_ID = "__angi_in_app_console__"
        private const val MAX_OUTPUT_CHARS = 120_000
        private const val COMMAND_TIMEOUT_SECONDS = 86_400L

        private val ANSI_CSI = Regex("\\u001B\\[[0-?]*[ -/]*[@-~]")
        private val ANSI_OSC = Regex("\\u001B\\][^\\u0007]*(?:\\u0007|\\u001B\\\\)")
    }

    private val _uiState = kotlinx.coroutines.flow.MutableStateFlow(
        ConsoleUiState(isDebianInstalled = sandboxManager.isInstalled())
    )
    val uiState: kotlinx.coroutines.flow.StateFlow<ConsoleUiState> = _uiState

    private val shell
        get() = sandboxManager.shellFor(SESSION_ID)

    private var commandJob: Job? = null
    private var initializedWorkspace = false

    fun refresh() {
        _uiState.value = _uiState.value.copy(
            isDebianInstalled = sandboxManager.isInstalled()
        )
    }

    fun setInput(value: String) {
        _uiState.value = _uiState.value.copy(input = value)
    }

    fun toggleTtyMode() {
        if (_uiState.value.isRunning) return
        _uiState.value = _uiState.value.copy(ttyMode = !_uiState.value.ttyMode)
    }

    fun submit() {
        val raw = _uiState.value.input
        if (raw.isEmpty()) return

        if (_uiState.value.isRunning) {
            shell.writeInput(raw)
            _uiState.value = _uiState.value.copy(
                input = "",
                status = "Input sent"
            )
            return
        }

        if (!sandboxManager.isInstalled()) {
            _uiState.value = _uiState.value.copy(
                isDebianInstalled = false,
                status = "Debian is not installed. Install it from NPU Diag first."
            )
            return
        }

        val command = raw
        _uiState.value = _uiState.value.copy(
            input = "",
            isRunning = true,
            lastExitCode = null,
            status = "Running"
        )
        appendOutput("$ ${command}\n")

        commandJob = viewModelScope.launch(Dispatchers.IO) {
            if (!initializedWorkspace) {
                val init = shell.run("cd /workspace", timeoutSeconds = 10L)
                val initSuccess = init["success"] as? Boolean ?: false
                if (initSuccess) {
                    initializedWorkspace = true
                    _uiState.value = _uiState.value.copy(cwd = "/workspace")
                } else {
                    appendOutput("[console] Could not enter /workspace; continuing in shell cwd.\n")
                }
            }

            val commandToRun = if (_uiState.value.ttyMode && shouldUseTty(command)) {
                wrapWithPseudoTerminal(command)
            } else {
                command
            }

            val result = shell.run(
                command = commandToRun,
                timeoutSeconds = COMMAND_TIMEOUT_SECONDS,
                onOutput = { text, isStderr ->
                    if (text.isNotEmpty()) {
                        appendOutput(
                            if (isStderr) "[stderr] ${text}\n" else "${text}\n"
                        )
                    }
                }
            )

            val exitCode = (result["exit_code"] as? Number)?.toInt()
            val cwd = result["cwd"]?.toString()?.ifBlank { _uiState.value.cwd }
                ?: _uiState.value.cwd
            val shellDied = result["shell_died"] as? Boolean ?: false
            val timedOut = result["timed_out"] as? Boolean ?: false

            val finalStatus = when {
                timedOut -> "Timed out"
                shellDied -> "Shell ended"
                exitCode == 0 -> "Completed"
                else -> "Exited ${exitCode ?: "?"}"
            }

            appendOutput("[exit ${exitCode ?: "?"}]\n")
            _uiState.value = _uiState.value.copy(
                isRunning = false,
                cwd = cwd,
                lastExitCode = exitCode,
                status = finalStatus,
                isDebianInstalled = sandboxManager.isInstalled()
            )
            commandJob = null
        }
    }

    private fun shouldUseTty(command: String): Boolean {
        val trimmed = command.trimStart()
        val statefulBuiltin = Regex("^(cd|export|unset|alias|unalias|umask|source|\\.)(?:\\s|$)")
        return !statefulBuiltin.containsMatchIn(trimmed)
    }

    private fun wrapWithPseudoTerminal(command: String): String {
        val quoted = shellSingleQuote(command)
        return "if command -v script >/dev/null 2>&1; then " +
            "script -qefc $quoted /dev/null; " +
            "else printf '%s\\n' '[console] TTY mode requires util-linux. Run: apt update && apt install -y util-linux' >&2; exit 127; fi"
    }

    private fun shellSingleQuote(value: String): String =
        "'" + value.replace("'", "'\"'\"'") + "'"

    fun stop() {
        if (!_uiState.value.isRunning) return
        _uiState.value = _uiState.value.copy(status = "Stopping…")
        shell.cancelForeground()
    }

    fun clearOutput() {
        _uiState.value = _uiState.value.copy(output = "")
    }

    fun resetShell() {
        commandJob?.cancel()
        commandJob = null
        sandboxManager.resetShell(SESSION_ID)
        initializedWorkspace = false
        _uiState.value = _uiState.value.copy(
            output = "",
            isRunning = false,
            cwd = "/workspace",
            lastExitCode = null,
            status = "Shell reset",
            isDebianInstalled = sandboxManager.isInstalled()
        )
    }

    @Synchronized
    private fun appendOutput(raw: String) {
        val clean = raw
            .replace(ANSI_OSC, "")
            .replace(ANSI_CSI, "")
            .replace("\r", "")

        if (clean.isEmpty()) return

        val current = _uiState.value
        var next = current.output + clean
        if (next.length > MAX_OUTPUT_CHARS) {
            next = "[older output truncated]\n" + next.takeLast(MAX_OUTPUT_CHARS)
        }
        _uiState.value = current.copy(output = next)
    }

    override fun onCleared() {
        commandJob?.cancel()
        if (_uiState.value.isRunning) {
            shell.cancelForeground()
        }
        super.onCleared()
    }
}
