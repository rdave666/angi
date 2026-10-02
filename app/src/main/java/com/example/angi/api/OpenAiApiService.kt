package com.example.angi.api

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.example.AngiApp
import com.example.MainActivity
import com.example.R
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class ApiServerState(
    val isRunning: Boolean = false,
    val bindHost: String = "127.0.0.1",
    val port: Int = 8080,
    val isLanMode: Boolean = false,
    val apiKey: String = "",
    val lanIp: String? = null,
    val endpointUrl: String = "http://127.0.0.1:8080/v1",
    val isEndpointAvailable: Boolean = true,
    val errorMessage: String? = null
)

class OpenAiApiService : Service() {

    private var server: OpenAiHttpServer? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: ACTION_START
        if (action == ACTION_STOP) {
            stopServer()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        startServer()
        return START_STICKY
    }

    override fun onDestroy() {
        stopServer()
        super.onDestroy()
    }

    private fun startServer() {
        val settingsRepo = AngiApp.instance.settingsRepository
        val settings = settingsRepo.settings.value
        val host = if (settings.apiServerBindLan) "0.0.0.0" else "127.0.0.1"
        val port = settings.apiServerPort
        val apiKey = settings.apiServerApiKey
        val requireAuth = settings.apiServerBindLan

        val lanIp = if (settings.apiServerBindLan) NetworkUtils.getLocalIpv4Address() else null
        val notifText = when {
            !settings.apiServerBindLan -> "Listening on http://127.0.0.1:$port/v1"
            lanIp != null -> "Listening on http://$lanIp:$port/v1"
            else -> "Listening on port $port (LAN address unavailable)"
        }
        val notification = buildNotification(notifText)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        try {
            server?.stop()
            val newServer = OpenAiHttpServer(
                host = host,
                port = port,
                apiKey = apiKey,
                requireAuth = requireAuth,
                inferenceEngine = AngiApp.instance.inferenceEngine,
                modelRepository = AngiApp.instance.modelRepository
            )
            val actualPort = newServer.start()
            server = newServer

            val clientEndpoint = when {
                !settings.apiServerBindLan -> "http://127.0.0.1:$actualPort/v1"
                lanIp != null -> "http://$lanIp:$actualPort/v1"
                else -> "LAN address unavailable"
            }

            _serverState.value = ApiServerState(
                isRunning = true,
                bindHost = host,
                port = actualPort,
                isLanMode = settings.apiServerBindLan,
                apiKey = apiKey,
                lanIp = lanIp,
                endpointUrl = clientEndpoint,
                isEndpointAvailable = (!settings.apiServerBindLan || lanIp != null),
                errorMessage = null
            )
            settingsRepo.updateSettings(settings.copy(isApiServerEnabled = true))
            Log.i("OpenAiApiService", "OpenAiApiService started successfully on $clientEndpoint")
        } catch (e: Exception) {
            Log.e("OpenAiApiService", "Failed to start OpenAiHttpServer: ${e.message}", e)
            _serverState.value = ApiServerState(
                isRunning = false,
                bindHost = host,
                port = port,
                isLanMode = settings.apiServerBindLan,
                apiKey = apiKey,
                lanIp = lanIp,
                endpointUrl = if (!settings.apiServerBindLan) "http://127.0.0.1:$port/v1" else (lanIp?.let { "http://$it:$port/v1" } ?: "LAN address unavailable"),
                isEndpointAvailable = false,
                errorMessage = e.message ?: "Failed to start server"
            )
            // Reconcile persisted state truthfully
            settingsRepo.updateSettings(settings.copy(isApiServerEnabled = false))
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun stopServer() {
        server?.stop()
        server = null
        val current = _serverState.value
        _serverState.value = current.copy(isRunning = false, errorMessage = null)
        try {
            val settingsRepo = AngiApp.instance.settingsRepository
            val currentSettings = settingsRepo.settings.value
            if (currentSettings.isApiServerEnabled) {
                settingsRepo.updateSettings(currentSettings.copy(isApiServerEnabled = false))
            }
        } catch (_: Throwable) {}
        Log.i("OpenAiApiService", "OpenAiApiService stopped.")
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "ANGI API Server",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Foreground service notification for embedded OpenAI-compatible HTTP API"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(statusText: String): Notification {
        val launchIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            launchIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val stopIntent = Intent(this, OpenAiApiService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("ANGI OpenAI API Server")
            .setContentText(statusText)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pendingIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopPendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    companion object {
        const val CHANNEL_ID = "angi_api_service_channel"
        const val NOTIFICATION_ID = 2026
        const val ACTION_START = "com.example.angi.api.ACTION_START"
        const val ACTION_STOP = "com.example.angi.api.ACTION_STOP"

        private val _serverState = MutableStateFlow(ApiServerState())
        val serverState: StateFlow<ApiServerState> = _serverState.asStateFlow()

        fun start(context: Context) {
            val intent = Intent(context, OpenAiApiService::class.java).apply {
                action = ACTION_START
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, OpenAiApiService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }
}
