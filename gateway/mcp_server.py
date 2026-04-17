"""
PhantomTouch MCP Server
========================
Exposes PhantomTouch device control as MCP (Model Context Protocol) tools.
Any MCP-compatible host (OpenClaw, Claude Desktop, etc.) can use these tools
natively — the LLM sees them as first-class callable functions.

Run:
  pip install mcp httpx
  python mcp_server.py

Then add to your OpenClaw skill config or Claude Desktop config:
  {
    "mcpServers": {
      "phantom-touch": {
        "command": "python",
        "args": ["/path/to/mcp_server.py"],
        "env": {"PHANTOM_TOUCH_URL": "http://localhost:8080"}
      }
    }
  }

The LLM will see tools like:
  phantom_screenshot()
  phantom_tap(x, y)
  phantom_open_url(url)
  phantom_swipe_down(percent, speed)
  etc.

It decides when to call them based on the user's request.
No prompt engineering needed — tool descriptions do the work.
"""

import os
import json
import base64
import httpx
from mcp.server import Server
from mcp.server.stdio import run_server
from mcp.types import Tool, TextContent, ImageContent

DEVICE_URL = os.environ.get("PHANTOM_TOUCH_URL", "http://localhost:9090")
client = httpx.Client(base_url=DEVICE_URL, timeout=30)

app = Server("phantom-touch")


def device_exec(command: dict) -> dict:
    """Execute a command against PhantomTouch HTTP API."""
    try:
        resp = client.post("/execute", json=command)
        resp.raise_for_status()
        return resp.json()
    except httpx.ConnectError:
        return {"success": False, "error": "Cannot reach relay server. Is relay_server.py running?"}
    except httpx.TimeoutException:
        return {"success": False, "error": "Request timed out. Phone may be unresponsive."}
    except httpx.HTTPStatusError as e:
        if e.response.status_code == 503:
            return {"success": False, "error": "Phone not connected to relay."}
        return {"success": False, "error": f"HTTP {e.response.status_code}: {e.response.text[:200]}"}


def device_batch(commands: list[dict]) -> dict:
    try:
        resp = client.post("/execute", json={"batch": commands})
        resp.raise_for_status()
        return resp.json()
    except httpx.ConnectError:
        return {"success": False, "error": "Cannot reach relay server. Is relay_server.py running?"}
    except httpx.TimeoutException:
        return {"success": False, "error": "Batch request timed out. Phone may be unresponsive."}
    except httpx.HTTPStatusError as e:
        if e.response.status_code == 503:
            return {"success": False, "error": "Phone not connected to relay."}
        return {"success": False, "error": f"HTTP {e.response.status_code}: {e.response.text[:200]}"}


# ── Tool Definitions ───────────────────────────────────────────────
# Each tool has a clear description so the LLM knows WHEN to use it.

@app.tool()
async def phantom_screenshot(scale: float = 0.5) -> list[TextContent | ImageContent]:
    """
    Capture a screenshot of the Android device screen.
    Returns the image so you can see what's currently displayed.
    Use this FIRST to understand the current screen state before acting.
    Use after actions to verify they worked.
    
    Args:
        scale: Resolution multiplier 0.0-1.0. Use 0.5 for normal, 1.0 for pixel-precise work.
    """
    resp = device_exec({"action": "screenshot", "scale": scale})
    if resp.get("success"):
        r = resp["result"]
        return [
            ImageContent(
                type="image",
                data=r["image"],
                mimeType="image/jpeg"
            ),
            TextContent(
                type="text",
                text=f"Screenshot captured. Native screen: {r['nativeWidth']}x{r['nativeHeight']}. "
                     f"Image: {r['imageWidth']}x{r['imageHeight']}. "
                     f"To tap, multiply image coordinates by {r['nativeWidth']/r['imageWidth']:.1f}."
            )
        ]
    return [TextContent(type="text", text=f"Screenshot failed: {resp.get('error')}")]


@app.tool()
async def phantom_tap(x: int, y: int) -> list[TextContent]:
    """
    Tap at exact pixel coordinates on the device screen.
    Coordinates are in NATIVE screen pixels (not screenshot pixels).
    If working from a screenshot, multiply image coords by the scale factor.
    
    Args:
        x: X coordinate in native screen pixels
        y: Y coordinate in native screen pixels
    """
    resp = device_exec({"action": "tap", "x": x, "y": y})
    return [TextContent(type="text", text=f"Tap at ({x}, {y}): {'success' if resp.get('success') else 'failed'}")]


