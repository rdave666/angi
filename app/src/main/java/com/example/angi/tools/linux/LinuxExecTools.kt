package com.example.angi.tools.linux

import com.example.angi.domain.tools.AngiTool
import com.example.angi.domain.tools.ToolCategory
import com.example.angi.domain.tools.ToolDefinition
import com.example.angi.domain.tools.ToolParameter
import com.example.angi.domain.tools.ToolResult
import com.example.angi.runtime.proot.LinuxSandboxManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Real Linux execution tool powered by PRoot and Debian ARM64.
 *
 * Capabilities:
 * - Arbitrary shell command execution inside guest Linux.
 * - Persistent shell state across calls in the current conversation (cwd, environment, aliases).
 * - Fresh one-shot isolation mode when fresh=true.
 * - Background detached execution when background=true.
 * - Zero confirmation dialogs for normal Linux execution.
 */
class LinuxExecTool(
    private val sandboxManager: LinuxSandboxManager,
    private val conversationIdProvider: () -> String = { "default_conversation" }
) : AngiTool {

    override val definition: ToolDefinition = ToolDefinition(
        name = "linux_exec",
        description = "Execute arbitrary Linux shell commands (bash, apt, git, curl, python, node, etc.) in Debian ARM64 via PRoot. State (current directory, env vars) persists across calls within the same conversation unless fresh=true. For long-running tasks, set background=true.",
        parameters = mapOf(
            "command" to ToolParameter("string", "The shell command line to execute (e.g. 'uname -a', 'apt install -y jq', 'python3 script.py')"),
            "workingDir" to ToolParameter("string", "Optional working directory to run command in (defaults to /root or last persistent directory)"),
            "env" to ToolParameter("object", "Optional map of environment variables to export for this command"),
            "timeoutSeconds" to ToolParameter("integer", "Timeout in seconds (defaults to 30, max 300)"),
            "fresh" to ToolParameter("boolean", "If true, run in an isolated fresh process rather than the persistent conversation shell"),
            "background" to ToolParameter("boolean", "If true, start detached background process and return immediately with pid")
        ),
        requiredParameters = listOf("command"),
        requiresUserConfirmation = false,
        category = ToolCategory.SYSTEM
    )

    override suspend fun execute(arguments: Map<String, Any?>): ToolResult = withContext(Dispatchers.IO) {
        val command = arguments["command"]?.toString() ?: return@withContext ToolResult(
            callId = "linux_exec",
            toolName = definition.name,
            isSuccess = false,
            output = "Missing required argument 'command'",
            error = "INVALID_ARGUMENTS"
        )

        if (!sandboxManager.isInstalled()) {
            return@withContext ToolResult(
                callId = "linux_exec",
                toolName = definition.name,
                isSuccess = false,
                output = "Linux userspace (Debian ARM64) is not installed yet. Please install it via Diagnostics / Linux Environment.",
                error = "ROOTFS_NOT_INSTALLED"
            )
        }

        val timeoutSeconds = (arguments["timeoutSeconds"] as? Number)?.toLong()?.coerceIn(1L, 300L) ?: 30L
        val workingDir = arguments["workingDir"]?.toString()
        val fresh = arguments["fresh"] as? Boolean ?: false
        val background = arguments["background"] as? Boolean ?: false

        @Suppress("UNCHECKED_CAST")
        val envMap = (arguments["env"] as? Map<String, Any?>)
            ?.mapNotNull { (k, v) -> if (v != null) k to v.toString() else null }
            ?.toMap() ?: emptyMap()

        if (background) {
            val bgResult = sandboxManager.processManager.startBackground(
                command = command,
                workingDir = workingDir ?: "/root",
                env = envMap
            )
            return@withContext ToolResult(
                callId = "linux_exec",
                toolName = definition.name,
                isSuccess = true,
                output = "Process started in background: pid=${bgResult["pid"]}. Check status with linux_process_status."
            )
        }

        if (fresh) {
            val launcher = sandboxManager.defaultLauncher
            val result = launcher.execute(
                command = command,
                timeoutSeconds = timeoutSeconds,
                workingDir = workingDir ?: "/root",
                extraEnv = envMap
            )
            val output = buildString {
                if (result.stdout.isNotEmpty()) append(result.stdout)
                if (result.stderr.isNotEmpty()) {
                    if (isNotEmpty()) append('\n')
                    append("[stderr]\n").append(result.stderr)
                }
            }
            return@withContext ToolResult(
                callId = "linux_exec",
                toolName = definition.name,
                isSuccess = result.success,
                output = output.ifEmpty { "(Process finished with exit code ${result.exitCode})" },
                error = if (!result.success) result.error ?: "Process exited with code ${result.exitCode}" else null
            )
        }

        // Persistent shell path
        val convId = conversationIdProvider()
        val shell = sandboxManager.shellFor(convId)

        // Build command wrapper if workingDir or env specified
        val prefix = buildString {
            if (workingDir != null) {
                append("cd ").append(shellSingleQuote(workingDir)).append(" && ")
            }
            envMap.forEach { (k, v) ->
                append(shellSingleQuote(k)).append('=').append(shellSingleQuote(v)).append(' ')
            }
        }
        val fullCommand = if (prefix.isEmpty()) command else "$prefix$command"

        val runResult = shell.run(fullCommand, timeoutSeconds)
        val success = runResult["success"] as? Boolean ?: false
        val stdout = runResult["stdout"]?.toString().orEmpty()
        val stderr = runResult["stderr"]?.toString().orEmpty()
        val exitCode = runResult["exit_code"] as? Int ?: 0
        val cwd = runResult["cwd"]?.toString() ?: "/root"

        val formattedOutput = buildString {
            if (stdout.isNotEmpty()) append(stdout)
            if (stderr.isNotEmpty()) {
                if (isNotEmpty()) append('\n')
                append("[stderr]\n").append(stderr)
            }
            if (isEmpty()) {
                append("(Command finished with exit code $exitCode in $cwd)")
            }
        }

        ToolResult(
            callId = "linux_exec",
            toolName = definition.name,
            isSuccess = success,
            output = formattedOutput,
            error = if (!success) "Exit code $exitCode" else null
        )
    }

    private fun shellSingleQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"
}

