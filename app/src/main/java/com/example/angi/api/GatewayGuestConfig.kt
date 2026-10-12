package com.example.angi.api

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Exposes the Android model gateway to the *existing* Debian rootfs.
 * The file contains only a scoped loopback gateway token, NEVER the provider API key.
 * It is not automatically sourced by shell startup or leaked to all child processes.
 */
object GatewayGuestConfig {
    const val ACTIVE_MODEL = "angi-active-model"
    const val GUEST_PATH = "/root/.config/angi/model-gateway.sh"

    fun provision(rootfsDir: File, port: Int, guestToken: String): Boolean {
        if (!rootfsDir.isDirectory || !File(rootfsDir, "etc").isDirectory ||
            port !in 1..65535 || guestToken.isBlank()) return false

        val dir = File(rootfsDir, "root/.config/angi")
        check(dir.isDirectory || dir.mkdirs()) { "Cannot create Debian gateway config directory" }
        val file = File(dir, "model-gateway.sh")
        val temp = File.createTempFile(".model-gateway-", ".tmp", dir)
        try {
            val contents = buildString {
                appendLine("# ANGI generated - source this explicitly for Debian coding agents.")
                appendLine("# This key works only on the phone loopback interface.")
                appendLine("export OPENAI_BASE_URL='http://127.0.0.1:$port/v1'")
                appendLine("export OPENAI_API_KEY=${quote(guestToken)}")
                appendLine("export ANGI_MODEL='$ACTIVE_MODEL'")
            }
            // Restrict permissions *before* making the generated file visible.
            check(temp.setReadable(false, false) && temp.setReadable(true, true) &&
                temp.setWritable(false, false) && temp.setWritable(true, true)) {
                "Cannot secure Debian gateway config"
            }
            temp.writeText(contents, Charsets.UTF_8)
            Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            return true
        } finally {
            temp.delete()
        }
    }

    fun revoke(rootfsDir: File) {
        File(rootfsDir, "root/.config/angi/model-gateway.sh").delete()
    }

    private fun quote(value: String): String = "'" + value.replace("'", "'\"'\"'") + "'"
}
