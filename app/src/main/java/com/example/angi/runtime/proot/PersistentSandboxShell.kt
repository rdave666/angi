package com.example.angi.runtime.proot

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

private const val MAX_OUTPUT_LENGTH = 30_000
// Sentinel characters: RS (Record Separator 0x1e) and US (Unit Separator 0x1f)
private const val RS = "\u001e"
private const val US = "\u001f"
private const val PID_PROBE_PREFIX = "${RS}ANGIBASHPID$US"

class PersistentSandboxShell(
    private val launcher: ProotLauncher
) {
    private val mutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var handle: ProotHandle? = null
    @Volatile private var bashPid: Int? = null
    private var watchdog: Job? = null
    private val currentSink = AtomicReference<CommandSink?>(null)

    private class CommandSink(
        val nonce: String,
        val stdoutBuf: StringBuilder = StringBuilder(),
        val stderrBuf: StringBuilder = StringBuilder(),
        val onOutput: ((text: String, isStderr: Boolean) -> Unit)? = null,
        val done: CompletableDeferred<Result> = CompletableDeferred()
    )

    data class Result(
        val exitCode: Int,
        val cwd: String,
        val bashPid: Int,
        val shellDied: Boolean = false
    )

    suspend fun run(
        command: String,
        timeoutSeconds: Long = 30L,
        onOutput: ((text: String, isStderr: Boolean) -> Unit)? = null
    ): Map<String, Any> = mutex.withLock {
        ensureShell()
        val h = handle ?: return@withLock errorMap("Shell failed to initialize")
        val nonce = (0 until 16).map { "0123456789abcdef".random() }.joinToString("")
        val sink = CommandSink(nonce = nonce, onOutput = onOutput)
        currentSink.set(sink)

        // Sentinel command: run user command, capture exit code, then emit sentinel to stderr with cwd
        val wrappedCommand = "$command ; __exit=\$? ; printf '\\n\\036%s\\037%d\\037%d\\037%s\\036\\n' '$nonce' \"\$__exit\" \"\$\$\" \"\$PWD\" >&2"
        h.writeLine(wrappedCommand)

        val result = withTimeoutOrNull(timeoutSeconds.seconds) { sink.done.await() }
        if (result == null) {
            cancelForeground()
            val recovered = withTimeoutOrNull(2.seconds) { sink.done.await() }
            currentSink.set(null)
            if (recovered == null) {
                reset()
                return@withLock timeoutMap(sink, "Command timed out after ${timeoutSeconds}s and shell was reset")
            }
            return@withLock buildResult(sink, recovered, timedOut = true)
        }

        currentSink.set(null)
        if (result.shellDied) {
            return@withLock buildResult(sink, result, shellDied = true)
        }
        bashPid = result.bashPid
        return@withLock buildResult(sink, result)
    }

    fun writeInput(line: String) {
        handle?.writeLine(line)
    }

    fun cancelForeground() {
        val pid = bashPid
        if (pid == null) {
            reset()
            return
        }
        scope.launch {
            for (sig in listOf("INT", "TERM", "KILL")) {
                sendSignalToChildren(pid, sig)
                delay(400.milliseconds)
                val done = currentSink.get()?.done?.isCompleted
                if (done == true || currentSink.get() == null) return@launch
            }
            reset()
        }
    }

    fun reset() {
        watchdog?.cancel()
        watchdog = null
        handle?.cancel()
        handle = null
        bashPid = null
        currentSink.getAndSet(null)?.done?.complete(
            Result(exitCode = -1, cwd = "/root", bashPid = 0, shellDied = true)
        )
    }

    private fun ensureShell() {
        if (handle != null) return

        val h = launcher.startStreaming(
            command = "exec bash --noprofile --norc",
            workingDir = "/root"
        ) { process, cancelled ->
            val stdoutFut = CompletableFuture.runAsync {
                val reader = process.inputStream.bufferedReader()
                while (!cancelled.get()) {
                    val line = reader.readLine() ?: break
                    dispatchStdout(line)
                }
            }
            val stderrFut = CompletableFuture.runAsync {
                val reader = process.errorStream.bufferedReader()
                while (!cancelled.get()) {
                    val line = reader.readLine() ?: break
                    dispatchStderr(line)
                }
            }
            listOf(stdoutFut, stderrFut)
        }

        handle = h
        // Probe bash pid at startup
        h.writeLine("printf '\\n\\036ANGIBASHPID\\037%d\\036\\n' \"\$\$\" >&2")

        watchdog = scope.launch {
            h.awaitExit()
            currentSink.getAndSet(null)?.done?.complete(
                Result(exitCode = -1, cwd = "/root", bashPid = bashPid ?: 0, shellDied = true)
            )
            handle = null
            bashPid = null
        }
    }

    private fun dispatchStdout(line: String) {
        val sink = currentSink.get() ?: return
        if (sink.stdoutBuf.length < MAX_OUTPUT_LENGTH) {
            if (sink.stdoutBuf.isNotEmpty()) sink.stdoutBuf.append('\n')
            sink.stdoutBuf.append(line)
        }
        runCatching { sink.onOutput?.invoke(line, false) }
    }

    private fun dispatchStderr(line: String) {
        if (line.isEmpty()) return
        if (line.startsWith(PID_PROBE_PREFIX) && line.endsWith(RS)) {
            val pidText = line.substring(PID_PROBE_PREFIX.length, line.length - 1)
            pidText.toIntOrNull()?.let { bashPid = it }
            return
        }

        val sink = currentSink.get() ?: return
        if (line.length >= 2 && line.startsWith(RS) && line.endsWith(RS)) {
            val payload = line.substring(1, line.length - 1)
            val parts = payload.split(US)
            if (parts.size == 4 && parts[0] == sink.nonce) {
                val exit = parts[1].toIntOrNull() ?: -1
                val pid = parts[2].toIntOrNull() ?: 0
                val cwd = parts[3]
                sink.done.complete(Result(exitCode = exit, cwd = cwd, bashPid = pid))
                return
            }
        }

        if (sink.stderrBuf.length < MAX_OUTPUT_LENGTH) {
            if (sink.stderrBuf.isNotEmpty()) sink.stderrBuf.append('\n')
            sink.stderrBuf.append(line)
        }
        runCatching { sink.onOutput?.invoke(line, true) }
    }

    private fun sendSignalToChildren(parentPid: Int, signal: String) {
        runCatching {
            launcher.execute(
                command = "kids=\$(pgrep -P $parentPid); [ -n \"\$kids\" ] && kill -$signal \$kids",
                timeoutSeconds = 5L
            )
        }
    }

    private fun buildResult(
        sink: CommandSink,
        result: Result,
        timedOut: Boolean = false,
        shellDied: Boolean = false
    ): Map<String, Any> {
        val stderr = if (shellDied || result.shellDied) {
            val tail = sink.stderrBuf.toString()
            if (tail.isEmpty()) "Shell session ended" else "$tail\nShell session ended"
        } else {
            sink.stderrBuf.toString()
        }
        return mapOf(
            "success" to (!timedOut && !shellDied && !result.shellDied && result.exitCode == 0),
            "stdout" to truncate(sink.stdoutBuf.toString()),
            "stderr" to truncate(stderr),
            "exit_code" to if (timedOut) -1 else result.exitCode,
            "timed_out" to timedOut,
            "cwd" to result.cwd,
            "shell_died" to (shellDied || result.shellDied)
        )
    }

    private fun timeoutMap(sink: CommandSink, stderr: String): Map<String, Any> = mapOf(
        "success" to false,
        "stdout" to truncate(sink.stdoutBuf.toString()),
        "stderr" to truncate(sink.stderrBuf.toString() + "\n" + stderr),
        "exit_code" to -1,
        "timed_out" to true,
        "cwd" to "/root",
        "shell_died" to true
    )

    private fun errorMap(stderr: String): Map<String, Any> = mapOf(
        "success" to false,
        "stdout" to "",
        "stderr" to stderr,
        "exit_code" to -1,
        "timed_out" to false,
        "cwd" to "/root",
        "shell_died" to false
    )

    private fun truncate(text: String): String {
        if (text.length <= MAX_OUTPUT_LENGTH) return text
        val half = MAX_OUTPUT_LENGTH / 2
        return text.take(half) + "\n\n[... Truncated ${text.length - MAX_OUTPUT_LENGTH} characters ...]\n\n" + text.takeLast(half)
    }
}