@app.tool()
async def phantom_tap_and_type(x: int, y: int, text: str) -> list[TextContent | ImageContent]:
    """
    Tap a text field at (x, y), wait for focus, then type text.
    Use this for filling in forms, search boxes, post composers, etc.
    Takes a verification screenshot after typing.
    
    Args:
        x: X coordinate of the text field (native pixels)
        y: Y coordinate of the text field (native pixels)
        text: Text to type into the field
    """
    resp = device_batch([
        {"action": "tap", "x": x, "y": y},
        {"action": "delay", "ms": 250},
        {"action": "type", "text": text},
        {"action": "delay", "ms": 150},
        {"action": "screenshot", "scale": 0.5}
    ])

    if not resp.get("batch"):
        # Error or single result (shouldn't happen for batch, but handle gracefully)
        return [TextContent(type="text", text=f"Tap+type failed: {resp.get('error', 'unknown error')}")]

    results = resp.get("results", [])
    output = []

    # Find the screenshot in batch results
    for r in results:
        if isinstance(r, dict) and r.get("action") == "screenshot" and r.get("success"):
            result_data = r.get("result", {})
            if "image" in result_data:
                output.append(ImageContent(
                    type="image",
                    data=result_data["image"],
                    mimeType="image/jpeg"
                ))

    succeeded = resp.get("allSucceeded", False)
    output.append(TextContent(
        type="text",
        text=f"Tap ({x},{y}) + type '{text[:50]}': {'success' if succeeded else 'failed'}"
    ))
    return output


@app.tool()
async def phantom_swipe(direction: str, percent: float = 50, speed: str = "medium") -> list[TextContent]:
    """
    Swipe/scroll the screen in a direction.
    
    Args:
        direction: "up", "down", "left", or "right"
        percent: How far to swipe as percentage of screen (0-100). 50 = half screen.
        speed: "fast" (150ms), "medium" (400ms), "slow" (800ms), "drag" (1500ms)
    """
    # Get screen dimensions
    info = device_exec({"action": "screenInfo"})
    if not info.get("success"):
        return [TextContent(type="text", text=f"Swipe failed: {info.get('error', 'could not get screen info')}")]
    w = info["result"]["width"]
    h = info["result"]["height"]
    
    pct = percent / 100.0
    speed_ms = {"fast": 150, "medium": 400, "slow": 800, "drag": 1500}.get(speed, 400)
    cx, cy = w // 2, h // 2
    
    coords = {
        "down":  (cx, int(h * 0.8), cx, int(h * (0.8 - pct))),
        "up":    (cx, int(h * 0.2), cx, int(h * (0.2 + pct))),
        "left":  (int(w * 0.8), cy, int(w * (0.8 - pct)), cy),
        "right": (int(w * 0.2), cy, int(w * (0.2 + pct)), cy),
    }
    
    if direction not in coords:
        return [TextContent(type="text", text=f"Invalid direction: {direction}")]
    
    sx, sy, ex, ey = coords[direction]
    resp = device_exec({
        "action": "swipe",
        "startX": sx, "startY": sy, "endX": ex, "endY": ey,
        "durationMs": speed_ms
    })
    return [TextContent(type="text", text=f"Swipe {direction} {percent}% at {speed}: {'success' if resp.get('success') else 'failed'}")]


@app.tool()
async def phantom_open_url(url: str, new_tab: bool = False) -> list[TextContent]:
    """
    Open a URL in Chrome browser on the device.
    This is the most reliable way to navigate — uses Android Intent, not UI tapping.
    Use for LinkedIn (linkedin.com), Facebook (m.facebook.com), or any website.
    
    Args:
        url: URL to open (https:// prefix added automatically if missing)
        new_tab: Whether to open in a new tab
    """
    resp = device_exec({"action": "openUrl", "url": url, "newTab": new_tab})
    return [TextContent(type="text", text=f"Opening {url}: {'success' if resp.get('success') else 'failed'}. Wait 3-5 seconds for page load, then take a screenshot.")]


@app.tool()
async def phantom_launch_app(package_name: str) -> list[TextContent]:
    """
    Launch an Android app by package name.
    Common packages:
      - com.linkedin.android (LinkedIn)
      - com.facebook.katana (Facebook)
      - com.android.chrome (Chrome)
      - com.instagram.android (Instagram)
      - com.twitter.android (X/Twitter)
      - com.whatsapp (WhatsApp)
    
    Args:
        package_name: Android package name of the app to launch
    """
    resp = device_exec({"action": "launchApp", "package": package_name})
    return [TextContent(type="text", text=f"Launch {package_name}: {'success' if resp.get('success') else resp.get('error', 'failed')}. Wait 3-5 seconds, then screenshot.")]


