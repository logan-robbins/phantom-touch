package ai.qmachina.phantomtouch.relay

import ai.qmachina.phantomtouch.executor.CommandExecutor
import ai.qmachina.phantomtouch.model.Command
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import kotlinx.coroutines.*
import okhttp3.*
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * WebSocket client that connects OUTBOUND to the relay server on the VM.
 *
 * The phone initiates the connection — no inbound ports, no port forwarding,
 * no Tailscale required. Works over cellular, home WiFi, anywhere.
 *
 * Protocol:
 *   1. Phone connects to ws://<vm-ip>:9091
 *   2. If token is set, sends {"token": "..."} and waits for auth_ok
 *   3. Sends {"type": "device_info", "payload": {...}} with screen dimensions
 *   4. Waits for commands: {"id": "uuid", "type": "command", "payload": {...}}
 *   5. Executes via CommandExecutor, sends back: {"id": "uuid", "type": "response", "payload": {...}}
 *   6. Sends heartbeat every 30s to keep connection alive
 *
 * Auto-reconnects on disconnect with exponential backoff.
 */
class RelayClient(
    private val executor: CommandExecutor,
    private val relayUrl: String,        // ws://vm-ip:9091
    private val authToken: String = "",
    private val deviceName: String = "phantom-touch"
) {
    companion object {
        private const val TAG = "RelayClient"
        private const val HEARTBEAT_INTERVAL_MS = 30_000L
        private const val MAX_BACKOFF_MS = 60_000L
        private const val INITIAL_BACKOFF_MS = 1_000L
    }

    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)  // no read timeout for WS
        .pingInterval(20, TimeUnit.SECONDS)     // OkHttp-level ping
        .build()

    private var webSocket: WebSocket? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var backoffMs = INITIAL_BACKOFF_MS
    private var isRunning = false

    // Handler for heartbeat
    private val heartbeatThread = HandlerThread("relay-heartbeat").apply { start() }
    private val heartbeatHandler = Handler(heartbeatThread.looper)
    private val heartbeatRunnable = object : Runnable {
        override fun run() {
            webSocket?.send(JSONObject().apply {
                put("type", "heartbeat")
                put("timestamp", System.currentTimeMillis())
            }.toString())
            heartbeatHandler.postDelayed(this, HEARTBEAT_INTERVAL_MS)
        }
    }

    fun start() {
        isRunning = true
        connect()
    }

    fun stop() {
        isRunning = false
        heartbeatHandler.removeCallbacks(heartbeatRunnable)
        webSocket?.close(1000, "Client stopping")
        webSocket = null
        scope.cancel()
    }

    private fun connect() {
        if (!isRunning) return

        Log.i(TAG, "Connecting to relay: $relayUrl")

        val request = Request.Builder()
            .url(relayUrl)
            .build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {

            override fun onOpen(ws: WebSocket, response: Response) {
                Log.i(TAG, "Connected to relay")
                backoffMs = INITIAL_BACKOFF_MS

                // Auth if token is set
                if (authToken.isNotEmpty()) {
                    ws.send(JSONObject().apply {
                        put("token", authToken)
                    }.toString())
                    // auth_ok will be handled in onMessage
                } else {
                    sendDeviceInfo(ws)
                    startHeartbeat()
                }
            }

            override fun onMessage(ws: WebSocket, text: String) {
                try {
                    val msg = JSONObject(text)
                    when (msg.optString("type")) {
                        "auth_ok" -> {
                            Log.i(TAG, "Auth successful")
                            sendDeviceInfo(ws)
                            startHeartbeat()
                        }

                        "auth_error" -> {
                            Log.e(TAG, "Auth failed: ${msg.optString("error")}")
                            ws.close(4001, "Auth failed")
                        }

                        "command" -> {
                            val id = msg.getString("id")
                            val payload = msg.getJSONObject("payload")
                            handleCommand(ws, id, payload)
                        }

                        "heartbeat_ack" -> {
                            // Connection alive
                        }

                        else -> {
                            Log.w(TAG, "Unknown message type: ${msg.optString("type")}")
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error processing message: ${e.message}", e)
                }
            }

            override fun onClosing(ws: WebSocket, code: Int, reason: String) {
                Log.i(TAG, "Relay closing: $code $reason")
                ws.close(code, reason)
            }

            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                Log.i(TAG, "Relay closed: $code $reason")
                heartbeatHandler.removeCallbacks(heartbeatRunnable)
                scheduleReconnect()
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "Relay connection failed: ${t.message}")
                heartbeatHandler.removeCallbacks(heartbeatRunnable)
                scheduleReconnect()
            }
        })
    }

    private fun sendDeviceInfo(ws: WebSocket) {
        // Send device info so the relay knows what's connected
        val info = JSONObject().apply {
            put("type", "device_info")
            put("payload", JSONObject().apply {
                put("name", deviceName)
                put("model", android.os.Build.MODEL)
                put("manufacturer", android.os.Build.MANUFACTURER)
                put("sdk", android.os.Build.VERSION.SDK_INT)
                put("android", android.os.Build.VERSION.RELEASE)
            })
        }
        ws.send(info.toString())
    }

    private fun handleCommand(ws: WebSocket, requestId: String, payload: JSONObject) {
        scope.launch {
            try {
                val commands = Command.parseBatch(payload)
                val results = executor.executeBatch(commands)

                val response = if (results.size == 1) {
                    results[0].toJson()
                } else {
                    JSONObject().apply {
                        put("batch", true)
                        put("count", results.size)
                        put("results", org.json.JSONArray().apply {
                            results.forEach { put(it.toJson()) }
                        })
                        put("allSucceeded", results.all { it.success })
                    }
                }

                val envelope = JSONObject().apply {
                    put("id", requestId)
                    put("type", "response")
                    put("payload", response)
                }

                ws.send(envelope.toString())

            } catch (e: Exception) {
                val errorEnvelope = JSONObject().apply {
                    put("id", requestId)
                    put("type", "response")
                    put("payload", JSONObject().apply {
                        put("success", false)
                        put("error", e.message ?: "Unknown error")
                    })
                }
                ws.send(errorEnvelope.toString())
            }
        }
    }

    private fun startHeartbeat() {
        heartbeatHandler.removeCallbacks(heartbeatRunnable)
        heartbeatHandler.postDelayed(heartbeatRunnable, HEARTBEAT_INTERVAL_MS)
    }

    private fun scheduleReconnect() {
        if (!isRunning) return

        Log.i(TAG, "Reconnecting in ${backoffMs}ms...")
        scope.launch {
            delay(backoffMs)
            backoffMs = (backoffMs * 2).coerceAtMost(MAX_BACKOFF_MS)
            connect()
        }
    }
}
