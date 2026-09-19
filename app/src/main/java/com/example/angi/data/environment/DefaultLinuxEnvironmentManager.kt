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
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit

class DefaultLinuxEnvironmentManager(
    private val context: Context,
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()
) : LinuxEnvironmentManager {

    private val environmentsBaseDir: File = File(context.filesDir, "environments")
    private val _environmentsFlow = MutableStateFlow<List<LinuxEnvironment>>(emptyList())
    private val extractor = SecureArchiveExtractor()

    init {
        if (!environmentsBaseDir.exists()) {
            environmentsBaseDir.mkdirs()
        }
        refreshEnvironments()
    }

    private fun getEnvDir(environmentId: String): File = File(environmentsBaseDir, environmentId)
    private fun getMetadataFile(environmentId: String): File = File(getEnvDir(environmentId), "metadata.json")

    fun getPathResolver(environmentId: String = PinnedLinuxEnvironments.ALPINE_3_21_AARCH64.id): LinuxPathResolver {
        return LinuxPathResolver(getEnvDir(environmentId))
    }

    override fun environments(): Flow<List<LinuxEnvironment>> = _environmentsFlow.asStateFlow()

    private fun refreshEnvironments() {
        val list = mutableListOf<LinuxEnvironment>()
        for (def in PinnedLinuxEnvironments.DEFAULT_DEFINITIONS) {
            val envDir = getEnvDir(def.id)
            val metaFile = getMetadataFile(def.id)
            if (metaFile.exists()) {
                val meta = readMetadata(metaFile, def)
                val status = if (File(envDir, "rootfs").exists() && meta.installTimestamp > 0L) {
                    LinuxEnvironmentStatus.INSTALLED
                } else if (meta.lastError != null) {
                    LinuxEnvironmentStatus.FAILED
                } else {
                    LinuxEnvironmentStatus.NOT_INSTALLED
                }
                list.add(LinuxEnvironment(def, status, meta))
            } else {
                list.add(LinuxEnvironment(def, LinuxEnvironmentStatus.NOT_INSTALLED, null))
            }
        }
        _environmentsFlow.value = list
    }

    private fun updateStatus(environmentId: String, status: LinuxEnvironmentStatus, lastError: String? = null) {
        val current = _environmentsFlow.value.toMutableList()
        val index = current.indexOfFirst { it.definition.id == environmentId }
        if (index != -1) {
            val item = current[index]
            val meta = item.metadata?.copy(lastError = lastError) ?: LinuxEnvironmentMetadata(
                environmentId = environmentId,
                distribution = item.definition.distribution,
                version = item.definition.version,
                architecture = item.definition.architecture,
                sourceUrl = item.definition.sourceUrl,
                expectedSha256 = item.definition.expectedSha256,
                lastError = lastError
            )
            current[index] = item.copy(status = status, metadata = meta)
            _environmentsFlow.value = current
        }
    }

    override suspend fun download(definition: LinuxEnvironmentDefinition): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val envDir = getEnvDir(definition.id)
            val downloadsDir = File(envDir, "downloads")
            downloadsDir.mkdirs()

            val tempFile = File(downloadsDir, "${definition.id}.tar.gz.part")
            val targetArchive = File(downloadsDir, "${definition.id}.tar.gz")

            updateStatus(definition.id, LinuxEnvironmentStatus.DOWNLOADING)

            // Step 1: Download to temporary archive
            val request = Request.Builder().url(definition.sourceUrl).build()
            val response = httpClient.newCall(request).execute()
            if (!response.isSuccessful) {
                throw IllegalStateException("Download failed with HTTP ${response.code}: ${response.message}")
            }

            val body = response.body ?: throw IllegalStateException("Empty response body from ${definition.sourceUrl}")
            val archiveSize: Long
            body.byteStream().use { input ->
                FileOutputStream(tempFile).use { output ->
                    input.copyTo(output)
                    output.flush()
                }
            }
            archiveSize = tempFile.length()

            // Step 2: Calculate and verify exact SHA-256
            updateStatus(definition.id, LinuxEnvironmentStatus.VERIFYING)
            val actualSha256 = computeSha256(tempFile)
            if (!actualSha256.equals(definition.expectedSha256, ignoreCase = true)) {
                tempFile.delete()
                val err = "SHA-256 mismatch! Expected: ${definition.expectedSha256}, actual: $actualSha256"
                updateStatus(definition.id, LinuxEnvironmentStatus.FAILED, err)
                throw SecurityException(err)
            }

            if (targetArchive.exists()) targetArchive.delete()
            if (!tempFile.renameTo(targetArchive)) {
                tempFile.copyTo(targetArchive, overwrite = true)
                tempFile.delete()
            }

            val metadata = LinuxEnvironmentMetadata(
                environmentId = definition.id,
                distribution = definition.distribution,
                version = definition.version,
                architecture = definition.architecture,
                sourceUrl = definition.sourceUrl,
                expectedSha256 = definition.expectedSha256,
                actualSha256 = actualSha256,
                archiveSizeBytes = archiveSize,
                installedSizeBytes = 0L,
                installTimestamp = 0L,
                lastError = null
            )
            writeMetadata(getMetadataFile(definition.id), metadata)
            refreshEnvironments()
            Unit
        }.onFailure { e ->
            updateStatus(definition.id, LinuxEnvironmentStatus.FAILED, e.message)
        }
    }

    override suspend fun install(environmentId: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val def = PinnedLinuxEnvironments.DEFAULT_DEFINITIONS.find { it.id == environmentId }
                ?: throw IllegalArgumentException("Unknown environment definition: '$environmentId'")

            val envDir = getEnvDir(environmentId)
            val archive = File(envDir, "downloads/${environmentId}.tar.gz")
            if (!archive.exists()) {
                throw IllegalStateException("Archive not downloaded for environment '$environmentId'. Run download first.")
            }

            // Re-verify checksum before extraction
            updateStatus(environmentId, LinuxEnvironmentStatus.VERIFYING)
            val actualSha = computeSha256(archive)
            if (!actualSha.equals(def.expectedSha256, ignoreCase = true)) {
                throw SecurityException("Corrupted archive! SHA-256 mismatch before extraction: $actualSha")
            }

            updateStatus(environmentId, LinuxEnvironmentStatus.EXTRACTING)
            val rootfsDir = File(envDir, "rootfs")
            val workspaceDir = File(envDir, "workspace")
            rootfsDir.mkdirs()
            workspaceDir.mkdirs()

            val extractedBytes = extractor.extractTarGz(archive, rootfsDir)

            val metadata = LinuxEnvironmentMetadata(
                environmentId = def.id,
                distribution = def.distribution,
                version = def.version,
                architecture = def.architecture,
                sourceUrl = def.sourceUrl,
                expectedSha256 = def.expectedSha256,
                actualSha256 = actualSha,
                archiveSizeBytes = archive.length(),
                installedSizeBytes = extractedBytes,
                installTimestamp = System.currentTimeMillis(),
                lastError = null
            )
            writeMetadata(getMetadataFile(environmentId), metadata)
            refreshEnvironments()
            Unit
        }.onFailure { e ->
            updateStatus(environmentId, LinuxEnvironmentStatus.FAILED, e.message)
        }
    }

    override suspend fun delete(environmentId: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val envDir = getEnvDir(environmentId)
            if (envDir.exists()) {
                envDir.deleteRecursively()
            }
            refreshEnvironments()
            Unit
        }
    }

    override suspend fun status(environmentId: String): LinuxEnvironmentStatus {
        val env = _environmentsFlow.value.find { it.definition.id == environmentId }
        return env?.status ?: LinuxEnvironmentStatus.NOT_INSTALLED
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
            // Step 1: Write /workspace/angi-rw-test.txt with ANGI_WRITE_TEST_<nonce1>
            val resolved = resolver.resolve("/workspace/angi-rw-test.txt", requireWritable = true)
            val targetFile = when (resolved) {
                is LinuxPathResolver.ResolvedTarget.Workspace -> resolved.file
                else -> throw SecurityException("Target not in workspace")
            }

            val expectedContent1 = "ANGI_WRITE_TEST_$nonce1"
            targetFile.writeText(expectedContent1, Charsets.UTF_8)
            writeSuccess = true
            details.append("WRITE PASS (nonce1: $nonce1); ")

            // Step 2: Read it back and verify exact contents
            val readBack1 = targetFile.readText(Charsets.UTF_8)
            if (readBack1 == expectedContent1) {
                readSuccess = true
                details.append("READ PASS; ")
            } else {
                details.append("READ FAIL: expected '$expectedContent1' but got '$readBack1'; ")
            }

            // Step 3: Rewrite/Append with ANGI_APPEND_TEST_<nonce2>
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
        FileInputStream(file).use { fis ->
            val buf = ByteArray(16384)
            while (true) {
                val read = fis.read(buf)
                if (read <= 0) break
                digest.update(buf, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun writeMetadata(file: File, meta: LinuxEnvironmentMetadata) {
        file.parentFile?.mkdirs()
        val json = JSONObject().apply {
            put("environmentId", meta.environmentId)
            put("distribution", meta.distribution)
            put("version", meta.version)
            put("architecture", meta.architecture)
            put("sourceUrl", meta.sourceUrl)
            put("expectedSha256", meta.expectedSha256)
            put("actualSha256", meta.actualSha256 ?: "")
            put("archiveSizeBytes", meta.archiveSizeBytes)
            put("installedSizeBytes", meta.installedSizeBytes)
            put("installTimestamp", meta.installTimestamp)
            put("lastError", meta.lastError ?: "")
        }
        file.writeText(json.toString(2), Charsets.UTF_8)
    }

    private fun readMetadata(file: File, def: LinuxEnvironmentDefinition): LinuxEnvironmentMetadata {
        return runCatching {
            val json = JSONObject(file.readText(Charsets.UTF_8))
            LinuxEnvironmentMetadata(
                environmentId = json.optString("environmentId", def.id),
                distribution = json.optString("distribution", def.distribution),
                version = json.optString("version", def.version),
                architecture = json.optString("architecture", def.architecture),
                sourceUrl = json.optString("sourceUrl", def.sourceUrl),
                expectedSha256 = json.optString("expectedSha256", def.expectedSha256),
                actualSha256 = json.optString("actualSha256").takeIf { it.isNotBlank() },
                archiveSizeBytes = json.optLong("archiveSizeBytes", 0L),
                installedSizeBytes = json.optLong("installedSizeBytes", 0L),
                installTimestamp = json.optLong("installTimestamp", 0L),
                lastError = json.optString("lastError").takeIf { it.isNotBlank() }
            )
        }.getOrElse {
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
}
