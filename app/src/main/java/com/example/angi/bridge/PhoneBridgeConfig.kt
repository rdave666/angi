package com.example.angi.bridge

import android.content.Context
import java.net.URI

data class PhoneBridgeConfig(
    val relayUrl: String = "",
    val workerToken: String = "",
    val allowShell: Boolean = false,
    val allowCodex: Boolean = false
) {
    fun validate() {
        val uri = URI(relayUrl)
        require(uri.scheme == "https" && !uri.host.isNullOrBlank()) { "Enter the relay HTTPS URL" }
        require(uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null &&
            (uri.path.isNullOrEmpty() || uri.path == "/")) { "Use a relay URL without credentials or a path" }
        require(workerToken.length >= 32 && workerToken.none { it.isWhitespace() }) {
            "Enter a worker token of at least 32 characters without spaces"
        }
    }
}

class PhoneBridgePreferences(context: Context) {
    private val prefs = context.getSharedPreferences("angi_phone_bridge", Context.MODE_PRIVATE)

    fun load() = PhoneBridgeConfig(
        relayUrl = prefs.getString("relay_url", "").orEmpty(),
        workerToken = prefs.getString("worker_token", "").orEmpty(),
        allowShell = prefs.getBoolean("allow_shell", false),
        allowCodex = prefs.getBoolean("allow_codex", false)
    )

    fun save(config: PhoneBridgeConfig) {
        config.validate()
        check(prefs.edit().putString("relay_url", config.relayUrl)
            .putString("worker_token", config.workerToken)
            .putBoolean("allow_shell", config.allowShell)
            .putBoolean("allow_codex", config.allowCodex).commit()) { "Could not save bridge configuration" }
    }

    fun clear() { prefs.edit().clear().apply() }
}