class LinuxProcessStatusTool(
    private val sandboxManager: LinuxSandboxManager
) : AngiTool {
    override val definition: ToolDefinition = ToolDefinition(
        name = "linux_process_status",
        description = "Inspect the current status of background processes started with linux_exec(background=true).",
        parameters = mapOf(
            "pid" to ToolParameter("string", "Process ID returned from linux_exec(background=true). If omitted, lists all active processes.")
        ),
        requiredParameters = emptyList(),
        requiresUserConfirmation = false,
        category = ToolCategory.SYSTEM
    )

    override suspend fun execute(arguments: Map<String, Any?>): ToolResult = withContext(Dispatchers.IO) {
        val pid = arguments["pid"]?.toString()
        if (pid != null) {
            val status = sandboxManager.processManager.getStatus(pid)
            if (status == null) {
                return@withContext ToolResult(
                    callId = "linux_proc_status",
                    toolName = definition.name,
                    isSuccess = false,
                    output = "No process found with pid '$pid'",
                    error = "PID_NOT_FOUND"
                )
            }
            val desc = "PID: ${status.pid}\nCommand: ${status.command}\nRunning: ${status.isRunning}\nExit Code: ${status.exitCode ?: "running"}\nRecent Output:\n${status.outputSnapshot.takeLast(2000)}"
            return@withContext ToolResult(
                callId = "linux_proc_status",
                toolName = definition.name,
                isSuccess = true,
                output = desc
            )
        }

        val all = sandboxManager.processManager.listProcesses()
        if (all.isEmpty()) {
            return@withContext ToolResult(
                callId = "linux_proc_status",
                toolName = definition.name,
                isSuccess = true,
                output = "No background processes currently tracked."
            )
        }

        val listStr = all.joinToString("\n---\n") { p ->
            "PID: ${p.pid} | Running: ${p.isRunning} | Exit: ${p.exitCode ?: "running"} | Command: ${p.command}"
        }
        ToolResult(
            callId = "linux_proc_status",
            toolName = definition.name,
            isSuccess = true,
            output = "Active background processes:\n$listStr"
        )
    }
}

class LinuxProcessOutputTool(
    private val sandboxManager: LinuxSandboxManager
) : AngiTool {
    override val definition: ToolDefinition = ToolDefinition(
        name = "linux_process_output",
        description = "Read full or tail output from a running or completed background process.",
        parameters = mapOf(
            "pid" to ToolParameter("string", "Process ID returned from linux_exec"),
            "lines" to ToolParameter("integer", "Number of trailing lines to read (defaults to 100)")
        ),
        requiredParameters = listOf("pid"),
        requiresUserConfirmation = false,
        category = ToolCategory.SYSTEM
    )

    override suspend fun execute(arguments: Map<String, Any?>): ToolResult = withContext(Dispatchers.IO) {
        val pid = arguments["pid"]?.toString() ?: return@withContext ToolResult(
            callId = "linux_proc_output",
            toolName = definition.name,
            isSuccess = false,
            output = "Missing required argument 'pid'",
            error = "INVALID_ARGUMENTS"
        )
        val lines = (arguments["lines"] as? Number)?.toInt() ?: 100
        val out = sandboxManager.processManager.getOutput(pid, lines)
        if (out == null) {
            return@withContext ToolResult(
                callId = "linux_proc_output",
                toolName = definition.name,
                isSuccess = false,
                output = "No process found with pid '$pid'",
                error = "PID_NOT_FOUND"
            )
        }
        ToolResult(
            callId = "linux_proc_output",
            toolName = definition.name,
            isSuccess = true,
            output = out.ifEmpty { "(No output recorded yet)" }
        )
    }
}

class LinuxProcessKillTool(
    private val sandboxManager: LinuxSandboxManager
) : AngiTool {
    override val definition: ToolDefinition = ToolDefinition(
        name = "linux_process_kill",
        description = "Terminate or cancel a running background process by pid.",
        parameters = mapOf(
            "pid" to ToolParameter("string", "Process ID to terminate")
        ),
        requiredParameters = listOf("pid"),
        requiresUserConfirmation = false,
        category = ToolCategory.SYSTEM
    )

    override suspend fun execute(arguments: Map<String, Any?>): ToolResult = withContext(Dispatchers.IO) {
        val pid = arguments["pid"]?.toString() ?: return@withContext ToolResult(
            callId = "linux_proc_kill",
            toolName = definition.name,
            isSuccess = false,
            output = "Missing required argument 'pid'",
            error = "INVALID_ARGUMENTS"
        )
        val killed = sandboxManager.processManager.kill(pid)
        ToolResult(
            callId = "linux_proc_kill",
            toolName = definition.name,
            isSuccess = killed,
            output = if (killed) "Process $pid successfully terminated" else "Process $pid not found or already terminated"
        )
    }
}
