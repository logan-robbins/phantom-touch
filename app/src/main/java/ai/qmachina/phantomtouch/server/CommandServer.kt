package ai.qmachina.phantomtouch.server

import ai.qmachina.phantomtouch.executor.CommandExecutor
import ai.qmachina.phantomtouch.model.Command
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject

/**
 * Embedded HTTP server on port 8080.
 * 
 * Endpoints:
 *   POST /execute   — run a single command or batch
 *   GET  /health    — check service status
 *   GET  /screen    — quick screenshot (convenience)
 * 
 * The server runs in the CaptureService foreground service so Android 
 * doesn't kill it. Access via:
 *   - localhost:8080 (adb forward)
 *   - LAN IP:8080 (same network)
 *   - Tailscale IP:8080 (remote)
 * 
 * Security note: This has no auth. For production, add a bearer token
 * check or restrict to Tailscale-only access. A simple token impl:
 *   - Set token in app settings
 *   - Check Authorization: Bearer <token> header on every request
 */
class CommandServer(
    private val executor: CommandExecutor,
    port: Int = 8080,
    private val authToken: String = ""
) : NanoHTTPD(port) {

    override fun serve(session: IHTTPSession): Response {
        // Bearer token check if configured
        if (authToken.isNotEmpty()) {
            val header = session.headers["authorization"] ?: ""
            if (header != "Bearer $authToken") {
                return jsonResponse(Response.Status.UNAUTHORIZED, JSONObject().apply {
                    put("error", "Invalid or missing auth token")
                })
            }
        }

        return try {
            when {
                session.uri == "/health" && session.method == Method.GET -> {
                    handleHealth()
                }
                session.uri == "/execute" && session.method == Method.POST -> {
                    handleExecute(session)
                }
                session.uri == "/screen" && session.method == Method.GET -> {
                    handleQuickScreenshot(session)
                }
                else -> {
                    jsonResponse(Response.Status.NOT_FOUND, JSONObject().apply {
                        put("error", "Not found. Endpoints: POST /execute, GET /health, GET /screen")
                    })
                }
            }
        } catch (e: Exception) {
            jsonResponse(Response.Status.INTERNAL_ERROR, JSONObject().apply {
                put("error", e.message ?: "Unknown error")
                put("type", e.javaClass.simpleName)
            })
        }
    }

    private fun handleHealth(): Response {
        val gestureAvailable = ai.qmachina.phantomtouch.service.GestureService.instance != null
        val json = JSONObject().apply {
            put("status", if (gestureAvailable) "ready" else "degraded")
            put("gestureService", gestureAvailable)
            put("server", true)
        }
        return jsonResponse(Response.Status.OK, json)
    }

    private fun handleExecute(session: IHTTPSession): Response {
        // Read POST body via NanoHTTPD's built-in parser (handles chunked/partial reads)
        val bodyMap = HashMap<String, String>()
        session.parseBody(bodyMap)
        val body = bodyMap["postData"] ?: ""
        if (body.isBlank()) {
            return jsonResponse(Response.Status.BAD_REQUEST, JSONObject().apply {
                put("error", "Request body is empty or missing")
            })
        }

        val json = try {
            JSONObject(body)
        } catch (e: org.json.JSONException) {
            return jsonResponse(Response.Status.BAD_REQUEST, JSONObject().apply {
                put("error", "Invalid JSON: ${e.message}")
            })
        }
        val commands = Command.parseBatch(json)

        // Execute (blocking on the server thread — fine for NanoHTTPD's thread pool)
        val results = runBlocking { executor.executeBatch(commands) }

        val response = if (results.size == 1) {
            // Single command: return flat result
            results[0].toJson()
        } else {
            // Batch: return array of results
            JSONObject().apply {
                put("batch", true)
                put("count", results.size)
                put("results", JSONArray().apply {
                    results.forEach { put(it.toJson()) }
                })
                // Surface any failures at top level
                put("allSucceeded", results.all { it.success })
            }
        }

        return jsonResponse(Response.Status.OK, response)
    }

    private fun handleQuickScreenshot(session: IHTTPSession): Response {
        val scale = session.parms["scale"]?.toFloatOrNull() ?: 0.5f
        val commands = listOf(Command.Screenshot(scale))
        val results = runBlocking { executor.executeBatch(commands) }
        return jsonResponse(Response.Status.OK, results[0].toJson())
    }

    private fun jsonResponse(status: Response.Status, json: JSONObject): Response {
        return newFixedLengthResponse(
            status,
            "application/json",
            json.toString()
        ).apply {
            addHeader("Access-Control-Allow-Origin", "*")
        }
    }
}
