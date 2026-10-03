package com.example.angi.bridge

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class PhoneBridgeConfigTest {
    @Test fun defaultsDoNotAuthorizeRemoteCommands() {
        val config = PhoneBridgeConfig()
        assertFalse(config.allowShell)
        assertFalse(config.allowCodex)
    }

    @Test fun rejectsInsecureOrCredentialBearingRelayUrls() {
        listOf("http://example.com", "https://token@example.com", "https://example.com/path",
            "https://example.com?q=token", "https://example.com#token", "https:///missing").forEach { url ->
            assertThrows(Exception::class.java) { PhoneBridgeConfig(url, "w".repeat(40)).validate() }
        }
        assertThrows(Exception::class.java) { PhoneBridgeConfig("https://example.com", "short").validate() }
        assertThrows(Exception::class.java) { PhoneBridgeConfig("https://example.com", " ".repeat(40)).validate() }
    }

    @Test fun configurationPersistsAndCanBeRevoked() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val prefs = PhoneBridgePreferences(context)
        prefs.clear()
        val config = PhoneBridgeConfig("https://relay.example.com", "w".repeat(40), allowShell = true)
        prefs.save(config)
        assertEquals(config, PhoneBridgePreferences(context).load())
        prefs.clear()
        assertEquals(PhoneBridgeConfig(), prefs.load())
    }

    @Test fun workerIsPackagedAsAnAsset() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val source = context.assets.open("phone_bridge/bridge.py").bufferedReader().use { it.readText() }
        assertTrue(source.contains("class Worker:"))
        assertTrue(source.contains("class NoRedirect"))
    }
}
