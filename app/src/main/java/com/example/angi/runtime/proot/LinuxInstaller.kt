package com.example.angi.runtime.proot

import android.content.Context
import com.example.angi.runtime.linux.SecureArchiveExtractor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

sealed interface InstallStep {
    data object ResolveImage : InstallStep
    data class Download(
        val fraction: Float,
        val downloadedBytes: Long? = null,
        val totalBytes: Long? = null
    ) : InstallStep
    data object Extract : InstallStep
    data object Configure : InstallStep
    data object ProotTest : InstallStep
    data object AptUpdate : InstallStep
    data class Packages(val packages: List<String>) : InstallStep
    data object Finalize : InstallStep
    data object Complete : InstallStep
}

data class InstallResult(
    val archiveSizeBytes: Long,
    val sha256: String,
    val downloadUrl: String
)

private const val UPDATE_TIMEOUT_SECONDS = 300L
private const val PACKAGE_TIMEOUT_SECONDS = 900L

class LinuxInstaller(
    private val paths: LinuxPaths,
    private val httpClient: OkHttpClient = OkHttpClient(),
    private val extractor: SecureArchiveExtractor = SecureArchiveExtractor(),
    private val urlProvider: () -> List<String> = { DebianDistroSpec.rootfsUrls() },
    private val fileDownloader: ((url: String, target: File, onProgress: (Float, Long, Long?) -> Unit) -> Pair<Long, String>)? = null,
    private val archiveExtractor: ((archive: File, targetDir: File) -> Unit)? = null,
    private val commandExecutor: ((launcher: ProotLauncher, command: String, timeoutSeconds: Long) -> ProotResult)? = null
) {
    suspend fun installDebian(onStep: (InstallStep) -> Unit): Result<InstallResult> =
        withContext(Dispatchers.IO) {
            // Serialise the entire filesystem transaction, not only apt. A second Install
            // action must never race against extraction or activation of a first install.
            installLock.withLock {
                runCatching {
                    paths.ensureLayout()
                    val proot = File(paths.prootPath)
                    check(proot.exists()) {
                        "PRoot binary missing: ${paths.prootPath}. Existing Debian files were not touched."
                    }
                    paths.copyLibtalloc()

                    // Idempotent: normal app upgrades/restarts, refreshes or duplicate taps
                    // must NEVER reinstall or delete Debian and its user-owned /root.
                    if (paths.isInstalled()) {
                        onStep(InstallStep.Complete)
                        return@runCatching InstallResult(0L, "", "")
                    }
                    // Previous builds may have left an unmarked, partially installed rootfs.
                    // Do not destroy it: preserve keys, agent config and packages for repair.
                    check(!paths.hasRootfsData()) {
                        "An existing Debian filesystem was detected without a valid install marker. " +
                            "It has been preserved. Do not reinstall or clear ANGI data; inspect Diagnostics."
                    }

                    // Only this installer owns the staging area. Never extract directly into
                    // the live rootfs; interruption or download errors cannot corrupt it.
                    val staging = File(paths.root, "rootfs-install-staging")
                    if (staging.exists()) {
                        check(staging.deleteRecursively()) { "Cannot clear abandoned install staging area" }
                    }
                    check(staging.mkdirs()) { "Cannot create install staging area" }
                    try {
                        onStep(InstallStep.ResolveImage)
                        val urls = urlProvider()
                        check(urls.isNotEmpty()) { "No download URLs available for Debian" }
                        val downloadUrl = urls.first()

                        val archive = File(paths.root, DebianDistroSpec.ARCHIVE_NAME)
                        val (archiveSize, computedSha256) = try {
                            onStep(InstallStep.Download(0f, 0L, null))
                            val stats = downloadFile(downloadUrl, archive) { fraction, downloaded, total ->
                                onStep(InstallStep.Download(fraction, downloaded, total))
                            }
                            currentCoroutineContext().ensureActive()
                            onStep(InstallStep.Extract)
                            if (archiveExtractor != null) {
                                archiveExtractor.invoke(archive, staging)
                            } else {
                                extractor.extractTarXz(archive, staging)
                            }
                            stats
                        } finally {
                            archive.delete()
                        }

                        currentCoroutineContext().ensureActive()
                        onStep(InstallStep.Configure)
                        DebianDistroSpec.configure(staging)
                        paths.ensureMountPoints(staging)

                        val launcher = ProotLauncher(
                            prootPath = paths.prootPath,
                            libDir = paths.libDir,
                            rootfsPath = staging.absolutePath,
                            tmpPath = paths.tmpDir.absolutePath,
                            binds = listOf(paths.workspaceDir.absolutePath to "/workspace"),
                            extraArgs = DebianDistroSpec.prootArgs,
                            env = DebianDistroSpec.env
                        )
                        currentCoroutineContext().ensureActive()
                        onStep(InstallStep.ProotTest)
                        val testResult = executeCommand(
                            launcher, "/bin/sh -c 'echo ANGI_PROOT_OK'", timeoutSeconds = 30L
                        )
                        check(testResult.success && testResult.stdout.contains("ANGI_PROOT_OK")) {
                            "PRoot smoke test failed: ${testResult.failureDetail()}"
                        }

                        currentCoroutineContext().ensureActive()
                        packageLock.withLock {
                            onStep(InstallStep.AptUpdate)
                            refreshPackageIndex(launcher)
                            currentCoroutineContext().ensureActive()
                            installBasePackages(launcher, onStep)
                        }

                        currentCoroutineContext().ensureActive()
                        onStep(InstallStep.Finalize)
                        // Empty legacy rootfs directories are safe to remove. Never replace
                        // an existing nonempty directory, even if it appeared during setup.
                        if (paths.rootfsDir.isDirectory) {
                            check(!paths.hasRootfsData()) {
                                "Another process created Debian data during installation; preserved."
                            }
                            check(paths.rootfsDir.delete()) { "Cannot remove empty rootfs directory" }
                        }
                        check(!paths.rootfsDir.exists()) {
                            "Existing Debian filesystem must not be overwritten"
                        }
                        check(staging.renameTo(paths.rootfsDir)) {
                            "Could not activate validated Debian installation"
                        }
                        // A crash after the rename but before this marker write is recoverable
                        // via LinuxPaths.hasRecoverableRootfs(), not grounds for deletion.
                        paths.writeMarker(DebianDistroSpec.ID)
                        onStep(InstallStep.Complete)
                        InstallResult(
                            archiveSizeBytes = archiveSize,
                            sha256 = computedSha256,
                            downloadUrl = downloadUrl
                        )
                    } finally {
                        // ONLY cleanup installer-owned temporary files; never live rootfs.
                        if (staging.exists()) staging.deleteRecursively()
                    }
                }
            }
        }

    private fun executeCommand(launcher: ProotLauncher, command: String, timeoutSeconds: Long): ProotResult {
        return commandExecutor?.invoke(launcher, command, timeoutSeconds)
            ?: launcher.execute(command, timeoutSeconds = timeoutSeconds)
    }

    private fun downloadFile(
        url: String,
        target: File,
        onProgress: (Float, Long, Long?) -> Unit
    ): Pair<Long, String> {
        if (fileDownloader != null) {
            return fileDownloader.invoke(url, target, onProgress)
        }
        val request = Request.Builder().url(url).build()
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Failed to download rootfs: HTTP ${response.code}")
            val body = response.body ?: throw IOException("Empty response body from $url")
            val totalBytes = body.contentLength().takeIf { it > 0 }
            var downloaded = 0L
            val digest = java.security.MessageDigest.getInstance("SHA-256")

            FileOutputStream(target).use { fos ->
                val input = body.byteStream()
                val buffer = ByteArray(32768)
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    fos.write(buffer, 0, read)
                    digest.update(buffer, 0, read)
                    downloaded += read
                    val frac = if (totalBytes != null && totalBytes > 0) {
                        (downloaded.toFloat() / totalBytes).coerceIn(0f, 1f)
                    } else 0f
                    onProgress(frac, downloaded, totalBytes)
                }
            }
            val sha256Hex = digest.digest().joinToString("") { "%02x".format(it) }
            return Pair(downloaded, sha256Hex)
        }
    }

    private fun refreshPackageIndex(launcher: ProotLauncher) {
        val result = executeCommand(launcher, "apt-get update", timeoutSeconds = UPDATE_TIMEOUT_SECONDS)
        check(result.success) { "apt-get update failed: ${result.failureDetail()}" }
    }

    private fun installBasePackages(launcher: ProotLauncher, onStep: (InstallStep) -> Unit) {
        val packages = DebianDistroSpec.basePackages
        onStep(InstallStep.Packages(packages))
        val cmd = "apt-get install -y --no-install-recommends " + packages.joinToString(" ")
        val result = executeCommand(launcher, cmd, timeoutSeconds = PACKAGE_TIMEOUT_SECONDS)
        check(result.success) { "Failed to install base packages: ${result.failureDetail()}" }
    }

    companion object {
        val packageLock = Mutex()
        private val installLock = Mutex()
    }
}
