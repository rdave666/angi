package com.example.angi.runtime.proot

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

data class ProcessStatus(
    val pid: String,
    val command: String,
    val isRunning: Boolean,
    val exitCode: Int?,
    val outputSnapshot: String
)

class LinuxProcessManager(
    private val launcher: ProotLauncher
) {
    private class ManagedProcess(
        val pid: String,
        val command: String,
        val handle: ProotHandle,
        val outputBuffer: StringBuilder = StringBuilder(),
        val isRunning: AtomicBoolean = AtomicBoolean(true),
        @Volatile var exitCode: Int? = null
    )

    private val processes = ConcurrentHashMap<String, ManagedProcess>()

    fun startBackground(
        command: String,
        workingDir: String = "/root",
        env: Map<String, String> = emptyMap()
    ): Map<String, Any> {
        val pid = "proc_" + System.currentTimeMillis().toString(36) + "_" + (1000..9999).random()
        val buffer = StringBuilder()
        val isRunning = AtomicBoolean(true)

        val handle = launcher.startStreaming(
            command = command,
            workingDir = workingDir,
            extraEnv = env
        ) { proc, cancelled ->
            val stdoutFut = java.util.concurrent.CompletableFuture.runAsync {
                val reader = proc.inputStream.bufferedReader()
                while (!cancelled.get()) {
                    val line = reader.readLine() ?: break
                    synchronized(buffer) {
                        if (buffer.length < 50_000) {
                            buffer.append(line).append('\n')
                        }
                    }
                }
            }
            val stderrFut = java.util.concurrent.CompletableFuture.runAsync {
                val reader = proc.errorStream.bufferedReader()
                while (!cancelled.get()) {
                    val line = reader.readLine() ?: break
                    synchronized(buffer) {
                        if (buffer.length < 50_000) {
                            buffer.append("[stderr] ").append(line).append('\n')
                        }
                    }
                }
            }
            listOf(stdoutFut, stderrFut)
        }

        val managed = ManagedProcess(pid, command, handle, buffer, isRunning)
        processes[pid] = managed

        Thread {
            val code = handle.awaitExit()
            managed.exitCode = code
            managed.isRunning.set(false)
        }.start()

        return mapOf(
            "success" to true,
            "pid" to pid,
            "command" to command,
            "status" to "running"
        )
    }

    fun getStatus(pid: String): ProcessStatus? {
        val p = processes[pid] ?: return null
        val snapshot = synchronized(p.outputBuffer) { p.outputBuffer.toString() }
        return ProcessStatus(
            pid = p.pid,
            command = p.command,
            isRunning = p.isRunning.get(),
            exitCode = p.exitCode,
            outputSnapshot = snapshot
        )
    }

    fun getOutput(pid: String, tailLines: Int = 100): String? {
        val p = processes[pid] ?: return null
        val full = synchronized(p.outputBuffer) { p.outputBuffer.toString() }
        val lines = full.lines()
        return if (lines.size <= tailLines) {
            full
        } else {
            lines.takeLast(tailLines).joinToString("\n")
        }
    }

    fun kill(pid: String): Boolean {
        val p = processes[pid] ?: return false
        p.handle.cancel()
        p.isRunning.set(false)
        p.exitCode = -9
        return true
    }

    fun listProcesses(): List<ProcessStatus> {
        return processes.values.map { p ->
            val snapshot = synchronized(p.outputBuffer) { p.outputBuffer.toString() }
            ProcessStatus(
                pid = p.pid,
                command = p.command,
                isRunning = p.isRunning.get(),
                exitCode = p.exitCode,
                outputSnapshot = snapshot
            )
        }
    }
}