@app.tool()
async def phantom_find_element(query: str, clickable_only: bool = False) -> list[TextContent]:
    """
    Search the screen's UI tree for elements matching a text query.
    Returns element positions (centerX, centerY) you can tap.
    Much faster than taking a screenshot for known UI elements.
    
    Args:
        query: Text to search for in element text, description, or ID
        clickable_only: If true, only return elements that can be tapped
    """
    resp = device_exec({"action": "findNode", "query": query, "clickable": clickable_only})
    if resp.get("success"):
        r = resp["result"]
        if r["count"] == 0:
            return [TextContent(type="text", text=f"No elements found matching '{query}'. Try a screenshot to see what's on screen.")]
        
        lines = [f"Found {r['count']} element(s) matching '{query}':"]
        for i, node in enumerate(r["nodes"]):
            lines.append(
                f"  [{i}] text='{node.get('text','')}' desc='{node.get('desc','')}' "
                f"center=({node['centerX']}, {node['centerY']}) "
                f"clickable={node.get('clickable', False)}"
            )
        return [TextContent(type="text", text="\n".join(lines))]
    return [TextContent(type="text", text=f"Find failed: {resp.get('error')}")]


@app.tool()
async def phantom_find_and_tap(query: str) -> list[TextContent]:
    """
    Find a UI element by text and tap it. Convenience combo of find + tap.
    Use for buttons, links, menu items when you know the label text.
    
    Args:
        query: Text of the element to find and tap (e.g., "Post", "Search", "Sign in")
    """
    find_resp = device_exec({"action": "findNode", "query": query, "clickable": True})
    if find_resp.get("success") and find_resp["result"]["count"] > 0:
        node = find_resp["result"]["nodes"][0]
        tap_resp = device_exec({"action": "tap", "x": node["centerX"], "y": node["centerY"]})
        return [TextContent(
            type="text",
            text=f"Found '{node.get('text', query)}' at ({node['centerX']}, {node['centerY']}) and tapped: "
                 f"{'success' if tap_resp.get('success') else 'failed'}"
        )]
    return [TextContent(type="text", text=f"Could not find clickable element matching '{query}'. Take a screenshot to see the screen.")]


@app.tool()
async def phantom_press_back() -> list[TextContent]:
    """Press the Android back button. Use to go back in Chrome, close dialogs, dismiss keyboards."""
    resp = device_exec({"action": "back"})
    return [TextContent(type="text", text=f"Back: {'success' if resp.get('success') else 'failed'}")]


@app.tool()
async def phantom_press_home() -> list[TextContent]:
    """Press the Android home button. Returns to the home screen."""
    resp = device_exec({"action": "home"})
    return [TextContent(type="text", text=f"Home: {'success' if resp.get('success') else 'failed'}")]


@app.tool()
async def phantom_wait_for_element(query: str, timeout_seconds: float = 5.0) -> list[TextContent]:
    """
    Wait for a UI element to appear on screen (polling).
    Use after navigation or app launch to confirm the page loaded.
    
    Args:
        query: Text to wait for (e.g., "linkedin.com", "Search", "Feed")
        timeout_seconds: How long to wait before giving up
    """
    resp = device_exec({
        "action": "waitForNode",
        "query": query,
        "timeoutMs": int(timeout_seconds * 1000)
    })
    if resp.get("success"):
        node = resp.get("result", {})
        return [TextContent(
            type="text",
            text=f"Element '{query}' appeared at ({node.get('centerX')}, {node.get('centerY')})"
        )]
    return [TextContent(type="text", text=f"Element '{query}' did not appear within {timeout_seconds}s")]


@app.tool()
async def phantom_chrome_action(action: str) -> list[TextContent]:
    """
    Execute a Chrome browser action.
    
    Args:
        action: One of: "addressBar" (focus URL bar), "refresh", "find" (find in page),
                "newTab", "closeTab", "back" (browser back), "forward" (browser forward)
    """
    resp = device_exec({"action": "chromeAction", "chromeAction": action})
    return [TextContent(type="text", text=f"Chrome {action}: {'success' if resp.get('success') else 'failed'}")]


@app.tool()
async def phantom_click(query: str, index: int = 0) -> list[TextContent]:
    """
    Click a UI element by its text label using accessibility ACTION_CLICK.
    More reliable than coordinate-based tap — use this when you know the element's text.
    Works like TalkBack: finds the element in the UI tree and clicks it directly.

    Args:
        query: Text of the element to click (e.g., "Post", "Sign in", "Search Google")
        index: Which match to click if multiple found (0 = first, default)
    """
    resp = device_exec({"action": "clickNode", "query": query, "index": index})
    if resp.get("success"):
        r = resp.get("result", {})
        return [TextContent(type="text", text=f"Clicked '{r.get('clickedText', query)}' at ({r.get('centerX')}, {r.get('centerY')})")]
    return [TextContent(type="text", text=f"Click failed: {resp.get('error')}. Try phantom_tap with coordinates instead.")]


