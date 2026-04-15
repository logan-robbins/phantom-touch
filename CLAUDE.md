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
│         or       Tool Gateway│              │                          │
│                  ↓           │              │  RelayClient (OkHttp WS) │
│            localhost:9090    │              │    ↕ executes commands    │
│                  ↓           │              │  GestureService          │
│            Relay Server      │◄── WS ──────│    (AccessibilityService) │
│            :9091 WebSocket   │   outbound   │  ScreenCapture           │
│            :9090 HTTP        │   from phone │    (MediaProjection)     │
└─────────────────────────────┘              │  CommandServer (:8080)   │
                                             │    (local HTTP, optional)│
                                             └──────────────────────────┘
```

Command flow: LLM tool call → gateway → relay :9090 HTTP → WebSocket → phone → CommandExecutor → result back up.

## Status / What's Done

The project is **scaffolded but not compiled or tested**. All source files are written. The code is structurally complete but needs:

1. **Android build** — The Gradle files, manifest, and Kotlin sources are in place but have never been run through the compiler. Expect import fixes, minor API corrections, and possibly missing resources (launcher icon, theme reference).

2. **Screenshot-to-coordinate pipeline** — The system prompt tells the LLM to multiply image coordinates by `nativeWidth / imageWidth`, but the tool_gateway screenshot tool needs to actually pass the image to the LLM as a vision input (currently returns metadata only in `_format_tool_result`). This is the most important missing piece.

3. **Relay protocol hardening** — Happy path works, but needs: reconnect state in the Android client (the OkHttp WS listener reconnects, but in-flight commands are lost), relay-side timeout for stale phone connections, and optionally TLS/wss:// support.

4. **Anthropic tool-calling normalization** — The `_normalize_anthropic_response` method in tool_gateway.py converts Anthropic's response format to OpenAI-like, but tool result messages need to be converted back to Anthropic format when calling Claude (Anthropic uses `tool_result` content blocks, not `role: tool`).

## Key Files

### Android App (`app/`)

| File | Purpose | Key Details |
|------|---------|-------------|
| `model/Command.kt` | Command protocol — all 20+ command types as a sealed class | `Command.parseBatch(json)` handles both single and `{"batch": [...]}` |
| `executor/CommandExecutor.kt` | Routes commands to the right Android API | The big `when (command)` block. Chrome operations use Intents for `openUrl`, `Runtime.exec("input keyevent ...")` for keyboard shortcuts. `findNode` and `waitForNode` search the accessibility tree. |
| `service/GestureService.kt` | `AccessibilityService` subclass | `dispatchGesture()` for tap/swipe/pinch, `typeText()` via `ACTION_SET_TEXT`, `dumpTree()` recursively serializes `AccessibilityNodeInfo` to JSON with bounds/flags. Singleton via `instance` companion. `canPerformGestures="true"` in XML config is critical. |
| `service/ScreenCapture.kt` | `MediaProjection` screenshot | Creates a temporary `VirtualDisplay` + `ImageReader`, grabs one frame, encodes to JPEG base64. `scale` param controls resolution. Handles row padding from `ImageReader`. |
| `service/CaptureService.kt` | Foreground service that owns everything | Holds `MediaProjection`, starts `CommandServer` (local HTTP) and `RelayClient` (outbound WS). Must be foreground service for MediaProjection on Android 10+. |
| `relay/RelayClient.kt` | OkHttp WebSocket client → connects to VM | Auto-reconnects with exponential backoff. Sends `device_info` on connect, `heartbeat` every 30s. Receives `{"id": "...", "type": "command", "payload": {...}}`, executes via `CommandExecutor`, sends `{"id": "...", "type": "response", "payload": {...}}` back. |
| `server/CommandServer.kt` | NanoHTTPD on :8080 | Local HTTP server, same command format. Optional — the relay is the primary path for remote use. Uses `runBlocking` to bridge NanoHTTPD's thread pool to coroutine-based executor. |
| `MainActivity.kt` | 5-step onboarding UI (no XML layouts) | All views constructed in code. Steps: accessibility → battery exemption → screen capture → relay URL input → start & minimize. Saves relay URL to SharedPreferences. |

### Gateway (`gateway/`)

| File | Purpose | Key Details |
|------|---------|-------------|
| `relay_server.py` | WebSocket relay on VM | `websockets` lib for phone connection (:9091), `aiohttp` for HTTP API (:9090). `PhoneConnection` class manages WS state, pending request futures, and heartbeats. HTTP endpoints mirror PhantomTouch's local API: `POST /execute`, `GET /health`. |
| `mcp_server.py` | MCP tool server for OpenClaw/Claude Desktop | Each tool is an `@app.tool()` decorated async function. Returns `TextContent` or `ImageContent`. Points at relay :9090 via `PHANTOM_TOUCH_URL` env var. |
| `tool_gateway.py` | Standalone agent loop with native function calling | 12 tools defined as OpenAI-format function schemas. `PhantomAgent.run(task)` loops: call LLM → execute tool calls → feed results back. Supports xAI Grok, Anthropic Claude, OpenAI GPT-4o via `LLM_PROVIDER` env var. `execute_tool()` maps tool names to PhantomTouch HTTP calls. |
| `skill.yaml` | OpenClaw skill registration | Points MCP server at `http://localhost:9090`. |

### Orchestrator (`orchestrator/`)

