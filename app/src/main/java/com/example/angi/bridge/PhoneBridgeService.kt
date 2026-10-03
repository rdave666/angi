package com.example.angi.bridge

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.example.AngiApp
import com.example.MainActivity
import com.example.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File

data class PhoneBridgeState(
    val isRunning: Boolean = false,
    val isConnected: Boolean = false,
    val message: String = "Disconnected"
)

/** User-started outbound worker in the existing Debian sandbox; never launches a second rootfs. */
class PhoneBridgeService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var worker: Process? = null
    @Volatile private var stopping = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopWorker()
            stopSelf()
            return START_NOT_STICKY
        }
        if (_state.value.isRunning) return START_NOT_STICKY
        stopping = false
        foreground()
        _state.value = PhoneBridgeState(isRunning = true, message = "Starting phone bridge")
        scope.launch {
            try {
                val config = PhoneBridgePreferences(this@PhoneBridgeService).load()
                config.validate()
                val manager = AngiApp.instance.linuxSandboxManager
                check(manager.isInstalled()) { "Install Debian in Diagnostics before connecting" }
                val launcher = manager.defaultLauncher
                val prerequisite = launcher.execute("python3 --version", 20)
                check(prerequisite.success) { "Python 3 is required in Debian; install it before connecting" }
                check(!stopping) { "Bridge stopped" }
                val script = File(manager.paths.rootfsDir, "usr/local/share/angi/bridge.py")
                script.parentFile?.mkdirs()
                assets.open("phone_bridge/bridge.py").use { input ->
                    script.outputStream().use { input.copyTo(it) }
                }
                val apiSettings = AngiApp.instance.settingsRepository.settings.value
                val command = buildString {
                    append("exec python3 -u /usr/local/share/angi/bridge.py worker")
                    append(" --workspace /workspace --state /root/.local/state/angi-bridge")
                    append(" --api-url http://127.0.0.1:${apiSettings.apiServerPort}")
                    if (config.allowShell) append(" --allow-shell")
                    if (config.allowCodex) append(" --allow-codex")
                }
                val process = synchronized(this@PhoneBridgeService) {
                    check(!stopping) { "Bridge stopped" }
                    launcher.start(command, "/workspace", mapOf(
                        "ANGI_BRIDGE_URL" to config.relayUrl,
                        "ANGI_BRIDGE_WORKER_TOKEN" to config.workerToken,
                        "ANGI_API_TOKEN" to apiSettings.apiServerApiKey
                    )).also { worker = it }
                }
                // Output contains only bridge connection events, never command output or credentials.
                scope.launch {
                    process.errorStream.bufferedReader().use { reader ->
                        while (reader.readLine() != null) { /* drain without exposing secrets */ }
                    }
                }
                process.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        if (!stopping) runCatching {
                            val event = JSONObject(line).optString("event")
                            _state.value = when (event) {
                                "connected" -> PhoneBridgeState(true, true, "Connected to relay")
                                "executing" -> PhoneBridgeState(true, true, "Running phone job")
                                "disconnected" -> PhoneBridgeState(true, false, "Relay unavailable; reconnecting")
                                else -> _state.value
                            }
                        }
                    }
                }
                val exitCode = process.waitFor()
                if (!stopping) _state.value = PhoneBridgeState(message = "Bridge exited ($exitCode); check relay settings and Debian")
            } catch (e: Exception) {
                if (!stopping) _state.value = PhoneBridgeState(message = e.message ?: "Could not start bridge")
            } finally {
                synchronized(this@PhoneBridgeService) { worker?.destroy(); worker = null }
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        // User must reconnect after app/service termination; no silent remote-control auto-start.
        return START_NOT_STICKY
    }

    private fun foreground() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "ANGI Phone Bridge", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 0, Intent(this, PhoneBridgeService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher).setContentTitle("ANGI phone bridge")
            .setContentText("Outbound remote connection enabled").setContentIntent(open)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Disconnect", stop).setOngoing(true).build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else startForeground(NOTIFICATION_ID, notification)
    }

    private fun stopWorker() {
        synchronized(this) { stopping = true; worker?.destroy() }
        _state.value = PhoneBridgeState()
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    override fun onDestroy() {
        val last = _state.value
        stopWorker()
        if (!last.isRunning) _state.value = last
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_ID = "angi_phone_bridge"
        private const val NOTIFICATION_ID = 2027
        const val ACTION_STOP = "com.example.angi.bridge.STOP"
        private val _state = MutableStateFlow(PhoneBridgeState())
        val state = _state.asStateFlow()

        fun start(context: Context) {
            val intent = Intent(context, PhoneBridgeService::class.java)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) { context.stopService(Intent(context, PhoneBridgeService::class.java)) }
    }
}
