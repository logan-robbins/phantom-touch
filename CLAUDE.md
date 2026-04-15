# CLAUDE.md — PhantomTouch

## What This Is

An Android app + server-side gateway that lets an LLM control a physical Android phone remotely. The LLM takes screenshots, reasons about UI layout, and emits gesture commands (tap, swipe, type) to operate apps like LinkedIn, Facebook, Chrome — anything on the phone.

## Why It Exists

The phone is not connected to the VM via USB or LAN. The phone sits on WiFi or cellular somewhere. The Azure VM runs OpenClaw + Grok. They communicate via an outbound WebSocket: the phone connects OUT to the VM, so no inbound ports or VPN are needed on the phone side.

## Architecture

```
VM (Azure Linux)                              Android Phone
┌─────────────────────────────┐              ┌──────────────────────────┐
│ OpenClaw/Grok ──→ MCP Server│              │ PhantomTouch App         │
│                  ↓           │              │                          │
│            localhost:9090    │              │  RelayClient (OkHttp WS) │
│                  ↓           │              │    ↕ executes commands    │
│            Relay Server      │◄── WS ──────│  GestureService          │
│            :9091 WebSocket   │   outbound   │    (AccessibilityService) │
│            :9090 HTTP        │   from phone │  ScreenCapture           │
└─────────────────────────────┘              │    (MediaProjection)     │
                                             │  CommandServer (:8080)   │
                                             │    (local HTTP, optional)│
                                             └──────────────────────────┘
```

Command flow: OpenClaw MCP tool call → mcp_server.py → relay :9090 HTTP → WebSocket → phone → CommandExecutor → result back up.

## Key Files

### Android App (`app/`)

| File | Purpose | Key Details |
|------|---------|-------------|
| `model/Command.kt` | Command protocol — all 20+ command types as a sealed class | `Command.parseBatch(json)` handles both single and `{"batch": [...]}` |
| `executor/CommandExecutor.kt` | Routes commands to the right Android API | The big `when (command)` block. Chrome operations use Intents for `openUrl`, `Runtime.exec("input keyevent ...")` for keyboard shortcuts. `findNode` and `waitForNode` search the accessibility tree. |
| `service/GestureService.kt` | `AccessibilityService` subclass | `dispatchGesture()` for tap/swipe/pinch, `typeText()` via `ACTION_SET_TEXT`, `dumpTree()` recursively serializes `AccessibilityNodeInfo` to JSON with bounds/flags. Singleton via `instance` companion. `canPerformGestures="true"` in XML config is critical. |
| `service/ScreenCapture.kt` | `MediaProjection` screenshot | Creates a temporary `VirtualDisplay` + `ImageReader`, grabs one frame, encodes to JPEG base64. `scale` param controls resolution. Handles row padding from `ImageReader`. Retries up to 3 times if frame is null. |
| `service/CaptureService.kt` | Foreground service that owns everything | Holds `MediaProjection`, starts `CommandServer` (local HTTP) and `RelayClient` (outbound WS). Must be foreground service for MediaProjection on Android 10+. |
| `relay/RelayClient.kt` | OkHttp WebSocket client → connects to VM | Auto-reconnects with exponential backoff. Sends `device_info` on connect, `heartbeat` every 30s. Receives `{"id": "...", "type": "command", "payload": {...}}`, executes via `CommandExecutor`, sends `{"id": "...", "type": "response", "payload": {...}}` back. |
| `server/CommandServer.kt` | NanoHTTPD on :8080 | Local HTTP server, same command format. Optional — the relay is the primary path for remote use. Supports bearer token auth. Uses `runBlocking` to bridge NanoHTTPD's thread pool to coroutine-based executor. |
| `MainActivity.kt` | 5-step onboarding UI (no XML layouts) | All views constructed in code. Steps: accessibility → battery exemption → screen capture → relay URL input → start & minimize. Saves relay URL to SharedPreferences. |

### Gateway (`gateway/`)

| File | Purpose | Key Details |
|------|---------|-------------|
| `relay_server.py` | WebSocket relay on VM | `websockets` lib for phone connection (:9091), `aiohttp` for HTTP API (:9090). `PhoneConnection` class manages WS state, pending request futures, and heartbeats. Heartbeat watchdog closes stale connections after 90s. HTTP endpoints mirror PhantomTouch's local API: `POST /execute`, `GET /health`. Requires `RELAY_TOKEN` by default. |
| `mcp_server.py` | MCP tool server for OpenClaw | Each tool is an `@app.tool()` decorated async function. Returns `TextContent` or `ImageContent`. Points at relay :9090 via `PHANTOM_TOUCH_URL` env var. Handles connection errors gracefully. |
| `skill.yaml` | OpenClaw skill registration | Points MCP server at `http://localhost:9090`. |

