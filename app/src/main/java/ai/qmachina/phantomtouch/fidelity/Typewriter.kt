package ai.qmachina.phantomtouch.fidelity

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.InputMethod
import android.os.SystemClock
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import ai.qmachina.phantomtouch.service.GestureService
import kotlinx.coroutines.delay
import kotlin.random.Random

/**
 * Accessibility IME path. Uses the same [AccessibilityService.InputMethod]
 * channel Voice Access and TalkBack's braille IME use:
 *
 *  - Text: per-character [InputMethod.AccessibilityInputConnection.commitText]
 *    (one [android.text.TextWatcher.onTextChanged] callback per keystroke,
 *    matching soft-keyboard behavior)
 *  - Chords: paired DOWN/UP [KeyEvent]s with
 *    FLAG_SOFT_KEYBOARD | FLAG_KEEP_TOUCH_MODE
 *  - Clear: bounded [getSurroundingText] + [deleteSurroundingText] loop
 *
 * No [AccessibilityNodeInfo.ACTION_SET_TEXT], no `Runtime.exec("input ...")`.
 *
 * Session scope is per-command. Each public method acquires the active
 * [InputMethod.AccessibilityInputConnection] on entry, emits its events,
 * returns. If focus is lost mid-command the connection goes null on the
 * next call and the command returns false — PhantomTouch does not
 * contest focus against Gboard or the user.
 */
class Typewriter(
    private val gestureService: GestureService,
    private val rng: Random,
) {

    /**
     * [InputMethod] subclass that is installed via
     * [GestureService.onCreateInputMethod]. No session callbacks are
     * needed — the platform wires the connection and we poll it per-command.
     */
    class PhantomInputMethod(service: AccessibilityService) : InputMethod(service)

    suspend fun type(text: String): Boolean {
        if (text.isEmpty()) return true
        val lastIndex = text.length - 1
        for (i in text.indices) {
            val ic = connection() ?: return false
            try {
                ic.commitText(text[i].toString(), 1, null)
            } catch (e: Exception) {
                android.util.Log.w(TAG, "commitText failed at index $i", e)
                return false
            }
            if (i < lastIndex) {
                val pause = Distributions.lognormal(
                    rng = rng,
                    medianMs = TYPE_MEDIAN_MS,
                    sigmaLog = TYPE_SIGMA_LOG,
                    minMs = TYPE_MIN_MS,
                    maxMs = TYPE_MAX_MS,
                )
                delay(pause)
            }
        }
        return true
    }

    suspend fun typeInto(node: AccessibilityNodeInfo, text: String): Boolean {
        val focused = node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        if (!focused) return false
        // Give the platform a beat to raise TYPE_VIEW_FOCUSED and wire the
        // AccessibilityInputConnection before we start committing.
        delay(FOCUS_SETTLE_MS)
        if (connection() == null) return false
        return type(text)
    }

    suspend fun clear(): Boolean {
        repeat(CLEAR_MAX_ITERATIONS) {
            val ic = connection() ?: return false
            val surrounding = try {
                ic.getSurroundingText(CLEAR_WINDOW, CLEAR_WINDOW, 0)
            } catch (e: Exception) {
                android.util.Log.w(TAG, "getSurroundingText failed", e)
                return false
            } ?: return false

            val text = surrounding.text
            val textLen = text?.length ?: 0
            if (textLen == 0) return true

            val selEnd = surrounding.selectionEnd.coerceIn(0, textLen)
            val before = selEnd
            val after = textLen - selEnd
            if (before == 0 && after == 0) return true

            try {
                ic.deleteSurroundingText(before, after)
            } catch (e: Exception) {
                android.util.Log.w(TAG, "deleteSurroundingText failed", e)
                return false
            }

            // If the field fit inside our bounded read we're done; otherwise
            // loop to clear the overflow.
            if (textLen < CLEAR_WINDOW) return true
        }
        return true
    }

    suspend fun sendKey(keyCode: Int): Boolean {
        val ic = connection() ?: return false
        val now = SystemClock.uptimeMillis()
        return try {
            ic.sendKeyEvent(buildKeyEvent(KeyEvent.ACTION_DOWN, keyCode, 0, now, now))
            ic.sendKeyEvent(buildKeyEvent(KeyEvent.ACTION_UP, keyCode, 0, now, now))
            true
        } catch (e: Exception) {
            android.util.Log.w(TAG, "sendKeyEvent failed (keyCode=$keyCode)", e)
            false
        }
    }

    suspend fun sendChord(meta: Int, keyCode: Int): Boolean {
        val ic = connection() ?: return false
        val modKey = metaStateToModifierKeyCode(meta)
        if (modKey == KeyEvent.KEYCODE_UNKNOWN) return false
        val now = SystemClock.uptimeMillis()
        return try {
            ic.sendKeyEvent(buildKeyEvent(KeyEvent.ACTION_DOWN, modKey, meta, now, now))
            ic.sendKeyEvent(buildKeyEvent(KeyEvent.ACTION_DOWN, keyCode, meta, now, now))
            ic.sendKeyEvent(buildKeyEvent(KeyEvent.ACTION_UP, keyCode, meta, now, now))
            ic.sendKeyEvent(buildKeyEvent(KeyEvent.ACTION_UP, modKey, 0, now, now))
            ic.clearMetaKeyStates(meta)
            true
        } catch (e: Exception) {
            android.util.Log.w(TAG, "sendChord failed (meta=$meta keyCode=$keyCode)", e)
            false
        }
    }

    // ── Internals ─────────────────────────────────────────────────

    private fun connection(): InputMethod.AccessibilityInputConnection? {
        return gestureService.inputMethod?.currentInputConnection
    }

    private fun buildKeyEvent(
        action: Int,
        keyCode: Int,
        metaState: Int,
        downTime: Long,
        eventTime: Long,
    ): KeyEvent = KeyEvent(
        /* downTime   */ downTime,
        /* eventTime  */ eventTime,
        /* action     */ action,
        /* code       */ keyCode,
        /* repeat     */ 0,
        /* metaState  */ metaState,
        /* deviceId   */ KeyCharacterMap.VIRTUAL_KEYBOARD,
        /* scancode   */ 0,
        /* flags      */ KeyEvent.FLAG_SOFT_KEYBOARD or KeyEvent.FLAG_KEEP_TOUCH_MODE,
    )

    private fun metaStateToModifierKeyCode(meta: Int): Int = when {
        meta and KeyEvent.META_CTRL_ON != 0 -> KeyEvent.KEYCODE_CTRL_LEFT
        meta and KeyEvent.META_ALT_ON != 0 -> KeyEvent.KEYCODE_ALT_LEFT
        meta and KeyEvent.META_SHIFT_ON != 0 -> KeyEvent.KEYCODE_SHIFT_LEFT
        meta and KeyEvent.META_META_ON != 0 -> KeyEvent.KEYCODE_META_LEFT
        else -> KeyEvent.KEYCODE_UNKNOWN
    }

    companion object {
        private const val TAG = "PhantomTypewriter"

        // Per-keystroke cadence — §4.4 of the fidelity spec.
        private const val TYPE_MEDIAN_MS = 200.0
        private const val TYPE_SIGMA_LOG = 0.30
        private const val TYPE_MIN_MS = 60L
        private const val TYPE_MAX_MS = 500L

        private const val FOCUS_SETTLE_MS = 100L
        private const val CLEAR_WINDOW = 8192
        private const val CLEAR_MAX_ITERATIONS = 4
    }
}
