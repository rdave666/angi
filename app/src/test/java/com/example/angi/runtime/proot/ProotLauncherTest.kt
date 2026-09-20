package com.example.angi.runtime.proot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ProotLauncherTest {

    @Test
    fun testBuildArgsConstruction() {
        val launcher = ProotLauncher(
            prootPath = "/data/data/com.example/lib/libproot.so",
            libDir = "/data/data/com.example/files/linux-sandbox",
            rootfsPath = "/data/data/com.example/files/linux-sandbox/rootfs",
            tmpPath = "/data/data/com.example/files/linux-sandbox/tmp",
            binds = listOf("/data/data/com.example/files/linux-sandbox/workspace" to "/workspace"),
            extraArgs = listOf("--link2symlink", "-L"),
            env = mapOf("DEBIAN_FRONTEND" to "noninteractive")
        )

        val args = launcher.buildArgs("echo 'hello'", "/root")

        assertEquals("/data/data/com.example/lib/libproot.so", args[0])
        assertTrue(args.contains("--link2symlink"))
        assertTrue(args.contains("-L"))
        assertTrue(args.contains("--rootfs=/data/data/com.example/files/linux-sandbox/rootfs"))
        assertTrue(args.contains("--bind=/dev"))
        assertTrue(args.contains("--bind=/proc"))
        assertTrue(args.contains("--bind=/sys"))
        assertTrue(args.contains("--bind=/data/data/com.example/files/linux-sandbox/workspace:/workspace"))
        assertTrue(args.contains("--bind=/data/data/com.example/files/linux-sandbox/tmp:/tmp"))
        assertTrue(args.contains("-0"))
        assertTrue(args.contains("-w"))
        assertTrue(args.contains("/root"))
        assertTrue(args.contains("/bin/sh"))
        assertTrue(args.contains("-c"))
        assertTrue(args.contains("echo 'hello'"))
    }

    @Test
    fun testBuildEnvConstruction() {
        val launcher = ProotLauncher(
            prootPath = "/data/data/com.example/lib/libproot.so",
            libDir = "/data/data/com.example/files/linux-sandbox",
            rootfsPath = "/data/data/com.example/files/linux-sandbox/rootfs",
            tmpPath = "/data/data/com.example/files/linux-sandbox/tmp",
            extraArgs = DebianDistroSpec.prootArgs,
            env = DebianDistroSpec.env
        )

        val env = launcher.buildEnv(mapOf("CUSTOM_VAR" to "test1234"))
        val envMap = env.associate {
            val parts = it.split('=', limit = 2)
            parts[0] to parts[1]
        }

        assertEquals("/root", envMap["HOME"])
        assertEquals("xterm-256color", envMap["TERM"])
        assertEquals("C.UTF-8", envMap["LANG"])
        assertEquals("/data/data/com.example/files/linux-sandbox", envMap["LD_LIBRARY_PATH"])
        assertEquals("/data/data/com.example/files/linux-sandbox/tmp", envMap["PROOT_TMP_DIR"])
        assertEquals("/data/data/com.example/lib/libproot-loader.so", envMap["PROOT_LOADER"])
        assertEquals("noninteractive", envMap["DEBIAN_FRONTEND"])
        assertEquals("test1234", envMap["CUSTOM_VAR"])
    }

    @Test
    fun testDebianDistroSpecDefaults() {
        assertEquals("debian-bookworm", DebianDistroSpec.ID)
        assertEquals("rootfs.tar.xz", DebianDistroSpec.ARCHIVE_NAME)
        assertTrue(DebianDistroSpec.prootArgs.contains("--link2symlink"))
        assertTrue(DebianDistroSpec.prootArgs.contains("-L"))
        assertTrue(DebianDistroSpec.basePackages.contains("bash"))
        assertTrue(DebianDistroSpec.basePackages.contains("curl"))
        assertTrue(DebianDistroSpec.basePackages.contains("git"))
        assertTrue(DebianDistroSpec.basePackages.contains("python3"))
        assertTrue(DebianDistroSpec.basePackages.contains("nodejs"))
    }

    @Test
    fun testProotResultTruncation() {
        val longOutput = "A".repeat(50_000)
        val result = ProotResult(
            success = false,
            stdout = "",
            stderr = longOutput,
            exitCode = 1
        )
        val failure = result.failureDetail(200)
        assertTrue(failure.length <= 202)
        assertTrue(failure.startsWith("…"))
    }
}
