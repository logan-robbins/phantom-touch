package ai.qmachina.phantomtouch.executor

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import ai.qmachina.phantomtouch.model.Command
import ai.qmachina.phantomtouch.model.CommandResult
import ai.qmachina.phantomtouch.service.GestureService
import ai.qmachina.phantomtouch.service.ScreenCapture
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject

/**
 * Executes commands against the device.
 *
 * Design:
 * - Batches execute sequentially (order matters: tap → delay → type)
 * - All coordinates are in native screen pixels
 * - Chrome operations use Intent-based navigation where possible (most reliable),
 *   falling back to gesture-based interaction for everything else
 */
class CommandExecutor(
    private val context: Context,
    private val screenCapture: ScreenCapture
) {

    companion object {
        const val CHROME_PACKAGE = "com.android.chrome"
        const val CHROME_ACTIVITY = "com.google.android.apps.chrome.Main"
    }

    suspend fun executeBatch(commands: List<Command>): List<CommandResult> {
        return commands.map { executeSingle(it) }
    }

    suspend fun executeSingle(command: Command): CommandResult {
        val gesture = GestureService.instance
            ?: return CommandResult("error", false,
                error = "AccessibilityService not running. Enable in Settings → Accessibility → PhantomTouch")

        return try {
            when (command) {
                // ── Gestures ──────────────────────────────────────
                is Command.Tap -> {
                    CommandResult("tap", gesture.tap(command.x, command.y),
                        data = jsonOf("x" to command.x, "y" to command.y))
                }

                is Command.DoubleTap -> {
                    val ok1 = gesture.tap(command.x, command.y)
                    delay(command.intervalMs)
                    val ok2 = gesture.tap(command.x, command.y)
                    CommandResult("doubleTap", ok1 && ok2)
                }

                is Command.LongPress -> {
                    CommandResult("longPress",
                        gesture.longPress(command.x, command.y, command.durationMs))
                }

                is Command.Swipe -> {
                    CommandResult("swipe",
                        gesture.swipe(command.startX, command.startY,
                            command.endX, command.endY, command.durationMs),
                        data = jsonOf(
                            "startX" to command.startX, "startY" to command.startY,
                            "endX" to command.endX, "endY" to command.endY,
                            "durationMs" to command.durationMs
                        ))
                }

                is Command.Pinch -> {
                    val ok = gesture.pinch(
                        command.centerX, command.centerY,
                        command.startSpan, command.endSpan,
                        command.durationMs
                    )
                    CommandResult("pinch", ok)
                }

                // ── Text input ────────────────────────────────────
                is Command.Type -> {
                    CommandResult("type", gesture.typeText(command.text),
                        data = jsonOf("text" to command.text))
                }

                is Command.KeyEvent -> {
                    val p = Runtime.getRuntime().exec(
                        arrayOf("input", "keyevent", command.keyCode.toString()))
                    CommandResult("keyEvent", p.waitFor() == 0)
                }

                is Command.ClearField -> {
                    // Select all + delete
                    Runtime.getRuntime().exec(arrayOf("input", "keyevent", "KEYCODE_MOVE_HOME")).waitFor()
                    Runtime.getRuntime().exec(arrayOf("input", "keyevent", "--longpress", "KEYCODE_SHIFT_LEFT", "KEYCODE_MOVE_END")).waitFor()
                    Runtime.getRuntime().exec(arrayOf("input", "keyevent", "KEYCODE_DEL")).waitFor()
                    CommandResult("clearField", true)
                }

                // ── Navigation ────────────────────────────────────
                is Command.Back -> CommandResult("back", gesture.pressBack())
                is Command.Home -> CommandResult("home", gesture.pressHome())
                is Command.Recents -> CommandResult("recents", gesture.pressRecents())

                // ── App control ───────────────────────────────────
                is Command.LaunchApp -> {
                    val launchIntent = context.packageManager
                        .getLaunchIntentForPackage(command.packageName)
                    if (launchIntent != null) {
                        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        context.startActivity(launchIntent)
                        CommandResult("launchApp", true,
                            data = jsonOf("package" to command.packageName))
                    } else {
                        CommandResult("launchApp", false,
                            error = "Package not found: ${command.packageName}")
                    }
                }

                // ── Chrome / Browser ──────────────────────────────
                is Command.OpenUrl -> {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(normalizeUrl(command.url))).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        if (command.newTab) {
                            // FLAG_ACTIVITY_MULTIPLE_TASK forces a new tab in Chrome
                            addFlags(Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
                        }
                        // Prefer Chrome explicitly
                        setPackage(CHROME_PACKAGE)
                    }
                    try {
                        context.startActivity(intent)
                        CommandResult("openUrl", true,
                            data = jsonOf("url" to command.url, "newTab" to command.newTab))
                    } catch (e: Exception) {
                        // Chrome not installed — fall back to default browser
                        intent.setPackage(null)
                        context.startActivity(intent)
                        CommandResult("openUrl", true,
                            data = jsonOf("url" to command.url, "fallback" to true))
                    }
                }

                is Command.ChromeNewTab -> {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse("about:blank")).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
                        setPackage(CHROME_PACKAGE)
                    }
                    context.startActivity(intent)
                    CommandResult("chromeNewTab", true)
                }

                is Command.ChromeAction -> {
                    val ok = executeChromeAction(command.action)
                    CommandResult("chromeAction", ok, data = jsonOf("action" to command.action))
                }

                // ── Perception ────────────────────────────────────
                is Command.Screenshot -> {
                    val base64 = screenCapture.captureBase64(command.scale, command.quality)
                    if (base64 != null) {
                        val (w, h, _) = screenCapture.getScreenInfo()
                        CommandResult("screenshot", true, data = JSONObject().apply {
                            put("image", base64)
                            put("format", "jpeg")
                            put("encoding", "base64")
                            put("scale", command.scale)
                            put("nativeWidth", w)
                            put("nativeHeight", h)
                            put("imageWidth", (w * command.scale).toInt())
                            put("imageHeight", (h * command.scale).toInt())
                        })
                    } else {
                        CommandResult("screenshot", false,
                            error = "MediaProjection not initialized")
                    }
                }

                is Command.DumpTree -> {
                    CommandResult("dumpTree", true, data = gesture.dumpTree())
                }

                is Command.ScreenInfo -> {
                    val (w, h, d) = screenCapture.getScreenInfo()
                    CommandResult("screenInfo", true, data = jsonOf(
                        "width" to w, "height" to h, "density" to d))
                }

                is Command.FindNode -> {
                    val nodes = findMatchingNodes(gesture, command.query, command.clickable)
                    CommandResult("findNode", true, data = JSONObject().apply {
                        put("query", command.query)
                        put("count", nodes.length())
                        put("nodes", nodes)
                    })
                }

                // ── Flow control ──────────────────────────────────
                is Command.Delay -> {
                    delay(command.ms)
                    CommandResult("delay", true, data = jsonOf("ms" to command.ms))
                }

                is Command.WaitForNode -> {
                    val found = waitForNode(gesture, command.query, command.timeoutMs, command.pollMs)
                    if (found != null) {
                        CommandResult("waitForNode", true, data = found)
                    } else {
                        CommandResult("waitForNode", false,
                            error = "Node '${command.query}' not found within ${command.timeoutMs}ms")
                    }
                }
            }
        } catch (e: Exception) {
            CommandResult(command::class.simpleName ?: "unknown", false, error = e.message)
        }
    }

    // ── Chrome helpers ────────────────────────────────────────────

    private fun executeChromeAction(action: String): Boolean {
        // Chrome responds to standard keyboard shortcuts via `input` shell command
        // These work because Chrome is the foreground app
        val keyCombos = when (action) {
            "addressBar" -> arrayOf("input", "keyevent", "--longpress", "KEYCODE_CTRL_LEFT", "KEYCODE_L")
            "refresh"    -> arrayOf("input", "keyevent", "--longpress", "KEYCODE_CTRL_LEFT", "KEYCODE_R")
            "back"       -> arrayOf("input", "keyevent", "--longpress", "KEYCODE_ALT_LEFT", "KEYCODE_DPAD_LEFT")
            "forward"    -> arrayOf("input", "keyevent", "--longpress", "KEYCODE_ALT_LEFT", "KEYCODE_DPAD_RIGHT")
            "closeTab"   -> arrayOf("input", "keyevent", "--longpress", "KEYCODE_CTRL_LEFT", "KEYCODE_W")
            "newTab"     -> arrayOf("input", "keyevent", "--longpress", "KEYCODE_CTRL_LEFT", "KEYCODE_T")
            "find"       -> arrayOf("input", "keyevent", "--longpress", "KEYCODE_CTRL_LEFT", "KEYCODE_F")
            else -> return false
        }
        return Runtime.getRuntime().exec(keyCombos).waitFor() == 0
    }

    private fun normalizeUrl(url: String): String {
        return if (url.startsWith("http://") || url.startsWith("https://")) url
        else "https://$url"
    }

    // ── Node search helpers ───────────────────────────────────────

    /**
     * Search the accessibility tree for nodes matching text/description.
     * Returns a compact JSON array with bounds (tap targets).
     */
    private fun findMatchingNodes(
        gesture: GestureService,
        query: String,
        clickableOnly: Boolean
    ): JSONArray {
        val results = JSONArray()
        val root = gesture.rootInActiveWindow ?: return results
        searchTree(root, query.lowercase(), clickableOnly, results)
        root.recycle()
        return results
    }

    private fun searchTree(
        node: AccessibilityNodeInfo,
        query: String,
        clickableOnly: Boolean,
        results: JSONArray
    ) {
        val text = node.text?.toString()?.lowercase() ?: ""
        val desc = node.contentDescription?.toString()?.lowercase() ?: ""
        val id = node.viewIdResourceName?.lowercase() ?: ""

        val matches = text.contains(query) || desc.contains(query) || id.contains(query)
        val passClickable = !clickableOnly || node.isClickable

        if (matches && passClickable) {
            val rect = android.graphics.Rect()
            node.getBoundsInScreen(rect)
            results.put(JSONObject().apply {
                put("text", node.text?.toString() ?: "")
                put("desc", node.contentDescription?.toString() ?: "")
                put("class", node.className?.toString() ?: "")
                put("centerX", rect.centerX())
                put("centerY", rect.centerY())
                put("bounds", jsonOf(
                    "left" to rect.left, "top" to rect.top,
                    "right" to rect.right, "bottom" to rect.bottom
                ))
                put("clickable", node.isClickable)
                put("editable", node.isEditable)
            })
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            searchTree(child, query, clickableOnly, results)
            child.recycle()
        }
    }

    /**
     * Poll the UI tree until a node matching the query appears.
     * Returns the node info as JSON, or null on timeout.
     * 
     * Use case: after openUrl, waitForNode("linkedin.com") to confirm page loaded.
     */
    private suspend fun waitForNode(
        gesture: GestureService,
        query: String,
        timeoutMs: Long,
        pollMs: Long
    ): JSONObject? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val nodes = findMatchingNodes(gesture, query, clickableOnly = false)
            if (nodes.length() > 0) {
                return nodes.getJSONObject(0)
            }
            delay(pollMs)
        }
        return null
    }

    // ── Util ──────────────────────────────────────────────────────

    private fun jsonOf(vararg pairs: Pair<String, Any>): JSONObject {
        return JSONObject().apply {
            pairs.forEach { (k, v) -> put(k, v) }
        }
    }
}
