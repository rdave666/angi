package com.example.angi.runtime.proot

import android.os.Build
import java.io.File
import java.io.IOException
import java.net.URL

private const val LXC_INDEX = "https://images.linuxcontainers.org/meta/1.0/index-user"
private const val LXC_BASE = "https://images.linuxcontainers.org"
private const val DEBIAN_RELEASE = "bookworm"

/**
 * Specifications for Debian ARM64 on Qualcomm Snapdragon 8 Gen 2.
 */
object DebianDistroSpec {
    const val ID = "debian-bookworm"
    const val DISPLAY_NAME = "Debian 12 (Bookworm)"
    const val ARCHIVE_NAME = "rootfs.tar.xz"

    fun arch(): String {
        val abi = Build.SUPPORTED_ABIS.firstOrNull().orEmpty()
        return when {
            abi.startsWith("arm64") -> "arm64"
            abi.startsWith("armeabi") -> "armhf"
            abi.startsWith("x86_64") -> "amd64"
            abi.startsWith("x86") -> "i386"
            else -> "arm64"
        }
    }

    /**
     * Finds newest rootfs download URL from LXC index.
     */
    fun rootfsUrls(): List<String> {
        val arch = arch()
        val index = URL(LXC_INDEX).openStream().bufferedReader().use { it.readText() }
        val line = index.lineSequence()
            .filter { it.startsWith("debian;$DEBIAN_RELEASE;$arch;default;") }
            .maxOrNull()
            ?: throw IOException("No Debian $DEBIAN_RELEASE image for $arch in LXC index")
        val path = line.split(';').getOrNull(5)?.trim()?.takeIf { it.isNotEmpty() }
            ?: throw IOException("Malformed LXC index line: $line")
        return listOf(LXC_BASE + path.removeSuffix("/") + "/rootfs.tar.xz")
    }

    fun writeResolvConf(rootfsDir: File) {
        val etc = File(rootfsDir, "etc").apply { mkdirs() }
        val resolvConf = File(etc, "resolv.conf").toPath()
        try {
            if (java.nio.file.Files.isSymbolicLink(resolvConf) || java.nio.file.Files.exists(resolvConf, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                java.nio.file.Files.delete(resolvConf)
            }
        } catch (_: Throwable) {
            File(etc, "resolv.conf").delete()
        }
        java.nio.file.Files.write(
            resolvConf,
            "nameserver 8.8.8.8\nnameserver 1.1.1.1\nnameserver 8.8.4.4\n".toByteArray(Charsets.UTF_8)
        )
    }

    fun configure(rootfsDir: File) {
        // Ensure standard directories required by apt/dpkg
        listOf(
            "var/lib/apt/lists/partial",
            "var/cache/apt/archives/partial",
            "var/lib/dpkg/updates",
            "var/lib/dpkg/info",
            "var/lib/dpkg/alternatives",
            "var/log",
            "run/lock",
            "tmp",
            "workspace",
            "root"
        ).forEach { File(rootfsDir, it).mkdirs() }

        // Setup DNS
        writeResolvConf(rootfsDir)
        val etc = File(rootfsDir, "etc").apply { mkdirs() }
        File(etc, "hosts").writeText("127.0.0.1 localhost\n::1 localhost\n")

        // Force unsafe io for dpkg so writes/fsyncs don't block phone storage
        val dpkgCfgDir = File(rootfsDir, "etc/dpkg/dpkg.cfg.d").apply { mkdirs() }
        File(dpkgCfgDir, "force-unsafe-io").writeText("force-unsafe-io\n")
    }

    /**
     * Mandatory PRoot flags for Debian on Android:
     * --link2symlink and -L are critical to emulate hardlinks without triggering protected_hardlinks failure.
     */
    val prootArgs = listOf("--link2symlink", "-L")

    val env = mapOf(
        "DEBIAN_FRONTEND" to "noninteractive"
    )

    val basePackages = listOf(
        "bash",
        "coreutils",
        "procps",
        "findutils",
        "grep",
        "sed",
        "tar",
        "curl",
        "wget",
        "ca-certificates",
        "git",
        "python3",
        "python3-pip",
        "nodejs",
        "npm",
        "util-linux"
    )
}
