# PhantomTouch

LLM-driven Android device automation. No root. No Appium. No framework bloat.

## Architecture

The phone and VM are not physically connected. The phone initiates an outbound 
WebSocket connection to a relay server on the VM. Commands flow down, results flow back.

```
┌───────────────────────────────────────────────────────────────────┐
│  Azure VM (or any server)                                         │
│                                                                   │
│  ┌─────────────┐    ┌──────────────────────────────────┐          │
│  │ OpenClaw /   │    │ Relay Server (relay_server.py)   │          │
│  │ Grok Agent   │───→│ :9090 HTTP ← gateway calls here │          │
│  │ via Telegram │    │ :9091 WS   ← phone connects here│          │
│  └─────────────┘    └──────────────┬───────────────────┘          │
│         │                          │                              │
│  ┌──────▼────────┐                 │                              │
│  │ MCP Server    │                 │  WebSocket                   │
│  │ (mcp_server)  │─── localhost ──→│  (outbound from phone)       │
│  └───────────────┘    :9090        │                              │
└────────────────────────────────────┼──────────────────────────────┘
                                     │
                         Internet / cellular
                                     │
┌────────────────────────────────────▼──────────────────────────────┐
│  Android Phone                                                    │
│                                                                   │
│  ┌───────────────────────────────────────────┐                    │
│  │ PhantomTouch App                          │                    │
│  │                                           │                    │
│  │  RelayClient ──→ connects OUT to VM:9091  │                    │
│  │  GestureService → tap/swipe/type any app  │                    │
│  │  ScreenCapture  → screenshot any app      │                    │
│  │  CommandServer  → local :8080 (optional)  │                    │
│  └───────────────────────────────────────────┘                    │
│                                                                   │
│  Phone controls: Chrome, LinkedIn, Facebook, any app              │
└───────────────────────────────────────────────────────────────────┘
```

**Key insight:** The phone connects OUT to the VM. No inbound ports on the phone,
no VPN, no Tailscale, no ADB. Works over cellular, home WiFi, anywhere.

## Input Fidelity

PhantomTouch drives the phone through the same `AccessibilityService` APIs
TalkBack, Voice Access, and Switch Access use — and avoids the patterns that
historically flag an installed app as adversarial automation.

- **Taps** resolve via `AccessibilityNodeInfo.ACTION_CLICK` first (TalkBack
  parity), falling back to a jittered `dispatchGesture` stroke (σ = 15 px,
  60–90 ms duration).
- **Swipes** emit a single cubic-Bezier arc with a perpendicular control-point
  offset, not a ruler-straight line.
- **Typing** commits one character at a time through
  `InputMethod.AccessibilityInputConnection.commitText` — the same IME channel
  Voice Access uses — with a lognormal inter-keystroke cadence (median 200 ms).
  One `TextWatcher.onTextChanged` callback per keystroke, matching a real soft
  keyboard.
