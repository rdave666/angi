package com.example.angi.api

import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class GatewayGuestConfigTest {
    @Test fun provisionWritesPrivateGuestOnlyTokenAndRevokes() {
        val rootfs = Files.createTempDirectory("angi-rootfs-test-").toFile()
        try {
            java.io.File(rootfs, "etc").mkdirs()
            assertTrue(GatewayGuestConfig.provision(rootfs, 18080, "guest-token-'quoted"))
            val script = java.io.File(rootfs, "root/.config/angi/model-gateway.sh")
            assertTrue(script.isFile)
            val text = script.readText()
            assertTrue(text.contains("http://127.0.0.1:18080/v1"))
            assertTrue(text.contains("angi-active-model"))
            assertTrue(text.contains("guest-token-"))
            assertTrue(text.contains("'\\\"'\\\"'"))
            assertFalse(text.contains("provider-secret"))
            assertFalse(script.canExecute())
            GatewayGuestConfig.revoke(rootfs)
            assertFalse(script.exists())
        } finally {
            rootfs.deleteRecursively()
        }
    }

    @Test fun doesNotCreateLinuxInstallationOrFileWithoutPreexistingRootfs() {
        val rootfs = Files.createTempDirectory("angi-empty-test-").toFile()
        try {
            assertFalse(GatewayGuestConfig.provision(rootfs, 8080, "x"))
            assertFalse(java.io.File(rootfs, "root").exists())
        } finally {
            rootfs.deleteRecursively()
        }
    }
}