@app.tool()
async def phantom_long_click(query: str, index: int = 0) -> list[TextContent]:
    """
    Long-press a UI element by its text label using accessibility ACTION_LONG_CLICK.
    Use for context menus, drag handles, edit modes.

    Args:
        query: Text of the element to long-press
        index: Which match to long-press if multiple found (0 = first)
    """
    resp = device_exec({"action": "longClickNode", "query": query, "index": index})
    if resp.get("success"):
        return [TextContent(type="text", text=f"Long-clicked '{query}'")]
    return [TextContent(type="text", text=f"Long-click failed: {resp.get('error')}")]


@app.tool()
async def phantom_fill_field(query: str, text: str) -> list[TextContent | ImageContent]:
    """
    Find a text field by its label/placeholder and type text into it.
    Focuses the field (ACTION_FOCUS), then commits one character at a time
    through the AccessibilityInputConnection — the same IME path Voice Access
    uses — with a human-scale inter-keystroke cadence.
    No coordinate math needed — just provide the field's label text.
    Takes a verification screenshot after filling.

    Args:
        query: Label or placeholder text of the field (e.g., "Search Google or type URL", "Email", "Password")
        text: Text to type into the field
    """
    resp = device_exec({"action": "setNodeText", "query": query, "text": text})
    output = []
    if resp.get("success"):
        output.append(TextContent(type="text", text=f"Filled '{query}' with '{text[:50]}'"))
    else:
        output.append(TextContent(type="text", text=f"Fill failed: {resp.get('error')}"))
    # Take verification screenshot
    ss = device_exec({"action": "screenshot", "scale": 0.5})
    if ss.get("success"):
        output.append(ImageContent(type="image", data=ss["result"]["image"], mimeType="image/jpeg"))
    return output


@app.tool()
async def phantom_scroll(direction: str, times: int = 1, container_query: str = "") -> list[TextContent]:
    """
    Scroll using accessibility actions (like Switch Access does).
    More natural than coordinate-based swipe. The app decides scroll amount.
    Use for scrolling feeds (LinkedIn, Facebook, YouTube), lists, and pages.

    Args:
        direction: "up", "down", "left", or "right"
        times: How many scroll steps (default 1)
        container_query: Optional text to find specific scrollable container (empty = first scrollable)
    """
    resp = device_exec({"action": "scrollNode", "direction": direction, "times": times, "query": container_query})
    if resp.get("success"):
        return [TextContent(type="text", text=f"Scrolled {direction} {times}x")]
    return [TextContent(type="text", text=f"Scroll failed: {resp.get('error')}. Try phantom_swipe as fallback.")]


@app.tool()
async def phantom_dismiss(query: str = "") -> list[TextContent]:
    """
    Dismiss a dialog, popup, or overlay using accessibility ACTION_DISMISS.
    Use instead of pressing back when you want to close a modal without affecting navigation.

    Args:
        query: Text of the dialog to dismiss (empty = dismiss any focused overlay)
    """
    resp = device_exec({"action": "dismissNode", "query": query})
    if resp.get("success"):
        return [TextContent(type="text", text=f"Dismissed{' ' + query if query else ' overlay'}")]
    return [TextContent(type="text", text=f"Dismiss failed: {resp.get('error')}. Try phantom_press_back instead.")]


@app.tool()
async def phantom_batch(commands_json: str) -> list[TextContent]:
    """
    Execute multiple commands in sequence. Use for complex multi-step interactions
    that need precise timing (e.g., tap → delay → type → screenshot).
    
    Args:
        commands_json: JSON string of command array, e.g.:
            '[{"action":"tap","x":540,"y":800},{"action":"delay","ms":200},{"action":"type","text":"hello"}]'
    """
    try:
        commands = json.loads(commands_json)
    except json.JSONDecodeError as e:
        return [TextContent(type="text", text=f"Invalid JSON: {e}")]
    
    resp = device_batch(commands)
    if not resp.get("batch"):
        return [TextContent(type="text", text=f"Batch failed: {resp.get('error', 'unknown error')}")]

    results = resp.get("results", [])
    summaries = [f"{r.get('action', '?')}:{r.get('success', '?')}" for r in results]
    return [TextContent(
        type="text",
        text=f"Batch of {len(commands)} commands: "
             f"{'all succeeded' if resp.get('allSucceeded') else 'some failed'}. "
             f"Results: {json.dumps(summaries)}"
    )]


# ── Run ────────────────────────────────────────────────────────────

if __name__ == "__main__":
    import asyncio
    asyncio.run(run_server(app))
