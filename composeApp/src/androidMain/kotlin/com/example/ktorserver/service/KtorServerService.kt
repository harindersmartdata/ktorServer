package com.example.ktorserver.service

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
import androidx.core.app.NotificationCompat
import com.example.ktorserver.KtorServerApp
import com.example.ktorserver.MainActivity
import com.example.ktorserver.R
import com.example.ktorserver.server.KtorServer
import com.example.ktorserver.util.localIpv4Address
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Hosts the Ktor engine in a foreground service.
 *
 * An Activity is the wrong home for a server: the process gets killed as soon as the
 * user leaves the app. A foreground service with an ongoing notification keeps the
 * socket alive while the app is in the background.
 */
class KtorServerService : Service() {

    private val server by lazy {
        val app = application as KtorServerApp
        KtorServer(
            repository = app.userRepository,
            store = app.replicationStore,
            changeLog = app.changeLogDao,
            writeDao = app.writeDao,
            promotions = app.promotionDao,
            promotionCoordinator = app.promotionCoordinator,
            secrets = { app.secrets.load() },
            replicator = app.replicator,
            peerMonitor = app.peerMonitor,
            port = KtorServer.DEFAULT_PORT,
        )
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopServer()
                return START_NOT_STICKY
            }

            else -> startServer()
        }
        return START_STICKY
    }

    private fun startServer() {
        if (_isRunning.value) return

        startAsForeground()
        runCatching { server.start() }
            .onSuccess {
                _isRunning.value = true
                _lastError.value = null
            }
            .onFailure { error ->
                _lastError.value = error.message ?: error::class.java.simpleName
                _isRunning.value = false
                stopSelf()
            }
    }

    private fun stopServer() {
        server.stop()
        _isRunning.value = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun startAsForeground() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(): Notification {
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )

        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, KtorServerService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )

        val host = localIpv4Address() ?: "localhost"

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Ktor server running")
            .setContentText("http://$host:${KtorServer.DEFAULT_PORT}")
            .setSmallIcon(R.drawable.ic_server)
            .setOngoing(true)
            .setContentIntent(openApp)
            .addAction(0, "Stop", stop)
            .build()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Local HTTP server",
            NotificationManager.IMPORTANCE_LOW,
        ).apply { description = "Shown while the embedded Ktor server is listening" }

        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    override fun onDestroy() {
        if (_isRunning.value) server.stop()
        _isRunning.value = false
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL_ID = "ktor_server"
        private const val NOTIFICATION_ID = 1001
        const val ACTION_STOP = "com.example.ktorserver.STOP"

        private val _isRunning = MutableStateFlow(false)
        val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

        private val _lastError = MutableStateFlow<String?>(null)
        val lastError: StateFlow<String?> = _lastError.asStateFlow()

        fun start(context: Context) {
            val intent = Intent(context, KtorServerService::class.java)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, KtorServerService::class.java).setAction(ACTION_STOP)
            context.startService(intent)
        }
    }
}
