package ai.qmachina.phantomtouch.fidelity

import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import ai.qmachina.phantomtouch.service.GestureService
import kotlin.math.hypot
import kotlin.random.Random

/**
 * Gesture synthesis with light humanization.
 *
 * Signatures mirror the old [GestureService] gesture methods so the
 * [ai.qmachina.phantomtouch.executor.CommandExecutor] call sites are a
 * drop-in replacement. All methods are stateless per-call — params plus
 * the seeded [rng] fully determine the emitted path.
 *
 * Tap uses ACTION_CLICK-first resolution (TalkBack parity) and falls back
 * to a jittered dispatchGesture when no clickable ancestor is found.
 * Swipe uses a single cubic Bezier with perpendicular control-point offset
 * (~8% of stroke length) for a gentle arc.
 *
 * Every dispatched MotionEvent carries the platform-stamped AT flags
 * (FLAG_IS_ACCESSIBILITY_EVENT=0x800, deviceId=-1, pressure=1.0, size=1.0,
 * toolType=TOOL_TYPE_UNKNOWN). These are identical to TalkBack and Voice
 * Access injections and are not maskable from userland.
 */
class Humanizer(
    private val gestureService: GestureService,
    private val rng: Random,
) {

    suspend fun tap(x: Float, y: Float): Boolean {
        if (performClickOnNode(x, y)) return true
        return dispatchTap(x, y)
    }

    suspend fun doubleTap(x: Float, y: Float, intervalMs: Long): Boolean {
        val ok1 = tap(x, y)
        val gap = Distributions.uniform(rng, 80.0, 200.0).toLong()
        val wait = if (intervalMs in DOUBLE_TAP_MIN_MS..DOUBLE_TAP_MAX_MS) intervalMs else gap
        kotlinx.coroutines.delay(wait)
        val ok2 = tap(x, y)
        return ok1 && ok2
    }

    suspend fun longPress(x: Float, y: Float, durationMs: Long): Boolean {
        val (jx, jy) = jitteredPoint(x, y)
        val duration = jitterDuration(durationMs)
        val path = Path().apply { moveTo(jx, jy) }
        val stroke = GestureDescription.StrokeDescription(path, 0, duration)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        return gestureService.dispatchGestureAsync(gesture)
    }

    suspend fun swipe(
        sx: Float,
        sy: Float,
        ex: Float,
        ey: Float,
        durationMs: Long,
    ): Boolean {
        val (jsx, jsy) = jitteredPoint(sx, sy)
        val (jex, jey) = jitteredPoint(ex, ey)
        val duration = jitterDuration(durationMs)

        val dx = jex - jsx
        val dy = jey - jsy
        val len = hypot(dx, dy).coerceAtLeast(1f)
        // Perpendicular unit vector (rotated 90°). Side (±) is fixed — both
        // control points sit on the same side of the A→B line.
        val side = if (rng.nextBoolean()) 1f else -1f
        val perpX = -dy / len * side
        val perpY = dx / len * side
        val offset = 0.08f * len

        val c1x = (jsx + dx * 1f / 3f + perpX * offset).clampToDisplayX()
        val c1y = (jsy + dy * 1f / 3f + perpY * offset).clampToDisplayY()
        val c2x = (jsx + dx * 2f / 3f + perpX * offset).clampToDisplayX()
        val c2y = (jsy + dy * 2f / 3f + perpY * offset).clampToDisplayY()

        val path = Path().apply {
            moveTo(jsx.clampToDisplayX(), jsy.clampToDisplayY())
            cubicTo(c1x, c1y, c2x, c2y, jex.clampToDisplayX(), jey.clampToDisplayY())
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, duration)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        return gestureService.dispatchGestureAsync(gesture)
    }

    suspend fun pinch(
        cx: Float,
        cy: Float,
        startSpan: Float,
        endSpan: Float,
        durationMs: Long,
    ): Boolean {
        val duration = jitterDuration(durationMs)
        val halfStart = startSpan / 2f
        val halfEnd = endSpan / 2f

        val p1 = strokePath(cx - halfStart, cy, cx - halfEnd, cy)
        val p2 = strokePath(cx + halfStart, cy, cx + halfEnd, cy)

        val s1 = GestureDescription.StrokeDescription(p1, 0, duration)
        val s2 = GestureDescription.StrokeDescription(p2, 0, duration)
        val gesture = GestureDescription.Builder()
            .addStroke(s1)
            .addStroke(s2)
            .build()
        return gestureService.dispatchGestureAsync(gesture)
    }

    // ── Internals ─────────────────────────────────────────────────

    private fun performClickOnNode(x: Float, y: Float): Boolean {
        val root = gestureService.rootInActiveWindow ?: return false
        val leaf = deepestContaining(root, x.toInt(), y.toInt()) ?: return false
        val leafBounds = Rect()
        leaf.getBoundsInScreen(leafBounds)
        val leafArea = leafBounds.width().toLong() * leafBounds.height().toLong()

        var cursor: AccessibilityNodeInfo? = leaf
        var depth = 0
        while (cursor != null && depth < MAX_ANCESTOR_DEPTH) {
            if (isClickable(cursor)) {
                val ancBounds = Rect()
                cursor.getBoundsInScreen(ancBounds)
                val ancArea = ancBounds.width().toLong() * ancBounds.height().toLong()
                val withinBudget = leafArea == 0L ||
                    ancArea.toDouble() <= leafArea.toDouble() * AREA_BUDGET
                if (withinBudget) {
                    return cursor.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                }
                // First clickable ancestor was too large — abandon a11y path.
                return false
            }
            cursor = cursor.parent
            depth++
        }
        return false
    }

    private fun isClickable(node: AccessibilityNodeInfo): Boolean {
        if (node.isClickable) return true
        return node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_CLICK }
    }

    private fun deepestContaining(
        node: AccessibilityNodeInfo,
        x: Int,
        y: Int,
    ): AccessibilityNodeInfo? {
        val rect = Rect()
        node.getBoundsInScreen(rect)
        if (!rect.contains(x, y)) return null
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val deeper = deepestContaining(child, x, y)
            if (deeper != null) return deeper
        }
        return node
    }

    private suspend fun dispatchTap(x: Float, y: Float): Boolean {
        val (jx, jy) = jitteredPoint(x, y)
        val duration = Distributions.uniform(rng, TAP_MIN_DURATION_MS, TAP_MAX_DURATION_MS).toLong()
        val path = Path().apply { moveTo(jx.clampToDisplayX(), jy.clampToDisplayY()) }
        val stroke = GestureDescription.StrokeDescription(path, 0, duration)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        return gestureService.dispatchGestureAsync(gesture)
    }

    private fun strokePath(sx: Float, sy: Float, ex: Float, ey: Float): Path {
        val (jsx, jsy) = jitteredPoint(sx, sy)
        val (jex, jey) = jitteredPoint(ex, ey)
        return Path().apply {
            moveTo(jsx.clampToDisplayX(), jsy.clampToDisplayY())
            lineTo(jex.clampToDisplayX(), jey.clampToDisplayY())
        }
    }

    private fun jitteredPoint(x: Float, y: Float): Pair<Float, Float> {
        val (dx, dy) = Distributions.gaussian2D(rng, COORD_SIGMA_PX)
        return (x + dx) to (y + dy)
    }

    private fun jitterDuration(durationMs: Long): Long {
        val factor = Distributions.uniform(rng, 0.9, 1.1)
        return (durationMs * factor).toLong().coerceAtLeast(1L)
    }

    private fun Float.clampToDisplayX(): Float {
        val w = gestureService.resources.displayMetrics.widthPixels
        return this.coerceIn(1f, (w - 1).toFloat())
    }

    private fun Float.clampToDisplayY(): Float {
        val h = gestureService.resources.displayMetrics.heightPixels
        return this.coerceIn(1f, (h - 1).toFloat())
    }

    companion object {
        private const val COORD_SIGMA_PX = 15f
        private const val TAP_MIN_DURATION_MS = 60.0
        private const val TAP_MAX_DURATION_MS = 90.0
        private const val AREA_BUDGET = 1.25
        private const val MAX_ANCESTOR_DEPTH = 8
        // ViewConfiguration.DOUBLE_TAP_MIN_TIME = 40ms, DOUBLE_TAP_TIMEOUT = 300ms.
        private const val DOUBLE_TAP_MIN_MS = 40L
        private const val DOUBLE_TAP_MAX_MS = 300L
    }
}
