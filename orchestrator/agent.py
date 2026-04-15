"""
PhantomTouch Orchestrator
=========================
Python client for the PhantomTouch HTTP API.
Runs on your VM/laptop. Talks to the Android device.

Supports:
  - Raw device control (tap, swipe, type)
  - Chrome/browser automation (openUrl, chromeAction)
  - Vision-LLM-driven automation loops
  - Accessibility tree search (findNode, waitForNode)
"""

import requests
import json
import base64
import time
from typing import Optional


DEVICE_URL = "http://localhost:8080"  # adb forward, or Tailscale IP


class PhantomTouch:
    """Client for the PhantomTouch HTTP API on Android."""

    def __init__(self, base_url: str = DEVICE_URL):
        self.base = base_url.rstrip("/")
        self._screen_w: Optional[int] = None
        self._screen_h: Optional[int] = None

    def health(self) -> dict:
        return requests.get(f"{self.base}/health").json()

    def execute(self, command: dict) -> dict:
        return requests.post(f"{self.base}/execute", json=command, timeout=30).json()

    def batch(self, commands: list[dict]) -> dict:
        return requests.post(f"{self.base}/execute", json={"batch": commands}, timeout=60).json()

    # ── Screen info ────────────────────────────────────────────────

    def screen_info(self) -> tuple[int, int]:
        if not self._screen_w:
            resp = self.execute({"action": "screenInfo"})
            self._screen_w = resp["result"]["width"]
            self._screen_h = resp["result"]["height"]
        return self._screen_w, self._screen_h

    # ── Screenshots ────────────────────────────────────────────────

    def screenshot(self, scale: float = 0.5) -> dict:
        resp = self.execute({"action": "screenshot", "scale": scale})
        if resp.get("success"):
            self._screen_w = resp["result"]["nativeWidth"]
            self._screen_h = resp["result"]["nativeHeight"]
        return resp

    def screenshot_bytes(self, scale: float = 0.5) -> Optional[bytes]:
        """Get screenshot as raw JPEG bytes (for saving to disk or sending to API)."""
        resp = self.screenshot(scale)
        if resp.get("success"):
            return base64.b64decode(resp["result"]["image"])
        return None

    # ── Gestures ───────────────────────────────────────────────────

    def tap(self, x: int, y: int) -> dict:
        return self.execute({"action": "tap", "x": x, "y": y})

    def double_tap(self, x: int, y: int) -> dict:
        return self.execute({"action": "doubleTap", "x": x, "y": y})

    def long_press(self, x: int, y: int, duration_ms: int = 1000) -> dict:
        return self.execute({"action": "longPress", "x": x, "y": y, "durationMs": duration_ms})

    def swipe(self, start_x: int, start_y: int, end_x: int, end_y: int, duration_ms: int = 300) -> dict:
        return self.execute({
            "action": "swipe",
            "startX": start_x, "startY": start_y,
            "endX": end_x, "endY": end_y,
            "durationMs": duration_ms
        })

    def swipe_down(self, pct: float = 0.5, speed_ms: int = 400) -> dict:
        """Scroll down by pct of screen height."""
        w, h = self.screen_info()
        cx = w // 2
        start_y = int(h * 0.8)
        end_y = int(h * (0.8 - pct))
        return self.swipe(cx, start_y, cx, max(end_y, 50), speed_ms)

    def swipe_up(self, pct: float = 0.5, speed_ms: int = 400) -> dict:
        w, h = self.screen_info()
        cx = w // 2
        start_y = int(h * 0.2)
        end_y = int(h * (0.2 + pct))
        return self.swipe(cx, start_y, cx, min(end_y, h - 50), speed_ms)

    def pull_to_refresh(self) -> dict:
        w, h = self.screen_info()
        cx = w // 2
        return self.swipe(cx, int(h * 0.15), cx, int(h * 0.65), 600)

    # ── Text input ─────────────────────────────────────────────────

    def type_text(self, text: str) -> dict:
        return self.execute({"action": "type", "text": text})

    def clear_field(self) -> dict:
        return self.execute({"action": "clearField"})

    def tap_and_type(self, x: int, y: int, text: str,
                     focus_delay_ms: int = 200, verify: bool = True) -> dict:
        """The core pattern: tap a field → wait for focus → type → verify."""
        cmds = [
            {"action": "tap", "x": x, "y": y},
            {"action": "delay", "ms": focus_delay_ms},
            {"action": "type", "text": text},
        ]
        if verify:
            cmds.extend([
                {"action": "delay", "ms": 100},
                {"action": "screenshot", "scale": 0.5}
            ])
        return self.batch(cmds)

    # ── Navigation ─────────────────────────────────────────────────

    def back(self) -> dict:
        return self.execute({"action": "back"})

    def home(self) -> dict:
        return self.execute({"action": "home"})

    # ── App control ────────────────────────────────────────────────

    def launch(self, package: str, wait_sec: float = 3.0) -> dict:
        resp = self.execute({"action": "launchApp", "package": package})
        time.sleep(wait_sec)
        return resp

    # ── Chrome / Browser ───────────────────────────────────────────

    def open_url(self, url: str, new_tab: bool = False, wait_sec: float = 3.0) -> dict:
        """Open a URL in Chrome via Intent. Most reliable navigation method."""
        resp = self.execute({"action": "openUrl", "url": url, "newTab": new_tab})
        time.sleep(wait_sec)
        return resp

    def chrome_new_tab(self) -> dict:
        return self.execute({"action": "chromeNewTab"})

    def chrome_address_bar(self) -> dict:
        """Focus Chrome's address bar (Ctrl+L equivalent)."""
        return self.execute({"action": "chromeAction", "chromeAction": "addressBar"})

    def chrome_refresh(self) -> dict:
        return self.execute({"action": "chromeAction", "chromeAction": "refresh"})

    def chrome_find(self, text: str) -> dict:
        """Open Chrome's find-in-page and search for text."""
        return self.batch([
            {"action": "chromeAction", "chromeAction": "find"},
            {"action": "delay", "ms": 300},
            {"action": "type", "text": text},
        ])

    def chrome_close_tab(self) -> dict:
        return self.execute({"action": "chromeAction", "chromeAction": "closeTab"})

    def chrome_navigate(self, url: str) -> dict:
        """Navigate by focusing address bar, clearing, typing URL, pressing Enter."""
        return self.batch([
            {"action": "chromeAction", "chromeAction": "addressBar"},
            {"action": "delay", "ms": 200},
            {"action": "clearField"},
            {"action": "delay", "ms": 100},
            {"action": "type", "text": url},
            {"action": "delay", "ms": 100},
            {"action": "keyEvent", "keyCode": 66},  # KEYCODE_ENTER
            {"action": "delay", "ms": 2000},
            {"action": "screenshot", "scale": 0.5}
        ])

    def browse_linkedin(self) -> dict:
        """Open LinkedIn's mobile web (avoids app-specific UI quirks)."""
        return self.open_url("https://www.linkedin.com/feed/", wait_sec=4)

    def browse_facebook(self) -> dict:
        """Open Facebook's mobile web."""
        return self.open_url("https://m.facebook.com/", wait_sec=4)

    # ── Accessibility tree ─────────────────────────────────────────

    def dump_tree(self) -> dict:
        return self.execute({"action": "dumpTree"})

    def find_node(self, query: str, clickable: bool = False) -> dict:
        """Search the UI tree for nodes matching text/description."""
        return self.execute({"action": "findNode", "query": query, "clickable": clickable})

    def wait_for_node(self, query: str, timeout_ms: int = 5000) -> dict:
        """Wait for a UI element to appear (polling)."""
        return self.execute({
            "action": "waitForNode", "query": query, "timeoutMs": timeout_ms
        })

    def find_and_tap(self, query: str) -> Optional[dict]:
        """Find a node by text and tap its center. Returns None if not found."""
        resp = self.find_node(query, clickable=True)
        if resp.get("success") and resp["result"]["count"] > 0:
            node = resp["result"]["nodes"][0]  # type: ignore
            if isinstance(node, dict):
                return self.tap(node["centerX"], node["centerY"])
        return None


