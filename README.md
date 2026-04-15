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
│  │ MCP Server or │                 │  WebSocket                   │
│  │ Tool Gateway  │─── localhost ──→│  (outbound from phone)       │
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

### 3. Gateway Setup (on the VM)

**MCP Server** (for OpenClaw):
```bash
# Point at the relay instead of the phone directly
export PHANTOM_TOUCH_URL=http://localhost:9090
python gateway/mcp_server.py
```

**Standalone Agent** (CLI):
```bash
export PHANTOM_TOUCH_URL=http://localhost:9090
export XAI_API_KEY=...
python gateway/tool_gateway.py "Open LinkedIn and scroll the feed"
```

The gateway doesn't know the phone is remote — it just hits `localhost:9090`,
which the relay proxies to the phone over WebSocket.

## How the LLM Emits Commands

The LLM never writes raw JSON commands. Instead:

**With MCP (OpenClaw/Claude):** The LLM sees tools like `phantom_tap()`, 
`phantom_open_url()`, `phantom_screenshot()` as native callable functions. 
It decides which to call based on the conversation. The MCP server translates 
tool calls to PhantomTouch HTTP requests.

**With Tool Calling (Grok/GPT-4o/Claude API):** The gateway defines 12 tools 
as function schemas. The LLM makes structured `tool_calls` in its response. 
The gateway executes them and feeds results back. The LLM sees screenshot 
images and decides the next action. This is a standard agent loop.

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
LLM → tool_call: task_complete(summary="Typed 'hello world' in LinkedIn post composer")
```

## Command Reference

| Command | Description |
|---------|-------------|
| `tap` | Tap at (x, y) coordinates |
| `doubleTap` | Double tap |
| `longPress` | Long press with configurable duration |
| `swipe` | Swipe with start/end coords and duration (speed) |
| `pinch` | Two-finger pinch (zoom in/out) |
| `type` | Type text into focused field |
| `clearField` | Select all + delete |
| `keyEvent` | Send Android key code (66=Enter, 4=Back) |
| `back` / `home` / `recents` | System navigation |
| `launchApp` | Launch app by package name |
| `openUrl` | Open URL in Chrome via Intent |
| `chromeNewTab` | New Chrome tab |
| `chromeAction` | Chrome keyboard shortcuts (addressBar, refresh, find, etc) |
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
│   │       ├── MainActivity.kt        # 5-step onboarding UI
│   │       ├── model/Command.kt       # Command protocol
│   │       ├── executor/CommandExecutor.kt
│   │       ├── relay/RelayClient.kt   # Outbound WS to VM
│   │       ├── server/CommandServer.kt # Local HTTP (optional)
│   │       └── service/
│   │           ├── GestureService.kt   # AccessibilityService
│   │           ├── ScreenCapture.kt    # MediaProjection
│   │           └── CaptureService.kt   # Foreground service
│   └── build.gradle
├── gateway/                      # Runs on VM (Python)
│   ├── relay_server.py          # WS relay (phone ↔ gateway bridge)
│   ├── mcp_server.py            # MCP tools for OpenClaw/Claude
│   ├── tool_gateway.py          # Standalone tool-calling agent
│   ├── skill.yaml               # OpenClaw skill config
│   └── requirements.txt
├── orchestrator/                 # Raw Python client (for direct HTTP)
│   └── agent.py
├── build.gradle
└── settings.gradle
```

## Dependencies

**Android app:** NanoHTTPD (local HTTP), OkHttp (outbound WebSocket), kotlinx-coroutines, Android SDK.

**VM gateway:** httpx, websockets, aiohttp, mcp (Python). No LangChain. No LangGraph.
