package com.example.angi.runtime.proot

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap

class LinuxSandboxManager(
    context: Context
) {
    val paths = LinuxPaths(context)
    val installer = LinuxInstaller(paths)

    private val _installStep = MutableStateFlow<InstallStep?>(null)
    val installStep: StateFlow<InstallStep?> = _installStep.asStateFlow()

    private val conversationShells = ConcurrentHashMap<String, PersistentSandboxShell>()

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

    suspend fun install(onStepProgress: ((InstallStep) -> Unit)? = null): Result<Unit> {
        return installer.installDebian { step ->
            _installStep.value = step
            onStepProgress?.invoke(step)
        }
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
}