# ── Vision LLM integration ─────────────────────────────────────────

SYSTEM_PROMPT = """You are an Android device automation agent. You receive screenshots
and an instruction. Respond with a JSON array of commands.

Commands:
  {"action": "tap", "x": INT, "y": INT}
  {"action": "doubleTap", "x": INT, "y": INT}
  {"action": "longPress", "x": INT, "y": INT, "durationMs": INT}
  {"action": "swipe", "startX": INT, "startY": INT, "endX": INT, "endY": INT, "durationMs": INT}
  {"action": "type", "text": STRING}
  {"action": "clearField"}
  {"action": "keyEvent", "keyCode": INT}  (66=Enter, 67=Backspace, 4=Back)
  {"action": "delay", "ms": INT}
  {"action": "back"}
  {"action": "home"}
  {"action": "openUrl", "url": STRING}
  {"action": "chromeAction", "chromeAction": "addressBar|refresh|find|newTab|closeTab"}
  {"action": "screenshot"}
  {"action": "findNode", "query": STRING}
  {"action": "waitForNode", "query": STRING, "timeoutMs": INT}
  []  (empty array = task complete)

RULES:
1. Coordinates are NATIVE screen pixels. Screenshot may be scaled — 
   multiply by (nativeWidth / imageWidth) to convert.
2. Always delay 150-300ms between tap and type.
3. Include screenshot after important actions to verify.
4. For Chrome: prefer openUrl for navigation, use tap/type for page interaction.
5. Respond ONLY with valid JSON array. No markdown. No explanation.
"""