## Design Choices to Preserve

1. **Sealed class command protocol.** Every command is a typed Kotlin data class. Parsing happens in `Command.fromJson()`. Adding a new command means: add the class, add the `fromJson` case, add the `executeSingle` case in `CommandExecutor`, add the tool in `mcp_server.py`.

2. **Phone connects outbound.** The RelayClient on Android initiates the WebSocket. The relay server on the VM accepts it. This is a hard requirement — the phone has no public IP and no inbound ports.

3. **The relay HTTP API is identical to the local HTTP API.** The gateway code doesn't know whether it's talking to the phone directly or via the relay. `PHANTOM_TOUCH_URL` is the only config difference.

4. **No root required.** Everything uses `AccessibilityService` (user-granted) and `MediaProjection` (user-granted). No `su`, no Magisk, no system app installation.

5. **Batch execution is sequential.** The `batch` array executes in order. This matters for tap → delay → type flows where the tap must focus a field before type can fill it.

6. **Coordinate space.** Screenshots are taken at a configurable `scale` (default 0.5). The LLM receives the image plus `nativeWidth`, `nativeHeight`, `imageWidth`, `imageHeight`. Tap coordinates must be in native pixels. The LLM must multiply image-space coords by `nativeWidth / imageWidth`.

7. **`openUrl` via Intent is preferred over UI tapping for navigation.** `Intent.ACTION_VIEW` with `setPackage("com.android.chrome")` is the most reliable way to get Chrome to a URL. UI tapping for navigation is fragile.

8. **OpenClaw is the LLM agent.** PhantomTouch does not call LLM APIs directly. It exposes tools via MCP server, and OpenClaw (running on the VM) decides when and how to use them.

## Known Issues and TODOs

- **`chromeAction` keyboard shortcuts.** These send Ctrl+key combos via `input keyevent --longpress`. This works when a hardware keyboard is connected or in some Chrome configurations, but may not work on all devices. Test and potentially fall back to accessibility tree navigation.
- **Relay auth is plaintext.** Token is sent as JSON over WS. For production, use Tailscale (encrypted tunnel) or `wss://` with TLS termination (Caddy in front of the relay).
- **Vision model required.** OpenClaw needs a vision-capable model configured to see `ImageContent` screenshots. The default Grok 4.20 reasoning model is text-only.
- **In-flight command loss.** If the WebSocket drops mid-command, the command is lost. The agent (OpenClaw) can retry via a new tool call, but there's no automatic retry queue on the phone side.

## Build Instructions

### Android
```bash
# Requires: Android SDK, JDK 17
cd app
# Create local.properties with: sdk.dir=/path/to/android/sdk
../gradlew assembleDebug
adb install build/outputs/apk/debug/app-debug.apk
```

### Gateway (VM)
```bash
cd gateway
pip install -r requirements.txt
# Start relay (requires RELAY_TOKEN)
RELAY_TOKEN=changeme python relay_server.py
# MCP server is started by OpenClaw via skill.yaml / openclaw.json
```

## Testing Strategy

1. Build the Android app, fix compilation errors.
2. Install on a test device, complete the 5-step onboarding with relay URL pointing at your VM.
3. On the VM, run `relay_server.py` and verify the phone connects (check logs).
4. Test with curl: `curl -X POST http://localhost:9090/execute -d '{"action":"screenInfo"}'`
5. Test screenshot: `curl -X POST http://localhost:9090/execute -d '{"action":"screenshot","scale":0.5}' | python -c "import sys,json; print(len(json.load(sys.stdin)['result']['image']))"` — should return a large number (base64 length).
6. Test gesture: `curl -X POST http://localhost:9090/execute -d '{"action":"tap","x":540,"y":1200}'`
7. Test batch: `curl -X POST http://localhost:9090/execute -d '{"batch":[{"action":"launchApp","package":"com.android.chrome"},{"action":"delay","ms":3000},{"action":"screenshot"}]}'`
8. Verify OpenClaw can call `phantom_screenshot` and see the image via a vision-capable model.
