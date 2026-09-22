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
    data class Download(val fraction: Float) : InstallStep
    data object Extract : InstallStep
    data object Configure : InstallStep
    data class Packages(val packages: List<String>) : InstallStep
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
    private val httpClient: OkHttpClient = OkHttpClient()
) {
    private val extractor = SecureArchiveExtractor()

    suspend fun installDebian(onStep: (InstallStep) -> Unit): Result<InstallResult> = withContext(Dispatchers.IO) {
        runCatching {
            paths.ensureLayout()
            val proot = File(paths.prootPath)
            check(proot.exists()) {
                "proot binary not found at ${paths.prootPath}. nativeLibraryDir contents: " +
                        (File(paths.nativeLibDir).listFiles()?.map { it.name } ?: "empty")
            }
            paths.copyLibtalloc()
            paths.deleteInstall()
            paths.ensureLayout()

            val archive = File(paths.root, DebianDistroSpec.ARCHIVE_NAME)
            val urls = DebianDistroSpec.rootfsUrls()
            check(urls.isNotEmpty()) { "No download URLs available for Debian" }
            val downloadUrl = urls.first()

            val (archiveSize, computedSha256) = try {
                onStep(InstallStep.Download(0f))
                val stats = downloadFile(downloadUrl, archive) { onStep(InstallStep.Download(it)) }
                currentCoroutineContext().ensureActive()

                onStep(InstallStep.Extract)
                extractor.extractTarXz(archive, paths.rootfsDir)
                stats
            } finally {
                archive.delete()
            }

            currentCoroutineContext().ensureActive()
            onStep(InstallStep.Configure)
            DebianDistroSpec.configure(paths.rootfsDir)
            paths.ensureMountPoints()

            val launcher = ProotLauncher(
                prootPath = paths.prootPath,
                libDir = paths.libDir,
                rootfsPath = paths.rootfsDir.absolutePath,
                tmpPath = paths.tmpDir.absolutePath,
                binds = listOf(paths.workspaceDir.absolutePath to "/workspace"),
                extraArgs = DebianDistroSpec.prootArgs,
                env = DebianDistroSpec.env
            )

            currentCoroutineContext().ensureActive()
            packageLock.withLock {
                refreshPackageIndex(launcher)
                currentCoroutineContext().ensureActive()
                installBasePackages(launcher, onStep)
            }

            paths.writeMarker(DebianDistroSpec.ID)
            InstallResult(
                archiveSizeBytes = archiveSize,
                sha256 = computedSha256,
                downloadUrl = downloadUrl
            )
        }.onFailure {
            paths.deleteInstall()
        }
    }

    private fun downloadFile(url: String, target: File, onProgress: (Float) -> Unit): Pair<Long, String> {
        val request = Request.Builder().url(url).build()
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Failed to download rootfs: HTTP ${response.code}")
            val body = response.body ?: throw IOException("Empty response body from $url")
            val totalBytes = body.contentLength()
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
                    if (totalBytes > 0) {
                        onProgress((downloaded.toFloat() / totalBytes).coerceIn(0f, 1f))
                    }
                }
            }
            val sha256Hex = digest.digest().joinToString("") { "%02x".format(it) }
            return Pair(downloaded, sha256Hex)
        }
    }

    private fun refreshPackageIndex(launcher: ProotLauncher) {
        val result = launcher.execute("apt-get update", timeoutSeconds = UPDATE_TIMEOUT_SECONDS)
        check(result.success) { "apt-get update failed: ${result.failureDetail()}" }
    }

    private fun installBasePackages(launcher: ProotLauncher, onStep: (InstallStep) -> Unit) {
        val packages = DebianDistroSpec.basePackages
        onStep(InstallStep.Packages(packages))
        val cmd = "apt-get install -y --no-install-recommends " + packages.joinToString(" ")
        val result = launcher.execute(cmd, timeoutSeconds = PACKAGE_TIMEOUT_SECONDS)
        check(result.success) { "Failed to install base packages: ${result.failureDetail()}" }
    }

    companion object {
        val packageLock = Mutex()
    }
}
