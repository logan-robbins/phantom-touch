# AccessibilityFidelity — Design Spec

**Date:** 2026-04-16
**Status:** Draft v2 — scope trimmed after review
**Author:** PhantomTouch team
**Scope:** Android app (`app/`). No changes to `gateway/`.

## 1. Purpose

PhantomTouch lets a disabled user's LLM agent (OpenClaw) operate their phone via the same accessibility APIs TalkBack, Voice Access, and Switch Access use. Today the app emits input through patterns that are both (a) visibly synthetic and (b) identical to known malware: `AccessibilityNodeInfo.ACTION_SET_TEXT` for bulk text, `Runtime.exec("input keyevent ...")` shell calls for key combos, and direct `dispatchGesture` strokes with no jitter. The Gustuff writeup (https://www.group-ib.com/blog/gustuff/) flags exactly these patterns.

This spec replaces those code paths with the modern `AccessibilityService` input stack: per-character `InputMethod.AccessibilityInputConnection.commitText`, `sendKeyEvent` for chords, and `dispatchGesture` Paths with light shape and cadence variation.

### 1.1 Goal

**Use Android's accessibility APIs the way the built-in ATs use them.** No more, no less. Every MotionEvent PhantomTouch emits should be indistinguishable from one TalkBack or Voice Access would emit for the same intent. Every text-input event should go through the same `AccessibilityInputConnection` path Voice Access uses.

Where free variation is cheap (±15 px coordinate jitter, lognormal typing interval, single arced swipe), we add it. Where complex biometric shaping (min-jerk velocity profiles, bigram-indexed typing cadence, two-finger asymmetry modeling) would cost substantial implementation with negligible detection benefit, we skip it.

### 1.2 What this spec does not attempt

- **Not** "indistinguishable from a real finger." Every `AccessibilityService.dispatchGesture` MotionEvent on unrooted Android carries `FLAG_IS_ACCESSIBILITY_EVENT=0x800`, `deviceId=KeyCharacterMap.VIRTUAL_KEYBOARD=-1`, `pressure=1.0f`, `size=1.0f`, `toolType=TOOL_TYPE_UNKNOWN` — stamped by `MotionEventInjector.obtainMotionEvent()` in AOSP. These flags are platform-level and unfixable from userland. They are also identical for TalkBack and Voice Access — the legitimate-AT signature, not a bot signature.
- **No anti-biometric shaping beyond light variation.** Elaborate Touchalytics-calibrated stroke curvature, min-jerk velocity LUTs, and bigram-indexed typing cadences buy little against modern platform defenses (account-shape signals, content classifiers, Play Integrity) that are already beyond userland's reach. See §1.4.
- **No IMU-correlated device-motion synthesis.** Crosses into adversarial deception.
- **No typo + backspace injection.** Real ATs don't fake human errors.
- **No banking-app compatibility.** Play Integrity `appAccessRisk` returns `KNOWN_CONTROLLING` whenever an accessibility service with `canPerformGestures=true` is enabled. Banking apps that hard-check this will refuse to run. Unfixable without root.
- **No voice / STT integration.** The LLM command API is the sole input path.

### 1.3 Distribution posture

