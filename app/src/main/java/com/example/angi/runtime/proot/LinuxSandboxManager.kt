package com.example.angi.runtime.proot

import android.content.Context
import com.example.angi.domain.environment.LinuxEnvironment
import com.example.angi.domain.environment.LinuxEnvironmentDefinition
import com.example.angi.domain.environment.LinuxEnvironmentManager
import com.example.angi.domain.environment.LinuxEnvironmentMetadata
import com.example.angi.domain.environment.LinuxEnvironmentStatus
import com.example.angi.domain.environment.LinuxRwSelfTestResult
import com.example.angi.domain.environment.PinnedLinuxEnvironments
import com.example.angi.runtime.linux.LinuxPathResolver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Unified Linux Environment and PRoot Sandbox Manager.
 * Single source of truth for PRoot Debian ARM64 runtime and workspace.
 */
class LinuxSandboxManager(
    context: Context,
    val paths: LinuxPaths = LinuxPaths(context),
    val installer: LinuxInstaller = LinuxInstaller(paths)
) : LinuxEnvironmentManager {

    private val _installStep = MutableStateFlow<InstallStep?>(null)
    val installStep: StateFlow<InstallStep?> = _installStep.asStateFlow()

    private val _environmentsFlow = MutableStateFlow<List<LinuxEnvironment>>(emptyList())
    private val conversationShells = ConcurrentHashMap<String, PersistentSandboxShell>()

    init {
        paths.ensureLayout()
        refreshEnvironments()
    }

    val defaultLauncher: ProotLauncher
        get() = ProotLauncher(
            prootPath = paths.prootPath,
            libDir = paths.libDir,
            rootfsPath = paths.rootfsDir.absolutePath,
            tmpPath = paths.tmpDir.absolutePath,
            binds = listOf(paths.workspaceDir.absolutePath to "/workspace"),
            extraArgs = DebianDistroSpec.prootArgs,
            env = DebianDistroSpec.env
        )

    val processManager: LinuxProcessManager by lazy {
        LinuxProcessManager(defaultLauncher)
    }

    fun isInstalled(): Boolean = paths.isInstalled()

    fun getPathResolver(): LinuxPathResolver {
        return LinuxPathResolver(paths.root)
    }

    override fun environments(): Flow<List<LinuxEnvironment>> = _environmentsFlow.asStateFlow()

    private fun getMetadataFile(): File = File(paths.root, "metadata.json")

    fun refreshEnvironments() {
        val def = PinnedLinuxEnvironments.DEBIAN_12_ARM64
        val installed = paths.isInstalled()
        val metaFile = getMetadataFile()
        val meta = if (metaFile.exists()) readMetadata(metaFile, def) else null
        val status = when {
            installed -> LinuxEnvironmentStatus.INSTALLED
            _installStep.value != null -> {
                when (_installStep.value) {
                    is InstallStep.ResolveImage, is InstallStep.Download -> LinuxEnvironmentStatus.DOWNLOADING
                    is InstallStep.Extract -> LinuxEnvironmentStatus.EXTRACTING
                    is InstallStep.Complete -> LinuxEnvironmentStatus.INSTALLED
                    else -> LinuxEnvironmentStatus.VERIFYING
                }
            }
            meta?.lastError != null -> LinuxEnvironmentStatus.FAILED
            else -> LinuxEnvironmentStatus.NOT_INSTALLED
        }
        _environmentsFlow.value = listOf(LinuxEnvironment(def, status, meta))
    }

    private fun updateStatus(status: LinuxEnvironmentStatus, lastError: String? = null) {
        val def = PinnedLinuxEnvironments.DEBIAN_12_ARM64
        val metaFile = getMetadataFile()
        val currentMeta = if (metaFile.exists()) readMetadata(metaFile, def) else null
        val meta = (currentMeta ?: LinuxEnvironmentMetadata(
            environmentId = def.id,
            distribution = def.distribution,
            version = def.version,
            architecture = def.architecture,
            sourceUrl = def.sourceUrl,
            expectedSha256 = def.expectedSha256
        )).copy(lastError = lastError)
        _environmentsFlow.value = listOf(LinuxEnvironment(def, status, meta))
    }

    suspend fun install(onStepProgress: ((InstallStep) -> Unit)? = null): Result<Unit> {
        updateStatus(LinuxEnvironmentStatus.DOWNLOADING)
        val result = installer.installDebian { step ->
            _installStep.value = step
            when (step) {
                is InstallStep.ResolveImage, is InstallStep.Download -> updateStatus(LinuxEnvironmentStatus.DOWNLOADING)
                is InstallStep.Extract -> updateStatus(LinuxEnvironmentStatus.EXTRACTING)
                is InstallStep.Configure, is InstallStep.ProotTest, is InstallStep.AptUpdate, is InstallStep.Packages, is InstallStep.Finalize -> updateStatus(LinuxEnvironmentStatus.VERIFYING)
                is InstallStep.Complete -> updateStatus(LinuxEnvironmentStatus.INSTALLED)
            }
            onStepProgress?.invoke(step)
        }
        if (result.isSuccess) {
            val installDetails = result.getOrNull()
            val actualChecksum = installDetails?.sha256 ?: "NOT VERIFIED"
            val archiveSize = installDetails?.archiveSizeBytes ?: 0L
            val meta = LinuxEnvironmentMetadata(
                environmentId = PinnedLinuxEnvironments.DEBIAN_12_ARM64.id,
                distribution = DebianDistroSpec.DISPLAY_NAME,
                version = "12 (Bookworm)",
                architecture = DebianDistroSpec.arch(),
                sourceUrl = installDetails?.downloadUrl ?: PinnedLinuxEnvironments.DEBIAN_12_ARM64.sourceUrl,
                expectedSha256 = "NOT VERIFIED",
                actualSha256 = actualChecksum,
                archiveSizeBytes = archiveSize,
                installedSizeBytes = calculateDirSize(paths.rootfsDir),
                installTimestamp = System.currentTimeMillis(),
                lastError = null
            )
            writeMetadata(getMetadataFile(), meta)
            updateStatus(LinuxEnvironmentStatus.INSTALLED)
        } else {
            val err = result.exceptionOrNull()?.message ?: "Installation failed"
            _installStep.value = null
            updateStatus(LinuxEnvironmentStatus.FAILED, err)
        }
        refreshEnvironments()
        return if (result.isSuccess) Result.success(Unit) else Result.failure(result.exceptionOrNull() ?: Exception("Installation failed"))
    }

    override suspend fun download(definition: LinuxEnvironmentDefinition): Result<Unit> {
        // Installing Debian performs download + extract + configure in unified pipeline
        return install()
    }

    override suspend fun install(environmentId: String): Result<Unit> {
        return install()
    }

    override suspend fun delete(environmentId: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            resetAllShells()
            paths.deleteInstall()
            getMetadataFile().delete()
            _installStep.value = null
            refreshEnvironments()
            Unit
        }
    }

    override suspend fun status(environmentId: String): LinuxEnvironmentStatus {
        refreshEnvironments()
        return _environmentsFlow.value.firstOrNull()?.status ?: LinuxEnvironmentStatus.NOT_INSTALLED
    }

    override suspend fun runSelfTest(environmentId: String): LinuxRwSelfTestResult = withContext(Dispatchers.IO) {
        val resolver = getPathResolver()
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

    fun shellFor(conversationId: String): PersistentSandboxShell {
        return conversationShells.computeIfAbsent(conversationId) {
            PersistentSandboxShell(defaultLauncher)
        }
    }

    fun resetShell(conversationId: String) {
        conversationShells.remove(conversationId)?.reset()
    }

    fun resetAllShells() {
        conversationShells.values.forEach { it.reset() }
        conversationShells.clear()
    }

    private fun calculateDirSize(dir: File): Long {
        if (!dir.exists()) return 0L
        return runCatching {
            dir.walkTopDown().filter { it.isFile }.map { it.length() }.sum()
        }.getOrDefault(0L)
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
