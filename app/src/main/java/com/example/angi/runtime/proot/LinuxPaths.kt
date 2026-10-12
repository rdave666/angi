package com.example.angi.runtime.proot

import android.content.Context
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

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

    val nativeLibDir: String get() = appContext.applicationInfo.nativeLibraryDir?.takeIf { it.isNotBlank() }
        ?: File(appContext.filesDir, "lib").absolutePath
    val prootPath: String get() = File(nativeLibDir, "libproot.so").absolutePath
    val tallocTarget: File get() = File(root, "libtalloc.so.2")
    val libDir: String get() = root.absolutePath

    /**
     * Workspace directory mounted inside guest Linux.
     * /workspace on guest binds to filesDir/linux-sandbox/workspace on host.
     */
    val workspaceDir: File get() = File(root, "workspace")

    fun ensureLayout() {
        // Creating an empty active rootfs here obscures an interrupted/never-started install.
        // Only a successfully staged installation is promoted into rootfsDir.
        listOf(root, tmpDir, workspaceDir).forEach { it.mkdirs() }
    }

    fun ensureMountPoints(targetRootfs: File = rootfsDir) {
        listOf("root", "tmp", "workspace", "dev", "proc", "sys", "etc")
            .forEach { File(targetRootfs, it).mkdirs() }
    }

    fun copyLibtalloc() {
        if (tallocTarget.exists()) return
        val source = File(nativeLibDir, "libtalloc.so")
        if (source.exists()) {
            source.copyTo(tallocTarget, overwrite = true)
        }
    }

    /**
     * A previous build could lose the single 'install' marker while the rootfs survived.
     * Recovery requires Debian + installed base packages, not simply an empty directory.
     * Never remove or overwrite files because the marker is missing.
     */
    fun hasRecoverableRootfs(): Boolean =
        File(rootfsDir, "etc/debian_version").isFile &&
            (File(rootfsDir, "usr/bin/bash").isFile || File(rootfsDir, "bin/bash").isFile) &&
            File(rootfsDir, "usr/bin/python3").isFile &&
            File(rootfsDir, "var/lib/dpkg/status").isFile

    fun hasRootfsData(): Boolean = rootfsDir.listFiles()?.isNotEmpty() == true

    fun isInstalled(): Boolean {
        if (!rootfsDir.isDirectory) return false
        val markerPresent = markerFile.isFile &&
            runCatching { markerFile.useLines { lines -> lines.any { it == "STATUS=INSTALLED" } } }
                .getOrDefault(false)
        return markerPresent || hasRecoverableRootfs()
    }

    fun writeMarker(distroId: String) {
        root.mkdirs()
        val temp = File.createTempFile(".install-", ".tmp", root)
        try {
            temp.writeText("STATUS=INSTALLED\nDISTRO=$distroId\nTIMESTAMP=${System.currentTimeMillis()}\n")
            Files.move(temp.toPath(), markerFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
        } finally {
            temp.delete()
        }
    }

    fun deleteInstall() {
        markerFile.delete()
        rootfsDir.deleteRecursively()
        tmpDir.deleteRecursively()
    }
}
