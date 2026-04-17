package ai.qmachina.phantomtouch.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.accessibilityservice.InputMethod
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import ai.qmachina.phantomtouch.fidelity.Humanizer
import ai.qmachina.phantomtouch.fidelity.Typewriter
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import kotlin.random.Random

/**
 * Thin [AccessibilityService] wrapper.
 *
 * Owns: lifecycle, raw [dispatchGesture] primitive, [rootInActiveWindow]
 * re-export (via inherited API), global actions (back/home/recents),
 * [dumpTree], [onCreateInputMethod] override.
 *
 * All fidelity logic — gesture synthesis, per-keystroke IME — lives in
 * [ai.qmachina.phantomtouch.fidelity.Humanizer] and
 * [ai.qmachina.phantomtouch.fidelity.Typewriter].
 */
class GestureService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: GestureService? = null
            private set
    }

    /** Seeded per service instance; reseeded on every service connect. */
    private val rng: Random = Random(System.nanoTime())

    val humanizer: Humanizer by lazy { Humanizer(this, rng) }
    val typewriter: Typewriter by lazy { Typewriter(this, rng) }

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

    override fun onCreateInputMethod(): InputMethod {
        return Typewriter.PhantomInputMethod(this)
    }

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

    // ── Global Actions ────────────────────────────────────────────────

    fun pressBack(): Boolean = performGlobalAction(GLOBAL_ACTION_BACK)
    fun pressHome(): Boolean = performGlobalAction(GLOBAL_ACTION_HOME)
    fun pressRecents(): Boolean = performGlobalAction(GLOBAL_ACTION_RECENTS)

    // ── UI Tree Dump ──────────────────────────────────────────────────

    /**
     * Dump the accessibility tree as structured JSON.
     * Each node includes: className, text, contentDescription, bounds,
     * isClickable, isEditable, isScrollable, viewId, childCount.
     */
    fun dumpTree(): JSONObject {
        val root = rootInActiveWindow ?: return JSONObject().put("error", "no active window")
        return nodeToJson(root)
    }

    private fun nodeToJson(node: AccessibilityNodeInfo): JSONObject {
        val json = JSONObject()
        json.put("class", node.className?.toString() ?: "")
        node.text?.let { json.put("text", it.toString()) }
        node.contentDescription?.let { json.put("desc", it.toString()) }
        node.viewIdResourceName?.let { json.put("id", it) }

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

        val childCount = node.childCount
        if (childCount > 0) {
            val children = JSONArray()
            for (i in 0 until childCount) {
                val child = node.getChild(i) ?: continue
                children.put(nodeToJson(child))
            }
            json.put("children", children)
        }

        return json
    }
}