Personal / sideloaded. No Play Store distribution. Target device is a Samsung Galaxy S24 on Android 14+. Users complete the Android 13+ "Allow restricted settings" flow once during onboarding (Settings → Apps → PhantomTouch → ⋮ → Allow restricted settings), then enable the accessibility service. `android:isAccessibilityTool="true"` in the service XML is declared regardless of distribution channel — it grants Android 14+ `accessibilityDataSensitive` view access and exempts the service from Android 17 AAPM auto-revocation (Android Authority, March 2026: https://www.androidauthority.com/android-advanced-protection-mode-accessibility-apk-teardown-3640742/).

### 1.4 Where the real detection surface lives

Honest accounting for Facebook and similar platforms:

- **The top-3 signals against an AT-driven session are account-shape, not input-shape:** posts/hour, likes/minute, friend-adds/day; content-classifier hits on LLM-generated text; graph-walk anomalies. No amount of swipe curvature realism survives an account that likes 300 posts in an hour.
- **`FLAG_IS_ACCESSIBILITY_EVENT=0x800` is readable by any app** in its own `View.dispatchTouchEvent`. If a platform chooses to flag sessions with 100% AT-flagged input, biometric shaping is irrelevant. This flag is platform-stamped on TalkBack/Voice Access sessions too; "this is an AT session" is the honest posture.
- **Rate limiting belongs at the LLM agent layer**, not here. Out of scope for this spec, but the right place to solve the usage-velocity problem. The agent's system prompt should cap actions/minute and posts/day.

This spec targets what userland can legitimately control: using the same APIs and similar surface statistics as the built-in ATs.

## 2. Architecture

### 2.1 Package layout

```
app/src/main/java/ai/qmachina/phantomtouch/
├── service/
│   └── GestureService.kt          MODIFIED — thin AccessibilityService wrapper
├── fidelity/                      NEW package
│   ├── Humanizer.kt               gesture synthesis (tap, swipe, longPress, pinch, doubleTap)
│   ├── Typewriter.kt              InputMethod subclass + per-keystroke commitText
│   └── Distributions.kt           seeded RNG samplers (pure functions)
└── executor/
    └── CommandExecutor.kt         MODIFIED — delegates to Humanizer/Typewriter
```

### 2.2 Call direction

```
CommandExecutor
  ├── (tap, doubleTap, longPress, swipe, pinch) → Humanizer
  │                                                   ├→ GestureService.rootInActiveWindow
  │                                                   │    (ACTION_CLICK-first tap resolution)
  │                                                   └→ GestureService.dispatchGestureAsync
  │                                                        (humanized dispatch fallback)
  ├── (type, clearField, keyEvent, chromeAction) → Typewriter
  │                                                   └→ GestureService.currentInputConnection
  │                                                        (commitText, sendKeyEvent)
  └── (back, home, recents, launchApp, openUrl, screenshot, dumpTree,
       findNode, clickNode, scrollNode, waitForNode, delay, screenInfo)
                                                   → unchanged paths on GestureService
                                                     or Context (intent dispatch)
```

### 2.3 Module boundaries

- `GestureService` owns: `AccessibilityService` lifecycle, raw `dispatchGesture` primitive, `rootInActiveWindow`, global actions (back/home/recents), `dumpTree`, `onCreateInputMethod()` override returning the Typewriter's `InputMethod`. **No fidelity logic.**
- `Humanizer` owns: gesture synthesis math. Stateless per-call; takes params (coords, duration, seed), emits primitive `dispatchGesture` calls.
- `Typewriter` owns: the `InputMethod` subclass, per-command session acquire/release, per-keystroke `commitText`/`sendKeyEvent` emission.
- `Distributions` owns: seeded RNG sampler functions (pure).
- `CommandExecutor` owns: command dispatch, Intent-based operations, node-search helpers, batch orchestration.

### 2.4 LLM-facing API

The MCP tool surface (`mcp_server.py`) and command JSON protocol (`Command.kt`) **do not change**. All fidelity behavior is internal.

### 2.5 Inter-command pacing

**None.** The LLM round-trip (screenshot upload → inference → tool call return) already imposes 2–10 s between commands, which exceeds naturalistic reading + decision pauses. Fidelity concerns are intra-command only.

## 3. Humanizer

### 3.1 Public surface

```kotlin
class Humanizer(
    private val gestureService: GestureService,
    private val rng: kotlin.random.Random,
) {
    suspend fun tap(x: Float, y: Float): Boolean
    suspend fun doubleTap(x: Float, y: Float, intervalMs: Long): Boolean
    suspend fun longPress(x: Float, y: Float, durationMs: Long): Boolean
    suspend fun swipe(sx: Float, sy: Float, ex: Float, ey: Float, durationMs: Long): Boolean
    suspend fun pinch(cx: Float, cy: Float, startSpan: Float, endSpan: Float, durationMs: Long): Boolean
}
```

Signatures match today's `GestureService` public methods. Drop-in replacement at the `CommandExecutor` call site.

### 3.2 Tap — ACTION_CLICK-first resolution

`tap(x, y)`:

1. `root = gestureService.rootInActiveWindow`. If null, fall through to (4).
2. Find the leaf `AccessibilityNodeInfo` whose `bounds` contain `(x, y)`. Walk up at most **8 levels** collecting the first ancestor with `isClickable() == true` or `hasAction(ACTION_CLICK)`. Depth cap covers typical Compose mergeDescendants trees (3–6 levels) plus RecyclerView item containers (4–8 levels).
3. Reject the ancestor if its bounds area is >1.25× the original leaf's bounds area (guards against matching a full-screen clickable).
4. On a valid clickable match: `performAction(ACTION_CLICK)`. Return the result, recycle the node.
5. On no match, null tree, or `performAction` returning false: **dispatched-tap fallback** (§3.3).

**Rationale.** TalkBack's `FocusActor` uses ACTION_CLICK first (`google/talkback` → `FocusActor.java`:405–420). ACTION_CLICK also sidesteps animation / overlay / hit-testing edge cases.

### 3.3 Dispatched-tap fallback

- **Coordinate jitter:** independent Gaussians, `σ_x = σ_y = 15 px`. Applied once per tap.
- **Duration:** uniform `U(60, 90) ms`. Well under `ViewConfiguration.TAP_TIMEOUT = 100 ms` so the platform still classifies as a tap.
- **Path:** zero-length (single point). TalkBack uses zero-length paths. Fine for our posture.

### 3.4 Swipe

- **Shape:** single cubic Bezier from `(sx, sy)` to `(ex, ey)`. Control points offset **perpendicular to A→B by ±0.08 × stroke_length**, same side. Produces a gentle arc instead of a ruler-straight line. No min-jerk velocity shaping — the platform samples the Path at the display refresh rate (behavior of `GestureDescription` dispatch per `GestureDescription.Builder` / `dispatchGesture` docs), which is fine for our posture.
- **Endpoint jitter:** independent Gaussian per endpoint, `σ = 15 px`.
- **Duration jitter:** requested `durationMs × U(0.9, 1.1)`.
- **Edge safety:** clamp all Bezier control points to `[1, displayWidth−1] × [1, displayHeight−1]`. `GestureDescription.Builder.addStroke` throws on negative bounds. Dispatched gestures bypass `InputReader`'s edge-gesture detector, so edge-swipe-to-back / drawer do not trigger — callers should use `Command.Back` for those.

### 3.5 Long press

Zero-length Bezier at jittered `(x, y)`. Duration = requested `durationMs × U(0.9, 1.1)`. Default 1000 ms (above `ViewConfiguration.DEFAULT_LONG_PRESS_TIMEOUT = 400 ms`).

### 3.6 Pinch

Two symmetric strokes, each an independent Bezier with independent endpoint jitter (σ = 15 px). Both start at `t = 0` and end at `t = durationMs`. No lead/follower asymmetry modeling — multi-touch asymmetry in real pinches is not a detection surface the platform exposes in any meaningful way.

### 3.7 Double tap

Two humanized taps. Inter-tap gap sampled from `U(80, 200) ms`. Must exceed `ViewConfiguration.DOUBLE_TAP_MIN_TIME = 40 ms` and stay below `DOUBLE_TAP_TIMEOUT = 300 ms`.

### 3.8 Seeded RNG

`kotlin.random.Random(seed: Long)` threaded through all samplers. Default seed sourced from `System.nanoTime()` at process start; override via `Humanizer` constructor for deterministic test runs. `kotlin.random.Random` is correct for non-cryptographic reproducible randomness per Android guidance (https://developer.android.com/privacy-and-security/risks/weak-prng).

### 3.9 Accepted platform fingerprint

Every dispatched MotionEvent carries:
- `FLAG_IS_ACCESSIBILITY_EVENT = 0x800`
- `deviceId = KeyCharacterMap.VIRTUAL_KEYBOARD = -1`
- `pressure = 1.0f`, `size = 1.0f`
- `toolType = TOOL_TYPE_UNKNOWN`

Platform-stamped by `MotionEventInjector.obtainMotionEvent()` on every accessibility-injected event, including TalkBack, Voice Access, Switch Access. The design makes no attempt to mask them.

## 4. Typewriter

### 4.1 Platform path

- Declare `android:accessibilityFlags="...|flagInputMethodEditor"` (`flagInputMethodEditor = 0x8000`) in `res/xml/accessibility_service_config.xml`.
- Override `AccessibilityService.onCreateInputMethod()` in `GestureService` to return the Typewriter's `InputMethod` subclass.
- Acquire the `AccessibilityInputConnection` via `gestureService.currentInputConnection` (fresh per focus session; invalidated when focus leaves).
- Text input via `connection.commitText(char, 1, null)` — one character per commit.
- Key events via `connection.sendKeyEvent(KeyEvent(...))` with `FLAG_SOFT_KEYBOARD | FLAG_KEEP_TOUCH_MODE`.

Same API path Voice Access uses (https://developer.android.com/reference/android/accessibilityservice/InputMethod.AccessibilityInputConnection) and the same pattern TalkBack's braille IME uses (`google/talkback` → `braille/brailleime/.../BrailleIme.java`:1736 `commitText`, 1705–1707 `sendKeyEvent`).

### 4.2 Minimum Android version

**`minSdkVersion = 34`** (Android 14). API 33 has unreliable `getInputMethod()` wiring on OneUI 5 and HyperOS (droidVNC-NG issue #208). API 34 is the first reliable version across OEMs. Target device (Samsung Galaxy S24) ships with Android 14+.

### 4.3 Public surface

```kotlin
class Typewriter(
    private val gestureService: GestureService,
    private val rng: kotlin.random.Random,
) {
    val inputMethod: AccessibilityService.InputMethod

    suspend fun type(text: String): Boolean
    suspend fun typeInto(node: AccessibilityNodeInfo, text: String): Boolean
    suspend fun clear(): Boolean
    suspend fun sendKey(keyCode: Int): Boolean
    suspend fun sendChord(meta: Int, keyCode: Int): Boolean
}
```

### 4.4 Per-keystroke cadence

Single lognormal distribution between each `commitText`:

- **Median:** 200 ms
- **σ (log):** 0.30
- **Clamp:** `[60, 500] ms`
- **Floor rationale:** 60 ms is the physiological minimum observed in mobile typing datasets.
- **Upper clamp rationale:** keep the agent responsive; the LLM round-trip already adds human-scale pauses between commands.

No bigram classes, no post-space / post-period multipliers, no layer-switch modeling. An AT-driven session that types at a uniform lognormal cadence is not a detection surface that matters.

### 4.5 What apps observe

`InputConnection.commitText` emits a single `TextWatcher.onTextChanged` callback per commit. Per-character commits therefore emit N `onTextChanged` callbacks — matching a software IME's per-keystroke behavior. No `KeyEvent` is emitted by `commitText`. Apps observing typing via `TextWatcher` see perfect soft-IME behavior.

### 4.6 KeyEvent chords

`sendKey(keyCode)` — emit paired `ACTION_DOWN`/`ACTION_UP` with `FLAG_SOFT_KEYBOARD | FLAG_KEEP_TOUCH_MODE`. No synthetic sleep between DOWN and UP.

`sendChord(meta, keyCode)` — emit 4 events:
1. `DOWN` modifier, `metaState = meta`
2. `DOWN` target, `metaState = meta`
3. `UP` target, `metaState = meta`
4. `UP` modifier, `metaState = 0`

All four events carry `FLAG_SOFT_KEYBOARD | FLAG_KEEP_TOUCH_MODE`. Follow with `connection.clearMetaKeyStates(meta)` to prevent stuck modifiers.

**Chrome chord caveat:** Chrome honors soft-IME chords. Apps that gate shortcuts on `InputDevice.SOURCE_KEYBOARD` (hardware) may ignore them. Existing `chromeAction` surface is kept and reimplemented through `Typewriter.sendChord` instead of `Runtime.exec`.

### 4.7 Clear field

`clear()`: read focused `AccessibilityInputConnection`, call `getSurroundingText(maxBefore=8192, maxAfter=8192, flags=0)` to measure text length (bounded read to avoid OOM on very large fields), then `deleteSurroundingText(length, 0)`. Loop up to 4 iterations if the field exceeded the bounded read on the first pass. No shell exec, no `ACTION_SET_TEXT`.

### 4.8 Type into a specific node

`typeInto(node, text)`: `node.performAction(ACTION_FOCUS)`, wait for focus event, then proceed with `type(text)` targeting the now-focused field via `AccessibilityInputConnection`.

**WebView / Compose edge case.** Some WebView text inputs and Compose fields using `Modifier.clearAndSetSemantics { }` do not expose focus through the standard `AccessibilityInputConnection` session callbacks. In those cases `typeInto` returns `false` and the caller may fall back to `Command.Tap` on the field followed by `Command.Type`. This is a documented degradation, not a bug.

### 4.9 Session acquire / release policy

The `InputMethod` session is scoped **per-command**, not per-app-lifetime. `Typewriter.type()` / `clear()` / `sendKey()` / `sendChord()` each:

1. Acquire `gestureService.currentInputConnection` on entry.
2. Execute the command.
3. Release on exit.

If the user physically touches the screen during a command, normal focus-stack behavior wins: the active connection is invalidated by the focus change, our in-flight command returns `false` ("focus lost"), and PhantomTouch does not contest it. Gboard and other installed IMEs remain the user's default keyboard for manual typing at all times. PhantomTouch's `InputMethod` is inert except during a commanded operation.

## 5. Distributions

Pure Kotlin module. All samplers take `kotlin.random.Random` as a parameter; no hidden state.

```kotlin
object Distributions {
    fun lognormal(rng: Random, medianMs: Double, sigmaLog: Double, minMs: Long, maxMs: Long): Long
    fun gaussian2D(rng: Random, sigma: Float): Pair<Float, Float>
    fun uniform(rng: Random, lo: Double, hi: Double): Double
}
```

## 6. Configuration changes

### 6.1 `res/xml/accessibility_service_config.xml`

```xml
<accessibility-service xmlns:android="http://schemas.android.com/apk/res/android"
    android:accessibilityEventTypes="typeAllMask"
    android:accessibilityFeedbackType="feedbackGeneric"
    android:accessibilityFlags="flagDefault|flagIncludeNotImportantViews|flagRequestTouchExplorationMode|flagReportViewIds|flagInputMethodEditor"
    android:canPerformGestures="true"
    android:canRetrieveWindowContent="true"
    android:isAccessibilityTool="true"
    android:notificationTimeout="100" />
```

Additions vs. today: `flagInputMethodEditor` (`0x8000`), `isAccessibilityTool="true"`.

### 6.2 `app/build.gradle`

Bump `minSdk` to **34**.

### 6.3 `AndroidManifest.xml`

No additional changes. `BIND_ACCESSIBILITY_SERVICE` already declared.

### 6.4 Onboarding copy

Existing 5-step onboarding in `MainActivity.kt` must note the Android 13+ "Allow restricted settings" step for sideloaded APKs: Settings → Apps → PhantomTouch → ⋮ → "Allow restricted settings." One-time per install.

## 7. Deletions

**`service/GestureService.kt`:**
- `tap()`, `longPress()`, `swipe()`, `pinch()` → migrate to `Humanizer`
- `typeText()` → delete; replaced by `Typewriter.type()`
- `findFocusedEditableNode()` → delete. Its callers (`typeText()` and `Command.ClearField` fallback) are being deleted. `Typewriter` gets the focused editable implicitly via `AccessibilityInputConnection` focus-session callbacks. WebView / Compose `clearAndSetSemantics` edge case is documented in §4.8.

**`executor/CommandExecutor.kt`:**
- `Runtime.getRuntime().exec(arrayOf("input", "keyevent", ...))` in `Command.KeyEvent` → `Typewriter.sendKey(keyCode)`
- `Runtime.getRuntime().exec(...)` fallback in `Command.ClearField` → `Typewriter.clear()`
- `Runtime.getRuntime().exec(...)` in `executeChromeAction` → `Typewriter.sendChord(meta, keyCode)`
- `ACTION_SET_TEXT` fallback in `Command.SetNodeText` → `Typewriter.typeInto(node, text)`
- `ACTION_SET_TEXT` in any `typeText`-equivalent path

No parallel paths retained. Shell exec is entirely removed from `CommandExecutor`.

## 8. Testing strategy

### 8.1 Unit tests (no device)

- `Distributions.lognormal`: seeded runs deterministic. Empirical median within 5% of nominal over N=10,000.
- `Distributions.gaussian2D`: seeded deterministic; empirical σ within 5% of requested.
- `Humanizer.swipe` path geometry: for a 500 px horizontal swipe, Path endpoints within σ tolerance of requested coords, middle control point offset perpendicular within expected magnitude.
- `Humanizer.tap` duration: all sampled values in `[60, 90] ms`.
- `Typewriter` cadence for input `"hello world"`: 10 inter-character intervals, all in `[60, 500] ms`, median near 200 ms.

### 8.2 Instrumented tests (Android 14+ emulator)

- `Command.Tap` against a test Activity with a clickable Button at known coords: verify `OnClickListener` fires via `ACTION_CLICK` path without ever reaching `dispatchGesture`.
- `Command.Tap` against coords with no clickable node: verify dispatch path runs, `dispatchTouchEvent` records MotionEvents, assert `getFlags() & 0x800 != 0`.
- `Command.Type("hello world")`: assert `TextWatcher.onTextChanged` fires 11 times, intervals all in `[60, 500] ms`.
- `Command.Swipe` from (100, 500) to (900, 500): all Path samples within display bounds, duration within ±10% of requested.
- `Command.ChromeAction("addressBar")`: launch Chrome via test-fixture Intent, verify address bar receives focus via `AccessibilityEvent.TYPE_VIEW_FOCUSED` after chord.
- **Session release test:** start `Command.Type("long text...")`, simulate a focus-loss event mid-type, assert the command returns `false` and no further `commitText` calls are made after the focus event.

### 8.3 Manual device tests

- LinkedIn / Facebook / Chrome: ordinary agent tasks. Account check at 1h / 24h / 7d / 30d for any restriction or challenge.
- **Voice Access parity test** (pass criterion, not "verify they match"): drive `Command.Tap(500, 800)` via PhantomTouch, then ask Voice Access to tap the same Activity at the same coord. Capture both `adb shell dumpsys input` outputs. **Pass** iff on both samples: `deviceId == -1`, `flags & 0x800 != 0`, `toolType == 0`, `pressure == 1.0`, `size == 1.0`. Timing and exact coord may differ; the four platform-stamped fields must match.
- Play Integrity sanity: confirm verdict shows `appAccessRisk = KNOWN_CONTROLLING`. Documented, accepted.

## 9. Risks and open questions

### 9.1 Resolved

- **"Can we look like a real finger?"** No. Platform-stamped flags make this unreachable on unrooted Android. Design accepts this.
- **"Which IME path?"** `AccessibilityInputConnection` via `FLAG_INPUT_METHOD_EDITOR`. No separate keyboard install.
- **"How elaborate should the humanization be?"** Light: coordinate jitter, one arced swipe, single-lognormal typing cadence. Elaborate biometric shaping cut as negligible-benefit-for-substantial-cost (§1.1, §1.4).
- **"IME coexistence with Gboard?"** Resolved via per-command session scope (§4.9). If the user touches the screen, PhantomTouch's in-flight command fails cleanly and the normal IME stack wins.
- **"Inter-command pacing?"** None. LLM round-trip dominates.

### 9.2 Open

- **Chrome chord delivery on OEM browser forks.** Samsung Internet / Mi Browser do not reliably honor `sendKeyEvent` chords. Mitigation (deferred): detect browser at `openUrl` time, fall back to a11y tree navigation for `chromeAction` when not Chrome.
- **Android 17 AAPM flag meaning.** We set `isAccessibilityTool="true"` and remain exempt today. If Google later tightens the exemption to require Play-verified declaration, sideloaded builds may lose it. Accepted future risk.

## 10. Out-of-scope follow-ups

Deliberately **not** in this spec:

- IMU-correlated device-motion synthesis
- Typo + backspace injection
- Root / `/dev/uinput` injection to strip `FLAG_IS_ACCESSIBILITY_EVENT`
- Banking-app compatibility
- Voice / STT integration
- **LLM-level rate limiting / usage discipline.** The real defense against account-level flagging on Facebook and similar platforms is action velocity and session shape — not input biometrics. Belongs in the agent's system prompt at the OpenClaw layer, not in the Android app. Starting caps to encode when that work happens:
  - **Max 20 actions / minute** (taps, swipes, types, chord events — any command).
  - **Max 5 posts / day** (new top-level posts or comments on feeds).
  - **Don't engage newly-created content within 10 s of page load** — imposes a read-before-act minimum that defeats "scroll feed, like-everything-instantly" heuristics.
  These are starting values, not calibrated thresholds. Tune per platform once baseline telemetry exists.
- Per-user cadence calibration
- Server-side behavioral profiling

## 11. Citations index

- AOSP `MotionEventInjector.java`: https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/services/accessibility/java/com/android/server/accessibility/MotionEventInjector.java
- AOSP `AccessibilityService.java` (`onCreateInputMethod`): https://android.googlesource.com/platform/frameworks/base/+/master/core/java/android/accessibilityservice/AccessibilityService.java
- AOSP `ViewConfiguration.java` (`TAP_TIMEOUT`, `DOUBLE_TAP_MIN_TIME`, `DEFAULT_LONG_PRESS_TIMEOUT`): https://android.googlesource.com/platform/frameworks/base/+/master/core/java/android/view/ViewConfiguration.java
- `InputMethod.AccessibilityInputConnection` docs: https://developer.android.com/reference/android/accessibilityservice/InputMethod.AccessibilityInputConnection
- `AccessibilityServiceInfo.FLAG_INPUT_METHOD_EDITOR`: https://developer.android.com/reference/android/accessibilityservice/AccessibilityServiceInfo#FLAG_INPUT_METHOD_EDITOR
- `isAccessibilityTool`: https://developer.android.com/reference/android/accessibilityservice/AccessibilityServiceInfo#isAccessibilityTool()
- Android 17 AAPM — Android Authority: https://www.androidauthority.com/android-advanced-protection-mode-accessibility-apk-teardown-3640742/
- Android 17 AAPM — The Hacker News: https://thehackernews.com/2026/03/android-17-blocks-non-accessibility.html
- Play Integrity `appAccessRisk`: https://developer.android.com/google/play/integrity/verdicts
- Android weak-PRNG guidance: https://developer.android.com/privacy-and-security/risks/weak-prng
- TalkBack source (`FocusActor.java`, `BrailleIme.java`): https://github.com/google/talkback
- Group-IB Gustuff (`ACTION_SET_TEXT` malware fingerprint): https://www.group-ib.com/blog/gustuff/

## 12. Legal posture (summary)

Built for and as **assistive technology for disabled users**. Supported by:

- **US:** ADA § 12182(b)(2)(A)(iii); 28 CFR § 36.303(b); DOJ/EEOC 2022 algorithmic fairness guidance. Post-Van Buren (2021) and hiQ (2022) CFAA narrowing: an authenticated user operating their own account via AT does not "exceed authorized access."
- **EU:** European Accessibility Act (Directive 2019/882) effective June 28, 2025; EN 301 549 WCAG 2.1 AA. DSA Art. 14 disability non-discrimination duty. EU Commission April 15, 2026 WhatsApp/AI-assistant preliminary finding.
- **UK:** Equality Act 2010 ss. 20, 29 anticipatory reasonable-adjustment duty.
- **Canada:** Accessible Canada Act S.C. 2019 c. 10; SOR/2025-255 Phase 1 Digital Technologies regulations.
- **Australia:** Disability Discrimination Act 1992 (Cth); Maguire v SOCOG (2000); Mesnage v Coles (2015).

No published U.S. or EU case has reached the merits of a platform's automation ban applied to an AT user. OpenAI Operator / ChatGPT Agent are functionally equivalent technologies deployed at scale without legal incident.