| File | Purpose |
|------|---------|
| `agent.py` | Raw Python HTTP client (`PhantomTouch` class) with convenience methods. Useful for scripting/testing. Has a `call_vision_llm()` function and `run_task()` loop but these are superseded by tool_gateway.py. |

## Design Choices to Preserve

1. **Sealed class command protocol.** Every command is a typed Kotlin data class. Parsing happens in `Command.fromJson()`. Adding a new command means: add the class, add the `fromJson` case, add the `executeSingle` case in `CommandExecutor`, add the tool in `mcp_server.py` and/or `tool_gateway.py`.

2. **Phone connects outbound.** The RelayClient on Android initiates the WebSocket. The relay server on the VM accepts it. This is a hard requirement — the phone has no public IP and no inbound ports.

3. **The relay HTTP API is identical to the local HTTP API.** The gateway code doesn't know whether it's talking to the phone directly or via the relay. `PHANTOM_TOUCH_URL` is the only config difference.

4. **No root required.** Everything uses `AccessibilityService` (user-granted) and `MediaProjection` (user-granted). No `su`, no Magisk, no system app installation.

5. **Batch execution is sequential.** The `batch` array executes in order. This matters for tap → delay → type flows where the tap must focus a field before type can fill it.

6. **Coordinate space.** Screenshots are taken at a configurable `scale` (default 0.5). The LLM receives the image plus `nativeWidth`, `nativeHeight`, `imageWidth`, `imageHeight`. Tap coordinates must be in native pixels. The LLM must multiply image-space coords by `nativeWidth / imageWidth`.

7. **`openUrl` via Intent is preferred over UI tapping for navigation.** `Intent.ACTION_VIEW` with `setPackage("com.android.chrome")` is the most reliable way to get Chrome to a URL. UI tapping for navigation is fragile.

## Known Issues and TODOs

- **Launcher icon.** No `@mipmap/ic_launcher` resource exists. Either add one or use a default Android icon.
- **Theme reference.** `@style/Theme.Material3.DayNight` in the manifest may require the Material 3 dependency, or replace with `@android:style/Theme.DeviceDefault`.
- **`tools:ignore` namespace.** The manifest uses `tools:ignore="QueryAllPackagesPermission"` but the `xmlns:tools` declaration is on the `<application>` tag instead of `<manifest>`. Move it up.
- **Vision in tool_gateway.** `_format_tool_result` for screenshots strips the base64 image and sets `_image_included = True`, but the next LLM call doesn't actually include the image in the message content. For vision to work, the screenshot base64 needs to be injected as an image content block in the next user/tool message.
- **Anthropic tool result format.** When `LLM_PROVIDER=anthropic`, tool results need to be sent as `{"role": "user", "content": [{"type": "tool_result", "tool_use_id": "...", "content": "..."}]}` — not the OpenAI `{"role": "tool"}` format currently used.
- **`clearField` implementation.** Uses `Runtime.exec("input keyevent ...")` which requires the app to have shell execution permission. This works on most devices but may fail on locked-down OEM ROMs. Alternative: use `AccessibilityNodeInfo.performAction(ACTION_SET_TEXT)` with empty string.
- **`chromeAction` keyboard shortcuts.** These send Ctrl+key combos via `input keyevent --longpress`. This works when a hardware keyboard is connected or in some Chrome configurations, but may not work on all devices. Test and potentially fall back to accessibility tree navigation.
- **Relay auth is plaintext.** Token is sent as JSON over WS. For production, use `wss://` with TLS termination (nginx/caddy in front of the relay).
- **No gradle.properties or local.properties.** These need to be created for the Android build to work.
- **NanoHTTPD POST body reading.** The current `CommandServer` reads the POST body using `content-length` header and `inputStream.read()`. NanoHTTPD may need `session.parseBody(HashMap())` instead for reliable body reading.

## Build Instructions

### Android
```bash
# Requires: Android SDK, JDK 17, Gradle
cd app
# Create local.properties with: sdk.dir=/path/to/android/sdk
../gradlew assembleDebug
adb install build/outputs/apk/debug/app-debug.apk
```

### Gateway (VM)
```bash
cd gateway
pip install -r requirements.txt
# Start relay first
RELAY_TOKEN=changeme python relay_server.py
# Then start gateway (in another terminal)
PHANTOM_TOUCH_URL=http://localhost:9090 python mcp_server.py
```

## Testing Strategy

1. Build the Android app, fix compilation errors.
2. Install on a test device, complete the 5-step onboarding with relay URL pointing at your VM.
3. On the VM, run `relay_server.py` and verify the phone connects (check logs).
4. Test with curl: `curl -X POST http://localhost:9090/execute -d '{"action":"screenInfo"}'`
5. Test screenshot: `curl -X POST http://localhost:9090/execute -d '{"action":"screenshot","scale":0.5}' | python -c "import sys,json; print(len(json.load(sys.stdin)['result']['image']))"` — should return a large number (base64 length).
6. Test gesture: `curl -X POST http://localhost:9090/execute -d '{"action":"tap","x":540,"y":1200}'`
7. Test batch: `curl -X POST http://localhost:9090/execute -d '{"batch":[{"action":"launchApp","package":"com.android.chrome"},{"action":"delay","ms":3000},{"action":"screenshot"}]}'`
8. Run `tool_gateway.py` with a simple task and verify the LLM receives screenshots and emits correct tool calls.
