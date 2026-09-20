package com.example.angi.runtime.proot

import android.content.Context
import java.io.File

/**
 * Storage layout and paths for PRoot-based Linux user space.
 * Follows Kai's proven filesystem layout:
 * - rootfs in filesDir/linux-sandbox/rootfs
 * - tmp in filesDir/linux-sandbox/tmp
 * - libproot.so dynamically resolved from applicationInfo.nativeLibraryDir
 * - libtalloc.so copied to filesDir/linux-sandbox/libtalloc.so.2 so dynamic linker finds it
 */
class LinuxPaths(
    context: Context,
    val dirName: String = "linux-sandbox"
) {
    private val appContext = context.applicationContext
    val root: File = File(appContext.filesDir, dirName)
    val rootfsDir: File get() = File(root, "rootfs")
    val tmpDir: File get() = File(root, "tmp")
    val markerFile: File get() = File(root, "install")

    val nativeLibDir: String get() = appContext.applicationInfo.nativeLibraryDir
    val prootPath: String get() = File(nativeLibDir, "libproot.so").absolutePath
    val tallocTarget: File get() = File(root, "libtalloc.so.2")
    val libDir: String get() = root.absolutePath

    /**
     * Workspace directory mounted inside guest Linux.
     * /workspace on guest binds to filesDir/linux-sandbox/workspace on host.
     */
    val workspaceDir: File get() = File(root, "workspace")

    fun ensureLayout() {
        listOf(root, rootfsDir, tmpDir, workspaceDir).forEach { it.mkdirs() }
    }

    fun ensureMountPoints() {
        File(rootfsDir, "root").mkdirs()
        File(rootfsDir, "tmp").mkdirs()
        File(rootfsDir, "workspace").mkdirs()
        File(rootfsDir, "dev").mkdirs()
        File(rootfsDir, "proc").mkdirs()
        File(rootfsDir, "sys").mkdirs()
        File(rootfsDir, "etc").mkdirs()
    }

    fun copyLibtalloc() {
        if (tallocTarget.exists()) return
        val source = File(nativeLibDir, "libtalloc.so")
        if (source.exists()) {
            source.copyTo(tallocTarget, overwrite = true)
        }
    }

    fun isInstalled(): Boolean {
        if (!rootfsDir.isDirectory) return false
        if (!markerFile.isFile) return false
        val content = runCatching { markerFile.readText() }.getOrNull() ?: return false
        return content.contains("INSTALLED")
    }

    fun writeMarker(distroId: String) {
        root.mkdirs()
        markerFile.writeText("STATUS=INSTALLED\nDISTRO=$distroId\nTIMESTAMP=${System.currentTimeMillis()}\n")
    }

    fun deleteInstall() {
        markerFile.delete()
        rootfsDir.deleteRecursively()
        tmpDir.deleteRecursively()
    }
}
