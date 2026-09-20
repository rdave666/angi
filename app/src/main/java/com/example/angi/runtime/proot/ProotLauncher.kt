package com.example.angi.runtime.proot

import java.io.BufferedReader
import java.io.File
import java.io.IOException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

private const val DEFAULT_MAX_OUTPUT_CHARS = 30_000
private const val DEFAULT_GUEST_PATH = "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"

data class ProotResult(
    val success: Boolean,
    val stdout: String = "",
    val stderr: String = "",
    val exitCode: Int = 0,
    val timedOut: Boolean = false,
    val error: String? = null
) {
    fun failureDetail(maxChars: Int = 500): String {
        val raw = stderr.ifBlank { stdout }.ifBlank { error.orEmpty() }.trim()
        if (raw.length <= maxChars) return raw
        return "…" + raw.takeLast(maxChars)
    }
}

class ProotHandle internal constructor(
    val process: Process,
    val cancelled: AtomicBoolean,
    private val readerFutures: List<CompletableFuture<Void>>
) {
    private val writer = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "angi-proot-write").apply { isDaemon = true }
    }

    fun isCancelled(): Boolean = cancelled.get()

    fun cancel() {
        cancelled.set(true)
        runCatching { writer.shutdownNow() }
        runCatching { process.inputStream.close() }
        runCatching { process.errorStream.close() }
        runCatching { process.outputStream.close() }
        process.destroyForcibly()
    }

    fun writeBytes(data: ByteArray) {
        if (cancelled.get() || data.isEmpty()) return
        runCatching {
            writer.execute {
                if (cancelled.get()) return@execute
                runCatching {
                    process.outputStream.write(data)
                    process.outputStream.flush()
                }
            }
        }
    }

    fun writeText(text: String) = writeBytes(text.toByteArray(Charsets.UTF_8))

    fun writeLine(line: String) = writeText(line + "\n")

    fun awaitExit(): Int {
        while (!cancelled.get() && process.isAlive) {
            runCatching { process.waitFor(200, TimeUnit.MILLISECONDS) }
        }
        if (cancelled.get()) return -1
        readerFutures.forEach { runCatching { it.get(500, TimeUnit.MILLISECONDS) } }
        return runCatching { process.exitValue() }.getOrDefault(-1)
    }
}

class ProotLauncher(
    private val prootPath: String,
    private val libDir: String,
    private val rootfsPath: String,
    private val tmpPath: String,
    private val binds: List<Pair<String, String>> = emptyList(),
    private val extraArgs: List<String> = emptyList(),
    private val env: Map<String, String> = emptyMap()
) {
    fun buildArgs(command: String, workingDir: String): Array<String> = buildList {
        add(prootPath)
        addAll(extraArgs)
        add("--rootfs=$rootfsPath")
        add("--bind=/dev")
        add("--bind=/proc")
        add("--bind=/sys")
        binds.forEach { (host, guest) -> add("--bind=$host:$guest") }
        add("--bind=$tmpPath:/tmp")
        add("-0")
        add("-w")
        add(workingDir)
        add("/bin/sh")
        add("-c")
        add(command)
    }.toTypedArray()

    fun buildEnv(extraEnv: Map<String, String>): Array<String> {
        val loaderPath = File(prootPath).parent.orEmpty() + "/libproot-loader.so"
        val base = mapOf(
            "HOME" to "/root",
            "PATH" to DEFAULT_GUEST_PATH,
            "TERM" to "xterm-256color",
            "LANG" to "C.UTF-8",
            "LD_LIBRARY_PATH" to libDir,
            "PROOT_TMP_DIR" to tmpPath,
            "PROOT_LOADER" to loaderPath
        )
        return (base + env + extraEnv).map { (k, v) -> "$k=$v" }.toTypedArray()
    }

    fun start(
        command: String,
        workingDir: String,
        extraEnv: Map<String, String> = emptyMap()
    ): Process = Runtime.getRuntime().exec(
        buildArgs(command, workingDir),
        buildEnv(extraEnv),
        File(rootfsPath).parentFile
    )

    fun execute(
        command: String,
        timeoutSeconds: Long,
        workingDir: String = "/root",
        extraEnv: Map<String, String> = emptyMap(),
        maxOutputChars: Int = DEFAULT_MAX_OUTPUT_CHARS
    ): ProotResult = try {
        val process = start(command, workingDir, extraEnv)
        val stdout = CompletableFuture.supplyAsync {
            readBounded(process.inputStream.bufferedReader(), maxOutputChars)
        }
        val stderr = CompletableFuture.supplyAsync {
            readBounded(process.errorStream.bufferedReader(), maxOutputChars)
        }

        if (process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
            val out = stdout.get()
            val err = stderr.get()
            ProotResult(
                success = process.exitValue() == 0,
                stdout = smartTruncate(out, maxOutputChars),
                stderr = smartTruncate(err, maxOutputChars),
                exitCode = process.exitValue()
            )
        } else {
            process.destroyForcibly()
            val out = runCatching { stdout.get(1, TimeUnit.SECONDS) }.getOrDefault("")
            val err = runCatching { stderr.get(1, TimeUnit.SECONDS) }.getOrDefault("")
            ProotResult(
                success = false,
                stdout = smartTruncate(out, maxOutputChars),
                stderr = smartTruncate(err, maxOutputChars),
                timedOut = true,
                error = "Timed out after ${timeoutSeconds}s"
            )
        }
    } catch (e: Exception) {
        ProotResult(success = false, error = e.message ?: "Failed to execute command in sandbox")
    }

    fun startStreaming(
        command: String,
        workingDir: String,
        extraEnv: Map<String, String> = emptyMap(),
        readers: (Process, AtomicBoolean) -> List<CompletableFuture<Void>>
    ): ProotHandle {
        val process = start(command, workingDir, extraEnv)
        val cancelled = AtomicBoolean(false)
        return ProotHandle(process, cancelled, readers(process, cancelled))
    }

    private fun readBounded(reader: BufferedReader, maxChars: Int): String {
        val sb = StringBuilder()
        val buf = CharArray(8192)
        try {
            var read: Int
            while (reader.read(buf).also { read = it } != -1) {
                sb.append(buf, 0, read)
                if (sb.length >= maxChars) break
            }
            if (sb.length >= maxChars) {
                while (reader.read(buf) != -1) { /* discard remainder */ }
            }
        } catch (_: IOException) {
            // Stream closed
        }
        return sb.toString()
    }

    private fun smartTruncate(text: String, maxChars: Int): String {
        if (text.length <= maxChars) return text
        val half = maxChars / 2
        return text.take(half) + "\n\n[... Truncated ${text.length - maxChars} characters ...]\n\n" + text.takeLast(half)
    }
}
