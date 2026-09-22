package com.example.angi.data.environment

import android.content.Context
import com.example.angi.domain.environment.LinuxEnvironment
import com.example.angi.domain.environment.LinuxEnvironmentDefinition
import com.example.angi.domain.environment.LinuxEnvironmentManager
import com.example.angi.domain.environment.LinuxEnvironmentMetadata
import com.example.angi.domain.environment.LinuxEnvironmentStatus
import com.example.angi.domain.environment.LinuxRwSelfTestResult
import com.example.angi.domain.environment.PinnedLinuxEnvironments
import com.example.angi.runtime.linux.LinuxPathResolver
import com.example.angi.runtime.linux.SecureArchiveExtractor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.UUID

/**
 * Checkpoint B & Fallback Linux Environment Manager.
 * Manages isolated Linux filesystem environments (e.g. Alpine 3.21 AArch64) with
 * SHA-256 verification, safe extraction, and path resolution for tools.
 */
class DefaultLinuxEnvironmentManager(
    private val context: Context
) : LinuxEnvironmentManager {

    private val baseDir = File(context.filesDir, "linux_environments")
    private val archiveExtractor = SecureArchiveExtractor()
    private val _environmentsFlow = MutableStateFlow<List<LinuxEnvironment>>(emptyList())

    init {
        if (!baseDir.exists()) {
            baseDir.mkdirs()
        }
        refreshEnvironments()
    }

    private fun getEnvDir(environmentId: String): File {
        return File(baseDir, environmentId).apply {
            if (!exists()) mkdirs()
        }
    }

    private fun getMetadataFile(envDir: File): File = File(envDir, "metadata.json")

    fun getPathResolver(environmentId: String = PinnedLinuxEnvironments.ALPINE_3_21_AARCH64.id): LinuxPathResolver {
        return LinuxPathResolver(getEnvDir(environmentId))
    }

    fun getPathResolver(): LinuxPathResolver {
        val installed = _environmentsFlow.value.firstOrNull { it.status == LinuxEnvironmentStatus.INSTALLED }
        return getPathResolver(installed?.definition?.id ?: PinnedLinuxEnvironments.ALPINE_3_21_AARCH64.id)
    }

    override fun environments(): Flow<List<LinuxEnvironment>> = _environmentsFlow.asStateFlow()

    fun refreshEnvironments() {
        val list = PinnedLinuxEnvironments.DEFAULT_DEFINITIONS.map { def ->
            val envDir = File(baseDir, def.id)
            val metaFile = getMetadataFile(envDir)
            val meta = if (metaFile.exists()) readMetadata(metaFile, def) else null
            val rootfsDir = File(envDir, "rootfs")
            val isInstalled = rootfsDir.exists() && (rootfsDir.list()?.isNotEmpty() == true)

            val status = when {
                isInstalled -> LinuxEnvironmentStatus.INSTALLED
                meta?.lastError != null -> LinuxEnvironmentStatus.FAILED
                File(envDir, "downloads").listFiles()?.isNotEmpty() == true -> LinuxEnvironmentStatus.DOWNLOADING
                else -> LinuxEnvironmentStatus.NOT_INSTALLED
            }
            LinuxEnvironment(definition = def, status = status, metadata = meta)
        }
        _environmentsFlow.value = list
    }

    override suspend fun download(definition: LinuxEnvironmentDefinition): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            updateEnvironmentStatus(definition.id, LinuxEnvironmentStatus.DOWNLOADING)
            val envDir = getEnvDir(definition.id)
            val downloadsDir = File(envDir, "downloads").apply { if (!exists()) mkdirs() }
            val fileName = definition.sourceUrl.substringAfterLast("/").ifEmpty { "${definition.id}.tar.gz" }
            val targetFile = File(downloadsDir, fileName)

            if (definition.sourceUrl.startsWith("http://") || definition.sourceUrl.startsWith("https://")) {
                val connection = (URL(definition.sourceUrl).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 15000
                    readTimeout = 30000
                    instanceFollowRedirects = true
                }
                connection.inputStream.use { input ->
                    FileOutputStream(targetFile).use { output ->
                        input.copyTo(output)
                    }
                }
            } else {
                if (!targetFile.exists()) {
                    targetFile.writeText("stub")
                }
            }

            var actualSha = ""
            if (targetFile.exists() && targetFile.length() > 0) {
                actualSha = computeSha256(targetFile)
                if (definition.expectedSha256.isNotBlank() && !actualSha.equals(definition.expectedSha256, ignoreCase = true)) {
                    throw SecurityException("SHA-256 verification failed: expected ${definition.expectedSha256}, got $actualSha")
                }
            }

            val meta = LinuxEnvironmentMetadata(
                environmentId = definition.id,
                distribution = definition.distribution,
                version = definition.version,
                architecture = definition.architecture,
                sourceUrl = definition.sourceUrl,
                expectedSha256 = definition.expectedSha256,
                actualSha256 = actualSha,
                archiveSizeBytes = targetFile.length()
            )
            writeMetadata(getMetadataFile(envDir), meta)
            updateEnvironmentStatus(definition.id, LinuxEnvironmentStatus.VERIFYING, meta)
            Unit
        }.onFailure { err ->
            updateEnvironmentStatus(definition.id, LinuxEnvironmentStatus.FAILED, lastError = err.message)
        }
    }

    override suspend fun install(environmentId: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val def = PinnedLinuxEnvironments.DEFAULT_DEFINITIONS.find { it.id == environmentId }
                ?: throw IllegalArgumentException("Unknown environment ID: $environmentId")
            val envDir = getEnvDir(environmentId)
            val downloadsDir = File(envDir, "downloads")
            val rootfsDir = File(envDir, "rootfs").apply { if (!exists()) mkdirs() }
            val archiveFile = downloadsDir.listFiles()?.firstOrNull { it.isFile && (it.name.endsWith(".tar.gz") || it.name.endsWith(".tar.xz")) }
                ?: throw IllegalStateException("No archive file found in downloads to install")

            updateEnvironmentStatus(environmentId, LinuxEnvironmentStatus.EXTRACTING)

            if (archiveFile.name.endsWith(".tar.xz")) {
                archiveExtractor.extractTarXz(archiveFile, rootfsDir)
            } else {
                archiveExtractor.extractTarGz(archiveFile, rootfsDir)
            }

            val metaFile = getMetadataFile(envDir)
            val existingMeta = if (metaFile.exists()) readMetadata(metaFile, def) else null
            val updatedMeta = (existingMeta ?: LinuxEnvironmentMetadata(
                environmentId = def.id,
                distribution = def.distribution,
                version = def.version,
                architecture = def.architecture,
                sourceUrl = def.sourceUrl,
                expectedSha256 = def.expectedSha256
            )).copy(
                installedSizeBytes = calculateDirSize(rootfsDir),
                installTimestamp = System.currentTimeMillis(),
                lastError = null
            )
            writeMetadata(metaFile, updatedMeta)
            updateEnvironmentStatus(environmentId, LinuxEnvironmentStatus.INSTALLED, updatedMeta)
            Unit
        }.onFailure { err ->
            updateEnvironmentStatus(environmentId, LinuxEnvironmentStatus.FAILED, lastError = err.message)
        }
    }

    override suspend fun delete(environmentId: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val envDir = File(baseDir, environmentId)
            if (envDir.exists()) {
                envDir.deleteRecursively()
            }
            refreshEnvironments()
            Unit
        }
    }

    override suspend fun status(environmentId: String): LinuxEnvironmentStatus {
        refreshEnvironments()
        return _environmentsFlow.value.firstOrNull { it.definition.id == environmentId }?.status
            ?: LinuxEnvironmentStatus.NOT_INSTALLED
    }

    override suspend fun runSelfTest(environmentId: String): LinuxRwSelfTestResult = withContext(Dispatchers.IO) {
        val resolver = getPathResolver(environmentId)
        val nonce1 = UUID.randomUUID().toString().take(8)
        val nonce2 = UUID.randomUUID().toString().take(8)

        var writeSuccess = false
        var readSuccess = false
        var modifySuccess = false
        val details = StringBuilder()

        try {
            val resolved = resolver.resolve("/workspace/angi-rw-test.txt", requireWritable = true)
            val targetFile = when (resolved) {
                is LinuxPathResolver.ResolvedTarget.Workspace -> resolved.file
                else -> throw SecurityException("Target not in workspace")
            }

            val expectedContent1 = "ANGI_WRITE_TEST_$nonce1"
            targetFile.writeText(expectedContent1, Charsets.UTF_8)
            writeSuccess = true
            details.append("WRITE PASS (nonce1: $nonce1); ")

            val readBack1 = targetFile.readText(Charsets.UTF_8)
            if (readBack1 == expectedContent1) {
                readSuccess = true
                details.append("READ PASS; ")
            } else {
                details.append("READ FAIL: expected '$expectedContent1' but got '$readBack1'; ")
            }

            val expectedContent2 = "ANGI_APPEND_TEST_$nonce2"
            targetFile.writeText(expectedContent2, Charsets.UTF_8)
            val readBack2 = targetFile.readText(Charsets.UTF_8)
            if (readBack2 == expectedContent2) {
                modifySuccess = true
                details.append("MODIFY PASS (nonce2: $nonce2).")
            } else {
                details.append("MODIFY FAIL: expected '$expectedContent2' but got '$readBack2'.")
            }
        } catch (e: Throwable) {
            details.append("EXCEPTION: ${e.localizedMessage}")
        }

        LinuxRwSelfTestResult(
            writeSuccess = writeSuccess,
            readSuccess = readSuccess,
            modifySuccess = modifySuccess,
            nonce1 = nonce1,
            nonce2 = nonce2,
            details = details.toString()
        )
    }

    private fun computeSha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { fis ->
            val buffer = ByteArray(8192)
            var bytesRead: Int
            while (fis.read(buffer).also { bytesRead = it } != -1) {
                digest.update(buffer, 0, bytesRead)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun calculateDirSize(dir: File): Long {
        var size = 0L
        dir.walkTopDown().forEach {
            if (it.isFile) size += it.length()
        }
        return size
    }

    private fun readMetadata(file: File, def: LinuxEnvironmentDefinition): LinuxEnvironmentMetadata {
        return try {
            val json = JSONObject(file.readText(Charsets.UTF_8))
            LinuxEnvironmentMetadata(
                environmentId = json.optString("environmentId", def.id),
                distribution = json.optString("distribution", def.distribution),
                version = json.optString("version", def.version),
                architecture = json.optString("architecture", def.architecture),
                sourceUrl = json.optString("sourceUrl", def.sourceUrl),
                expectedSha256 = json.optString("expectedSha256", def.expectedSha256),
                actualSha256 = json.optString("actualSha256", null),
                archiveSizeBytes = json.optLong("archiveSizeBytes", 0L),
                installedSizeBytes = json.optLong("installedSizeBytes", 0L),
                installTimestamp = json.optLong("installTimestamp", 0L),
                lastError = json.optString("lastError", null)
            )
        } catch (e: Exception) {
            LinuxEnvironmentMetadata(
                environmentId = def.id,
                distribution = def.distribution,
                version = def.version,
                architecture = def.architecture,
                sourceUrl = def.sourceUrl,
                expectedSha256 = def.expectedSha256
            )
        }
    }

    private fun writeMetadata(file: File, meta: LinuxEnvironmentMetadata) {
        val json = JSONObject().apply {
            put("environmentId", meta.environmentId)
            put("distribution", meta.distribution)
            put("version", meta.version)
            put("architecture", meta.architecture)
            put("sourceUrl", meta.sourceUrl)
            put("expectedSha256", meta.expectedSha256)
            put("actualSha256", meta.actualSha256)
            put("archiveSizeBytes", meta.archiveSizeBytes)
            put("installedSizeBytes", meta.installedSizeBytes)
            put("installTimestamp", meta.installTimestamp)
            put("lastError", meta.lastError)
        }
        file.writeText(json.toString(2), Charsets.UTF_8)
    }

    private fun updateEnvironmentStatus(
        environmentId: String,
        status: LinuxEnvironmentStatus,
        metadata: LinuxEnvironmentMetadata? = null,
        lastError: String? = null
    ) {
        val currentList = _environmentsFlow.value
        _environmentsFlow.value = currentList.map { env ->
            if (env.definition.id == environmentId) {
                val newMeta = (metadata ?: env.metadata)?.copy(lastError = lastError)
                env.copy(status = status, metadata = newMeta)
            } else {
                env
            }
        }
    }
}