def call_vision_llm(
    screenshot_b64: str,
    instruction: str,
    img_w: int, img_h: int,
    nat_w: int, nat_h: int,
    api_key: str = "",
    model: str = "grok-3-vision",
    api_base: str = "https://api.x.ai/v1",
) -> list[dict]:
    """Send screenshot + instruction to vision LLM, get back commands."""
    import os
    api_key = api_key or os.environ.get("XAI_API_KEY", "")

    resp = requests.post(
        f"{api_base}/chat/completions",
        headers={"Authorization": f"Bearer {api_key}", "Content-Type": "application/json"},
        json={
            "model": model,
            "messages": [
                {"role": "system", "content": SYSTEM_PROMPT},
                {"role": "user", "content": [
                    {"type": "image_url", "image_url": {
                        "url": f"data:image/jpeg;base64,{screenshot_b64}"}},
                    {"type": "text", "text": (
                        f"Screen: {nat_w}x{nat_h} native, "
                        f"image: {img_w}x{img_h} "
                        f"(multiply coords by {nat_w/img_w:.1f}x)\n\n"
                        f"Task: {instruction}"
                    )}
                ]}
            ],
            "temperature": 0.1,
            "max_tokens": 2000
        },
        timeout=30
    )
    resp.raise_for_status()
    content = resp.json()["choices"][0]["message"]["content"].strip()
    if content.startswith("```"):
        content = content.split("\n", 1)[1].rsplit("```", 1)[0]
    return json.loads(content)


def run_task(device: PhantomTouch, instruction: str, max_steps: int = 10, **llm_kwargs):
    """Execute an open-ended task via vision LLM loop."""
    print(f"[task] {instruction}")

    for step in range(max_steps):
        print(f"\n[step {step + 1}/{max_steps}]")

        resp = device.screenshot(scale=0.5)
        if not resp.get("success"):
            print(f"  screenshot failed: {resp}")
            break

        r = resp["result"]
        commands = call_vision_llm(
            r["image"], instruction,
            r["imageWidth"], r["imageHeight"],
            r["nativeWidth"], r["nativeHeight"],
            **llm_kwargs
        )

        if not commands:
            print("  LLM returned [] — task complete")
            break

        print(f"  {len(commands)} commands:")
        for c in commands:
            print(f"    {c}")

        result = device.batch(commands)
        print(f"  allSucceeded={result.get('allSucceeded')}")
        time.sleep(0.5)

    print("[task] done")


# ── Example usage ──────────────────────────────────────────────────

if __name__ == "__main__":
    d = PhantomTouch(DEVICE_URL)
    print(f"Status: {d.health()}")

    # ── Example 1: Browse LinkedIn in Chrome ───────────────────────
    print("\n=== LinkedIn via Chrome ===")
    d.open_url("https://www.linkedin.com/feed/", wait_sec=4)
    d.swipe_down(pct=0.5, speed_ms=400)  # scroll the feed
    time.sleep(1)
    d.screenshot(scale=0.5)  # capture for LLM

    # ── Example 2: Post on LinkedIn via Chrome ─────────────────────
    print("\n=== LinkedIn post via Chrome ===")
    d.open_url("https://www.linkedin.com/feed/", wait_sec=4)
    run_task(d,
        instruction=(
            "I'm on LinkedIn's feed in Chrome. "
            "Find and tap the 'Start a post' area. "
            "Type: 'Building PhantomTouch — an open-source Android automation "
            "layer for LLM agents. Zero root, zero Appium.' "
            "Do NOT submit. Take a verification screenshot."
        ),
        max_steps=6
    )

    # ── Example 3: Browse Facebook in Chrome ───────────────────────
    print("\n=== Facebook via Chrome ===")
    d.browse_facebook()
    d.swipe_down(pct=0.6, speed_ms=500)

    # ── Example 4: Use LinkedIn native app ─────────────────────────
    print("\n=== LinkedIn native app ===")
    d.launch("com.linkedin.android", wait_sec=4)
    run_task(d,
        instruction="Scroll down the LinkedIn feed 3 times slowly, taking a screenshot after each scroll.",
        max_steps=8
    )

    # ── Example 5: Search something in Chrome ──────────────────────
    print("\n=== Chrome search ===")
    d.open_url("https://www.google.com", wait_sec=3)
    # Use findNode to locate the search box, or just tap known coords
    d.find_and_tap("Search")
    time.sleep(0.5)
    d.type_text("PhantomTouch Android automation")
    d.execute({"action": "keyEvent", "keyCode": 66})  # Enter