- **Keyboard chords** (Chrome's Ctrl-L, Ctrl-R, etc.) are paired DOWN/UP
  `KeyEvent`s flagged `FLAG_SOFT_KEYBOARD | FLAG_KEEP_TOUCH_MODE`, sent via
  `AccessibilityInputConnection.sendKeyEvent`.
- **No** `Runtime.exec("input …")`, **no** `ACTION_SET_TEXT`, **no** clipboard
  paste. Those patterns match known malware (Gustuff) and are not how the
  built-in assistive technologies work.

Design rationale, accepted limits, and citations live in
[`docs/superpowers/specs/2026-04-16-accessibility-fidelity-design.md`](docs/superpowers/specs/2026-04-16-accessibility-fidelity-design.md).

## Quick Start

### 1. VM Setup (Azure / any server)

```bash
cd gateway
pip install -r requirements.txt

# Start the relay (opens :9090 for HTTP, :9091 for phone WebSocket)
export RELAY_TOKEN=your-shared-secret
python relay_server.py
```

Open firewall port 9091 (WebSocket) for the phone to connect.
Port 9090 stays localhost-only — the gateway talks to it locally.

### 2. Android Setup

Build and install the PhantomTouch app, then follow the 5-step onboarding:
1. Enable Accessibility Service
2. Disable battery optimization
3. Grant screen capture
4. Enter relay URL: `ws://your-vm-ip:9091` + auth token
5. Start & Minimize

The app connects to the relay and goes headless.

> **Android 13+ sideloading note.** If the accessibility toggle for PhantomTouch
> is greyed out the first time, open Settings → Apps → PhantomTouch → ⋮ (top-right)
> → **"Allow restricted settings"**, then return to the onboarding screen. One-time
> per install. Android blocks sideloaded APKs from flipping the accessibility
> switch until the user explicitly overrides it.

**Build requirements.** Android Studio Hedgehog+ / JDK 17, Android SDK platform 34.
`minSdk = 34` (Android 14) — API 34 is the first release with reliable
`AccessibilityService.InputMethod` wiring across Samsung One UI and Xiaomi HyperOS.

### 3. OpenClaw Integration (on the VM)

The MCP server runs as a child process of OpenClaw, registered via `skill.yaml` or
`mcpServers` in `openclaw.json`. OpenClaw sees PhantomTouch tools as native callable
functions and uses them to control the phone.

```bash
# The MCP server points at the relay (not the phone directly)
export PHANTOM_TOUCH_URL=http://localhost:9090
python gateway/mcp_server.py
```

The MCP server doesn't know the phone is remote — it just hits `localhost:9090`,
which the relay proxies to the phone over WebSocket.

For remote-claw VMs, the update script (`007-phantom-touch.sh`) handles installation
automatically: starts the relay as a systemd service and registers the MCP server
in `openclaw.json`.

## How the LLM Emits Commands

The LLM never writes raw JSON commands. OpenClaw sees tools like `phantom_tap()`,
`phantom_open_url()`, `phantom_screenshot()` as native callable functions via the
MCP server. It decides which to call based on the conversation. The MCP server
translates tool calls to PhantomTouch HTTP requests against the relay.

**Flow example:**
```
User: "Post 'hello world' on LinkedIn"

LLM → tool_call: screenshot()
      ← result: {image, native_width: 1080, coord_multiplier: 2.0}
LLM → tool_call: open_url(url="linkedin.com/feed")
      ← result: {success: true}
LLM → tool_call: screenshot()
      ← result: {image showing LinkedIn feed}
LLM → tool_call: find_and_tap(query="Start a post")
      ← result: {found: true, tapped_at: [540, 200]}
LLM → tool_call: screenshot()
      ← result: {image showing post composer}
LLM → tool_call: tap_and_type(x=540, y=600, text="hello world")
      ← result: {success: true}
LLM → tool_call: screenshot()
      ← result: {image showing typed text}
LLM → tool_call: find_and_tap(query="Post")
      ← result: {found: true, tapped_at: [540, 200]}
```

## Command Reference

| Command | Description |
|---------|-------------|
| `tap` | Tap at (x, y) coordinates |
| `doubleTap` | Double tap |
| `longPress` | Long press with configurable duration |
| `swipe` | Swipe with start/end coords and duration (speed) |
| `pinch` | Two-finger pinch (zoom in/out) |
| `type` | Type into focused field (per-character commitText via AccessibilityInputConnection, humanized cadence) |
| `clearField` | Delete the focused field's contents via the AccessibilityInputConnection |
| `keyEvent` | Send an Android `KeyEvent` (paired DOWN/UP) through the AccessibilityInputConnection — e.g. 66=Enter, 4=Back |
| `back` / `home` / `recents` | System navigation |
| `launchApp` | Launch app by package name |
| `openUrl` | Open URL in Chrome via Intent |
| `chromeNewTab` | New Chrome tab |
| `chromeAction` | Chrome keyboard chords (addressBar, refresh, find, etc) delivered as soft-IME `KeyEvent`s |
| `screenshot` | Capture screen as base64 JPEG |
| `dumpTree` | Full accessibility tree as JSON |
| `findNode` | Search tree for elements by text |
| `waitForNode` | Poll until element appears |
| `screenInfo` | Get screen dimensions |
| `delay` | Pause between batch commands |

## Project Structure

```
phantom-touch/
├── app/                          # Android app (Kotlin)
│   ├── src/main/
│   │   ├── AndroidManifest.xml
│   │   ├── res/
│   │   └── java/.../
│   │       ├── MainActivity.kt         # 5-step onboarding UI
│   │       ├── model/Command.kt        # Command protocol (sealed class)
│   │       ├── executor/CommandExecutor.kt
│   │       ├── relay/RelayClient.kt    # Outbound WS to VM
│   │       ├── server/CommandServer.kt # Local HTTP (optional)
│   │       ├── fidelity/               # Accessibility-fidelity input stack
│   │       │   ├── Humanizer.kt        # Gesture synthesis (ACTION_CLICK-first tap, arced swipe, …)
│   │       │   ├── Typewriter.kt       # PhantomInputMethod + per-char commitText / sendKeyEvent
│   │       │   └── Distributions.kt    # Seeded lognormal / gaussian2D / uniform samplers
│   │       └── service/
│   │           ├── GestureService.kt   # AccessibilityService (thin wrapper)
│   │           ├── ScreenCapture.kt    # MediaProjection
│   │           └── CaptureService.kt   # Foreground service
│   └── build.gradle
├── gateway/                      # Runs on VM (Python)
│   ├── relay_server.py          # WS relay (phone ↔ gateway bridge)
│   ├── mcp_server.py            # MCP tools for OpenClaw
│   ├── skill.yaml               # OpenClaw skill config
│   └── requirements.txt
├── build.gradle
└── settings.gradle
```

## Dependencies

**Android app:** NanoHTTPD (local HTTP), OkHttp (outbound WebSocket), kotlinx-coroutines, Android SDK.

**VM gateway:** httpx, websockets, aiohttp, mcp (Python). No LangChain. No LangGraph.
