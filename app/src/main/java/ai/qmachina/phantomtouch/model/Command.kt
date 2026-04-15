package ai.qmachina.phantomtouch.model

import org.json.JSONArray
import org.json.JSONObject

/**
 * PhantomTouch Command Protocol v2
 *
 * Supports:
 *   - Raw gestures (tap, swipe, longPress)
 *   - Text input (type, keyEvent)
 *   - Navigation (back, home, recents)
 *   - App control (launchApp)
 *   - Chrome/browser (openUrl, chromeNewTab, chromeTabs)
 *   - Perception (screenshot, dumpTree, screenInfo)
 *   - Flow control (delay)
 *   - Batching (sequential execution)
 */

sealed class Command {
    // ── Gestures ──────────────────────────────────────────────────
    data class Tap(val x: Float, val y: Float) : Command()
    data class DoubleTap(val x: Float, val y: Float, val intervalMs: Long = 100) : Command()
    data class LongPress(val x: Float, val y: Float, val durationMs: Long = 1000) : Command()
    data class Swipe(
        val startX: Float, val startY: Float,
        val endX: Float, val endY: Float,
        val durationMs: Long = 300
    ) : Command()
    data class Pinch(
        val centerX: Float, val centerY: Float,
        val startSpan: Float, val endSpan: Float,
        val durationMs: Long = 400
    ) : Command()

    // ── Text input ────────────────────────────────────────────────
    data class Type(val text: String) : Command()
    data class KeyEvent(val keyCode: Int) : Command()
    object ClearField : Command()

    // ── Navigation ────────────────────────────────────────────────
    object Back : Command()
    object Home : Command()
    object Recents : Command()

    // ── App control ───────────────────────────────────────────────
    data class LaunchApp(val packageName: String) : Command()

    // ── Chrome / Browser ──────────────────────────────────────────
    /** Open a URL in Chrome (or default browser). Uses ACTION_VIEW intent — 
     *  most reliable way to navigate without UI tapping. */
    data class OpenUrl(val url: String, val newTab: Boolean = false) : Command()

    /** Open Chrome with a blank new tab. */
    object ChromeNewTab : Command()

    /** Send Chrome-specific keystrokes:
     *  "addressBar" → Ctrl+L (focus address bar)
     *  "refresh"    → Ctrl+R
     *  "back"       → Alt+Left
     *  "forward"    → Alt+Right
     *  "closeTab"   → Ctrl+W
     *  "newTab"     → Ctrl+T
     *  "find"       → Ctrl+F  */
    data class ChromeAction(val action: String) : Command()

    // ── Perception ────────────────────────────────────────────────
    data class Screenshot(val scale: Float = 0.5f, val quality: Int = 80) : Command()
    object DumpTree : Command()
    object ScreenInfo : Command()

    /** Focused dump — find nodes matching a text/desc query.
     *  Much smaller payload than full tree dump. */
    data class FindNode(val query: String, val clickable: Boolean = false) : Command()

    // ── Flow control ──────────────────────────────────────────────
    data class Delay(val ms: Long) : Command()

    /** Wait for a specific UI element to appear (polling).
     *  Useful after launching Chrome — wait for address bar to be ready. */
    data class WaitForNode(
        val query: String,
        val timeoutMs: Long = 5000,
        val pollMs: Long = 300
    ) : Command()

    companion object {
        fun fromJson(json: JSONObject): Command {
            return when (val action = json.getString("action")) {
                "tap" -> Tap(
                    x = json.getDouble("x").toFloat(),
                    y = json.getDouble("y").toFloat()
                )
                "doubleTap" -> DoubleTap(
                    x = json.getDouble("x").toFloat(),
                    y = json.getDouble("y").toFloat(),
                    intervalMs = json.optLong("intervalMs", 100)
                )
                "longPress" -> LongPress(
                    x = json.getDouble("x").toFloat(),
                    y = json.getDouble("y").toFloat(),
                    durationMs = json.optLong("durationMs", 1000)
                )
                "swipe" -> Swipe(
                    startX = json.getDouble("startX").toFloat(),
                    startY = json.getDouble("startY").toFloat(),
                    endX = json.getDouble("endX").toFloat(),
                    endY = json.getDouble("endY").toFloat(),
                    durationMs = json.optLong("durationMs", 300)
                )
                "pinch" -> Pinch(
                    centerX = json.getDouble("centerX").toFloat(),
                    centerY = json.getDouble("centerY").toFloat(),
                    startSpan = json.getDouble("startSpan").toFloat(),
                    endSpan = json.getDouble("endSpan").toFloat(),
                    durationMs = json.optLong("durationMs", 400)
                )
                "type" -> Type(text = json.getString("text"))
                "keyEvent" -> KeyEvent(keyCode = json.getInt("keyCode"))
                "clearField" -> ClearField
                "back" -> Back
                "home" -> Home
                "recents" -> Recents
                "launchApp" -> LaunchApp(packageName = json.getString("package"))
                "openUrl" -> OpenUrl(
                    url = json.getString("url"),
                    newTab = json.optBoolean("newTab", false)
                )
                "chromeNewTab" -> ChromeNewTab
                "chromeAction" -> ChromeAction(action = json.getString("chromeAction"))
                "screenshot" -> Screenshot(
                    scale = json.optDouble("scale", 0.5).toFloat(),
                    quality = json.optInt("quality", 80)
                )
                "dumpTree" -> DumpTree
                "screenInfo" -> ScreenInfo
                "findNode" -> FindNode(
                    query = json.getString("query"),
                    clickable = json.optBoolean("clickable", false)
                )
                "delay" -> Delay(ms = json.getLong("ms"))
                "waitForNode" -> WaitForNode(
                    query = json.getString("query"),
                    timeoutMs = json.optLong("timeoutMs", 5000),
                    pollMs = json.optLong("pollMs", 300)
                )
                else -> throw IllegalArgumentException("Unknown action: $action")
            }
        }

        fun parseBatch(json: JSONObject): List<Command> {
            return if (json.has("batch")) {
                val arr = json.getJSONArray("batch")
                (0 until arr.length()).map { fromJson(arr.getJSONObject(it)) }
            } else {
                listOf(fromJson(json))
            }
        }
    }
}

data class CommandResult(
    val action: String,
    val success: Boolean,
    val data: Any? = null,
    val error: String? = null
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("action", action)
        put("success", success)
        data?.let { put("result", it) }
        error?.let { put("error", it) }
    }
}
