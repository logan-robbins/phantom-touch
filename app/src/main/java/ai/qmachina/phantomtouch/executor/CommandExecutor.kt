package ai.qmachina.phantomtouch.executor

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.KeyEvent
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

        val humanizer = gesture.humanizer
        val typewriter = gesture.typewriter

        return try {
            when (command) {
                // ── Gestures ──────────────────────────────────────
                is Command.Tap -> {
                    CommandResult("tap", humanizer.tap(command.x, command.y),
                        data = jsonOf("x" to command.x, "y" to command.y))
                }

                is Command.DoubleTap -> {
                    CommandResult("doubleTap",
                        humanizer.doubleTap(command.x, command.y, command.intervalMs))
                }

                is Command.LongPress -> {
                    CommandResult("longPress",
                        humanizer.longPress(command.x, command.y, command.durationMs))
                }

                is Command.Swipe -> {
                    CommandResult("swipe",
                        humanizer.swipe(command.startX, command.startY,
                            command.endX, command.endY, command.durationMs),
                        data = jsonOf(
                            "startX" to command.startX, "startY" to command.startY,
                            "endX" to command.endX, "endY" to command.endY,
                            "durationMs" to command.durationMs
                        ))
                }

                is Command.Pinch -> {
                    val ok = humanizer.pinch(
                        command.centerX, command.centerY,
                        command.startSpan, command.endSpan,
                        command.durationMs
                    )
                    CommandResult("pinch", ok)
                }

                // ── Text input ────────────────────────────────────
                is Command.Type -> {
                    CommandResult("type", typewriter.type(command.text),
                        data = jsonOf("text" to command.text))
                }

                is Command.KeyEvent -> {
                    CommandResult("keyEvent", typewriter.sendKey(command.keyCode),
                        data = jsonOf("keyCode" to command.keyCode))
                }

                is Command.ClearField -> {
                    CommandResult("clearField", typewriter.clear())
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
                    val ok = executeChromeAction(typewriter, command.action)
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

                // ── Accessibility actions ────────────────────────
                is Command.ClickNode -> {
                    val node = findFirstMatchingNode(gesture, command.query, command.index)
                        ?: return CommandResult("clickNode", false,
                            error = "No clickable node matching '${command.query}'")
                    val rect = android.graphics.Rect()
                    node.getBoundsInScreen(rect)
                    val ok = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    val text = node.text?.toString() ?: ""
                    node.recycle()
                    CommandResult("clickNode", ok, data = jsonOf(
                        "query" to command.query, "clickedText" to text,
                        "centerX" to rect.centerX(), "centerY" to rect.centerY()))
                }

                is Command.LongClickNode -> {
                    val node = findFirstMatchingNode(gesture, command.query, command.index)
                        ?: return CommandResult("longClickNode", false,
                            error = "No node matching '${command.query}'")
                    val ok = node.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)
                    node.recycle()
                    CommandResult("longClickNode", ok, data = jsonOf("query" to command.query))
                }

                is Command.SetNodeText -> {
                    val node = findFirstMatchingNode(gesture, command.query, index = 0, requireEditable = true)
                        ?: return CommandResult("setNodeText", false,
                            error = "No editable node matching '${command.query}'")
                    val ok = typewriter.typeInto(node, command.text)
                    node.recycle()
                    CommandResult("setNodeText", ok, data = jsonOf(
                        "query" to command.query, "textLength" to command.text.length))
                }

                is Command.ScrollNode -> {
                    val scrollable = findScrollableNode(gesture, command.query)
                        ?: return CommandResult("scrollNode", false,
                            error = "No scrollable container found" +
                                if (command.query.isNotEmpty()) " matching '${command.query}'" else "")
                    val action = if (command.direction == "down" || command.direction == "right")
                        AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                    else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
                    var ok = true
                    repeat(command.times) {
                        if (ok) {
                            ok = scrollable.performAction(action)
                            if (command.times > 1) delay(300) // pause between scrolls
                        }
                    }
                    scrollable.recycle()
                    CommandResult("scrollNode", ok, data = jsonOf(
                        "direction" to command.direction, "times" to command.times))
                }

                is Command.DismissNode -> {
                    if (command.query.isNotEmpty()) {
                        val node = findFirstMatchingNode(gesture, command.query, index = 0)
                            ?: return CommandResult("dismissNode", false,
                                error = "No node matching '${command.query}'")
                        val ok = node.performAction(AccessibilityNodeInfo.ACTION_DISMISS)
                        node.recycle()
                        CommandResult("dismissNode", ok, data = jsonOf("query" to command.query))
                    } else {
                        // Try to dismiss any focused dialog
                        val ok = gesture.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE)
                        CommandResult("dismissNode", ok)
                    }
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
            android.util.Log.e("CommandExecutor", "Command failed: ${command::class.simpleName}", e)
            CommandResult(command::class.simpleName ?: "unknown", false,
                error = "${e.javaClass.simpleName}: ${e.message}")
        }
    }

    // ── Chrome helpers ────────────────────────────────────────────

    /**
     * Chrome honors soft-IME chords delivered via the accessibility
     * InputConnection. Apps that gate shortcuts on SOURCE_KEYBOARD
     * (hardware) may ignore them — see §4.6 of the fidelity spec.
     */
    private suspend fun executeChromeAction(
        typewriter: ai.qmachina.phantomtouch.fidelity.Typewriter,
        action: String,
    ): Boolean {
        val (meta, keyCode) = when (action) {
            "addressBar" -> KeyEvent.META_CTRL_ON to KeyEvent.KEYCODE_L
            "refresh"    -> KeyEvent.META_CTRL_ON to KeyEvent.KEYCODE_R
            "back"       -> KeyEvent.META_ALT_ON  to KeyEvent.KEYCODE_DPAD_LEFT
            "forward"    -> KeyEvent.META_ALT_ON  to KeyEvent.KEYCODE_DPAD_RIGHT
            "closeTab"   -> KeyEvent.META_CTRL_ON to KeyEvent.KEYCODE_W
            "newTab"     -> KeyEvent.META_CTRL_ON to KeyEvent.KEYCODE_T
            "find"       -> KeyEvent.META_CTRL_ON to KeyEvent.KEYCODE_F
            else -> return false
        }
        return typewriter.sendChord(meta, keyCode)
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
     * Find the first matching node and return it (NOT recycled — caller must recycle).
     * Used by accessibility action commands (clickNode, setNodeText, etc.)
     */
    private fun findFirstMatchingNode(
        gesture: GestureService,
        query: String,
        index: Int = 0,
        requireEditable: Boolean = false
    ): AccessibilityNodeInfo? {
        val root = gesture.rootInActiveWindow ?: return null
        val matches = mutableListOf<AccessibilityNodeInfo>()
        collectMatchingNodes(root, query.lowercase(), matches, requireEditable)
        root.recycle()
        return if (index < matches.size) {
            // Recycle all except the one we're returning
            matches.forEachIndexed { i, n -> if (i != index) n.recycle() }
            matches[index]
        } else {
            matches.forEach { it.recycle() }
            null
        }
    }

    private fun collectMatchingNodes(
        node: AccessibilityNodeInfo,
        query: String,
        results: MutableList<AccessibilityNodeInfo>,
        requireEditable: Boolean
    ) {
        val text = node.text?.toString()?.lowercase() ?: ""
        val desc = node.contentDescription?.toString()?.lowercase() ?: ""
        val id = node.viewIdResourceName?.lowercase() ?: ""

        val matches = text.contains(query) || desc.contains(query) || id.contains(query)
        val passEditable = !requireEditable || node.isEditable

        if (matches && passEditable) {
            // Obtain a fresh reference (not recycled with parent)
            results.add(AccessibilityNodeInfo.obtain(node))
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectMatchingNodes(child, query, results, requireEditable)
            child.recycle()
        }
    }

    /**
     * Find a scrollable container, optionally matching a query.
     * Returns the node (NOT recycled — caller must recycle).
     */
    private fun findScrollableNode(
        gesture: GestureService,
        query: String
    ): AccessibilityNodeInfo? {
        val root = gesture.rootInActiveWindow ?: return null
        val result = findScrollableInTree(root, query.lowercase())
        root.recycle()
        return result
    }

    private fun findScrollableInTree(
        node: AccessibilityNodeInfo,
        query: String
    ): AccessibilityNodeInfo? {
        if (node.isScrollable) {
            if (query.isEmpty()) {
                return AccessibilityNodeInfo.obtain(node)
            }
            val text = node.text?.toString()?.lowercase() ?: ""
            val desc = node.contentDescription?.toString()?.lowercase() ?: ""
            val id = node.viewIdResourceName?.lowercase() ?: ""
            if (text.contains(query) || desc.contains(query) || id.contains(query)) {
                return AccessibilityNodeInfo.obtain(node)
            }
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findScrollableInTree(child, query)
            child.recycle()
            if (found != null) return found
        }
        return null
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
