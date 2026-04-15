package ai.qmachina.phantomtouch.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject

/**
 * Core gesture engine. Runs as an AccessibilityService so it can:
 * 1. Dispatch arbitrary gestures (tap, swipe, long press) at pixel coordinates
 * 2. Inject text into focused fields
 * 3. Dump the accessibility node tree for structured UI understanding
 * 4. Perform global actions (back, home, recents)
 */
class GestureService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: GestureService? = null
            private set
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) { /* no-op */ }
    override fun onInterrupt() { /* no-op */ }

    // ── Gesture Dispatch ──────────────────────────────────────────────

    /**
     * Dispatch a gesture and suspend until completion.
     * Returns true if the gesture was dispatched successfully.
     */
    suspend fun dispatchGestureAsync(gesture: GestureDescription): Boolean {
        val deferred = CompletableDeferred<Boolean>()
        val dispatched = dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                deferred.complete(true)
            }
            override fun onCancelled(gestureDescription: GestureDescription?) {
                deferred.complete(false)
            }
        }, null)

        if (!dispatched) return false
        return withTimeout(10_000) { deferred.await() }
    }

    /** Tap at exact pixel coordinates */
    suspend fun tap(x: Float, y: Float): Boolean {
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0, 50)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        return dispatchGestureAsync(gesture)
    }

    /** Long press at coordinates with configurable duration */
    suspend fun longPress(x: Float, y: Float, durationMs: Long = 1000): Boolean {
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0, durationMs)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        return dispatchGestureAsync(gesture)
    }

    /**
     * Swipe from (startX, startY) to (endX, endY).
     * durationMs controls speed: lower = faster swipe, higher = slower drag.
     * 
     * For "scroll down medium speed, 50% of screen":
     *   swipe(screenW/2, screenH*0.75, screenW/2, screenH*0.25, 400)
     */
    suspend fun swipe(
        startX: Float, startY: Float,
        endX: Float, endY: Float,
        durationMs: Long = 300
    ): Boolean {
        val path = Path().apply {
            moveTo(startX, startY)
            lineTo(endX, endY)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, durationMs)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        return dispatchGestureAsync(gesture)
    }

    /**
     * Pinch gesture for zoom in/out.
     * startSpan < endSpan = zoom in, startSpan > endSpan = zoom out.
     * Span is the distance between two fingers in pixels.
     */
    suspend fun pinch(
        centerX: Float, centerY: Float,
        startSpan: Float, endSpan: Float,
        durationMs: Long = 400
    ): Boolean {
        val halfStart = startSpan / 2
        val halfEnd = endSpan / 2

        val path1 = Path().apply {
            moveTo(centerX - halfStart, centerY)
            lineTo(centerX - halfEnd, centerY)
        }
        val path2 = Path().apply {
            moveTo(centerX + halfStart, centerY)
            lineTo(centerX + halfEnd, centerY)
        }

        val stroke1 = GestureDescription.StrokeDescription(path1, 0, durationMs)
        val stroke2 = GestureDescription.StrokeDescription(path2, 0, durationMs)
        val gesture = GestureDescription.Builder()
            .addStroke(stroke1)
            .addStroke(stroke2)
            .build()
        return dispatchGestureAsync(gesture)
    }

    /** Public accessor for the active window root node (used by CommandExecutor.findNode) */
    val rootInActiveWindow: AccessibilityNodeInfo?
        get() = super.getRootInActiveWindow()

    // ── Text Input ────────────────────────────────────────────────────

    /**
     * Type text into the currently focused field.
     * Uses ACTION_SET_TEXT which replaces the field content.
     * For append behavior, read current text first and prepend.
     */
    fun typeText(text: String): Boolean {
        val focusedNode = findFocusedEditableNode() ?: return false
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        val result = focusedNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        focusedNode.recycle()
        return result
    }

    fun findFocusedEditableNode(): AccessibilityNodeInfo? {
        // Try input focus first (text fields)
        var node = findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (node != null && node.isEditable) return node
        node?.recycle()

        // Fall back to accessibility focus
        node = findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY)
        if (node != null && node.isEditable) return node
        node?.recycle()

        return null
    }

    // ── Global Actions ────────────────────────────────────────────────

    fun pressBack(): Boolean = performGlobalAction(GLOBAL_ACTION_BACK)
    fun pressHome(): Boolean = performGlobalAction(GLOBAL_ACTION_HOME)
    fun pressRecents(): Boolean = performGlobalAction(GLOBAL_ACTION_RECENTS)

    // ── UI Tree Dump ──────────────────────────────────────────────────

    /**
     * Dump the accessibility tree as structured JSON.
     * Each node includes: className, text, contentDescription, bounds,
     * isClickable, isEditable, isScrollable, viewId, childCount.
     * 
     * The LLM can use this to:
     * 1. Find elements by text/description without vision
     * 2. Get precise bounds for tap coordinates
     * 3. Understand page structure and scrollable containers
     */
    fun dumpTree(): JSONObject {
        val root = rootInActiveWindow ?: return JSONObject().put("error", "no active window")
        val tree = nodeToJson(root)
        root.recycle()
        return tree
    }

    private fun nodeToJson(node: AccessibilityNodeInfo): JSONObject {
        val json = JSONObject()
        json.put("class", node.className?.toString() ?: "")
        node.text?.let { json.put("text", it.toString()) }
        node.contentDescription?.let { json.put("desc", it.toString()) }
        node.viewIdResourceName?.let { json.put("id", it) }

        // Bounds in screen coordinates — these are your tap targets
        val rect = android.graphics.Rect()
        node.getBoundsInScreen(rect)
        json.put("bounds", JSONObject().apply {
            put("left", rect.left)
            put("top", rect.top)
            put("right", rect.right)
            put("bottom", rect.bottom)
            put("centerX", rect.centerX())
            put("centerY", rect.centerY())
        })

        // Interaction flags — tells the model what's actionable
        val flags = JSONArray()
        if (node.isClickable) flags.put("clickable")
        if (node.isLongClickable) flags.put("longClickable")
        if (node.isEditable) flags.put("editable")
        if (node.isScrollable) flags.put("scrollable")
        if (node.isCheckable) flags.put("checkable")
        if (node.isChecked) flags.put("checked")
        if (node.isFocused) flags.put("focused")
        if (node.isSelected) flags.put("selected")
        if (flags.length() > 0) json.put("flags", flags)

        // Recurse children
        val childCount = node.childCount
        if (childCount > 0) {
            val children = JSONArray()
            for (i in 0 until childCount) {
                val child = node.getChild(i) ?: continue
                children.put(nodeToJson(child))
                child.recycle()
            }
            json.put("children", children)
        }

        return json
    }
}
