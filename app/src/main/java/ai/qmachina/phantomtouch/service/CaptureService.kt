package ai.qmachina.phantomtouch.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import ai.qmachina.phantomtouch.executor.CommandExecutor
import ai.qmachina.phantomtouch.relay.RelayClient
import ai.qmachina.phantomtouch.server.CommandServer

/**
 * Foreground service that:
 * 1. Holds the MediaProjection (screenshot capture)
 * 2. Runs the local HTTP CommandServer (:8080, for LAN/ADB use)
 * 3. Runs the RelayClient (outbound WebSocket to VM, for remote use)
 *
 * The relay is the key for remote operation: the phone connects OUT to the VM,
 * so no inbound ports or VPN needed.
 */
class CaptureService : Service() {

    companion object {
        const val CHANNEL_ID = "phantom_touch_channel"
        const val NOTIFICATION_ID = 1
        const val EXTRA_RESULT_CODE = "resultCode"
        const val EXTRA_DATA = "data"
        const val EXTRA_RELAY_URL = "relayUrl"
        const val EXTRA_RELAY_TOKEN = "relayToken"
        const val SERVER_PORT = 8080

        var instance: CaptureService? = null
            private set
    }

    private var screenCapture: ScreenCapture? = null
    private var commandServer: CommandServer? = null
    private var relayClient: RelayClient? = null

    fun hasProjection(): Boolean = screenCapture != null
    fun isServerRunning(): Boolean = commandServer != null
    fun isRelayConnected(): Boolean = relayClient != null

    override fun onCreate() {
        super.onCreate()
        instance = this
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = buildNotification(intent)
        startForeground(NOTIFICATION_ID, notification)

        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, -1) ?: -1
        val data = intent?.getParcelableExtra<Intent>(EXTRA_DATA)
        val relayUrl = intent?.getStringExtra(EXTRA_RELAY_URL) ?: ""
        val relayToken = intent?.getStringExtra(EXTRA_RELAY_TOKEN) ?: ""

        android.util.Log.i("PhantomTouch", "onStartCommand: resultCode=$resultCode data=${data != null} relayUrl=$relayUrl")

        if (resultCode == android.app.Activity.RESULT_OK && data != null) {
            screenCapture = ScreenCapture(this).apply {
                initialize(resultCode, data)
            }

            val executor = CommandExecutor(this, screenCapture!!)

            // Always start local HTTP server (useful for LAN/ADB debugging)
            try {
                commandServer = CommandServer(executor, SERVER_PORT, authToken = relayToken).apply {
                    start()
                }
                android.util.Log.i("PhantomTouch", "Local HTTP server on :$SERVER_PORT")
            } catch (e: Exception) {
                android.util.Log.e("PhantomTouch", "Failed to start CommandServer", e)
            }

            // Start relay client if URL is configured
            if (relayUrl.isNotBlank()) {
                relayClient = RelayClient(
                    executor = executor,
                    relayUrl = relayUrl,
                    authToken = relayToken,
                    deviceName = "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}"
                ).apply {
                    start()
                }
                android.util.Log.i("PhantomTouch", "Relay client connecting to $relayUrl")
            }
        }

        return START_STICKY
    }

    override fun onDestroy() {
        relayClient?.stop()
        commandServer?.stop()
        screenCapture?.release()
        instance = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "PhantomTouch Active",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "PhantomTouch is running and accepting commands"
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(intent: Intent?): Notification {
        val relayUrl = intent?.getStringExtra(EXTRA_RELAY_URL) ?: ""
        val subtitle = if (relayUrl.isNotBlank()) {
            "Local :$SERVER_PORT + Relay → ${relayUrl.take(40)}"
        } else {
            "Local :$SERVER_PORT"
        }

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("PhantomTouch Active")
                .setContentText(subtitle)
                .setSmallIcon(android.R.drawable.ic_menu_compass)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setContentTitle("PhantomTouch Active")
                .setContentText(subtitle)
                .setSmallIcon(android.R.drawable.ic_menu_compass)
                .build()
        }
    }
}
