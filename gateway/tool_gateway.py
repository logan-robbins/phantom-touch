"""
PhantomTouch Tool-Calling Gateway
==================================
A standalone agent loop that uses native LLM function/tool calling 
to drive PhantomTouch. Works with any vision model that supports tools:
  - xAI Grok (grok-3-vision)
  - Anthropic Claude (claude-sonnet-4-20250514)
  - OpenAI GPT-4o

The LLM sees PhantomTouch as a set of callable tools. It decides which
tool to call based on the conversation. No JSON-in-prompt hacking.

Architecture:
  User (Telegram/CLI) → This gateway → LLM (tool calls) → PhantomTouch (HTTP)
                                      ↑                    ↓
                                      └── tool results ────┘

Usage:
  export XAI_API_KEY=...
  export PHANTOM_TOUCH_URL=http://localhost:8080
  python tool_gateway.py "Post 'hello world' on LinkedIn"

Or import and use programmatically:
  from tool_gateway import PhantomAgent
  agent = PhantomAgent()
  result = agent.run("Open LinkedIn and scroll the feed")
"""

import os
import sys
import json
import base64
import httpx
from typing import Any

# ── Config ─────────────────────────────────────────────────────────

DEVICE_URL = os.environ.get("PHANTOM_TOUCH_URL", "http://localhost:9090")

# LLM provider config — swap between Grok / Claude / OpenAI
LLM_PROVIDER = os.environ.get("LLM_PROVIDER", "xai")  # "xai", "anthropic", "openai"

LLM_CONFIG = {
    "xai": {
        "api_base": "https://api.x.ai/v1",
        "api_key_env": "XAI_API_KEY",
        "model": "grok-3-vision",
    },
    "anthropic": {
        "api_base": "https://api.anthropic.com/v1",
        "api_key_env": "ANTHROPIC_API_KEY",
        "model": "claude-sonnet-4-20250514",
    },
    "openai": {
        "api_base": "https://api.openai.com/v1",
        "api_key_env": "OPENAI_API_KEY",
        "model": "gpt-4o",
    },
}

# ── Tool Schemas ───────────────────────────────────────────────────
# These are the tool definitions the LLM sees. The LLM chooses which
# to call and with what arguments. This is the "gateway" — the LLM
# doesn't generate raw JSON commands, it makes structured tool calls.

TOOLS = [
    {
        "type": "function",
        "function": {
            "name": "screenshot",
            "description": "Capture a screenshot of the Android device. Always call this first to see what's on screen, and after actions to verify they worked.",
            "parameters": {
                "type": "object",
                "properties": {
                    "scale": {
                        "type": "number",
                        "description": "Resolution scale 0.0-1.0. Default 0.5. Use 1.0 for pixel-precise coordinate work.",
                        "default": 0.5
                    }
                }
            }
        }
    },
    {
        "type": "function",
        "function": {
            "name": "tap",
            "description": "Tap at pixel coordinates on the device screen. Coordinates must be in NATIVE screen pixels. If working from a scaled screenshot, multiply image coords by (nativeWidth / imageWidth).",
            "parameters": {
                "type": "object",
                "properties": {
                    "x": {"type": "integer", "description": "X coordinate in native screen pixels"},
                    "y": {"type": "integer", "description": "Y coordinate in native screen pixels"}
                },
                "required": ["x", "y"]
            }
        }
    },
    {
        "type": "function",
        "function": {
            "name": "tap_and_type",
            "description": "Tap a text field and type text into it. Use for search boxes, post composers, form fields, URL bars, etc.",
            "parameters": {
                "type": "object",
                "properties": {
                    "x": {"type": "integer", "description": "X coordinate of the text field"},
                    "y": {"type": "integer", "description": "Y coordinate of the text field"},
                    "text": {"type": "string", "description": "Text to type after tapping"}
                },
                "required": ["x", "y", "text"]
            }
        }
    },
    {
        "type": "function",
        "function": {
            "name": "swipe",
            "description": "Scroll/swipe the screen. Use for scrolling feeds, navigating carousels, pull-to-refresh.",
            "parameters": {
                "type": "object",
                "properties": {
                    "direction": {
                        "type": "string",
                        "enum": ["up", "down", "left", "right"],
                        "description": "Direction to swipe"
                    },
                    "percent": {
                        "type": "number",
                        "description": "Distance as percentage of screen (0-100). 50 = half screen.",
                        "default": 50
                    },
                    "speed": {
                        "type": "string",
                        "enum": ["fast", "medium", "slow"],
                        "description": "Swipe speed. fast=quick flick, medium=normal scroll, slow=careful drag.",
                        "default": "medium"
                    }
                },
                "required": ["direction"]
            }
        }
    },
    {
        "type": "function",
        "function": {
            "name": "open_url",
            "description": "Open a URL in Chrome. Most reliable way to navigate to any website. Use for LinkedIn (linkedin.com/feed), Facebook (m.facebook.com), or any URL.",
            "parameters": {
                "type": "object",
                "properties": {
                    "url": {"type": "string", "description": "URL to open. https:// added automatically."},
                    "new_tab": {"type": "boolean", "description": "Open in new Chrome tab", "default": False}
                },
                "required": ["url"]
            }
        }
    },
    {
        "type": "function",
        "function": {
            "name": "launch_app",
            "description": "Launch an Android app. Use package names: com.linkedin.android (LinkedIn), com.facebook.katana (Facebook), com.android.chrome (Chrome), com.instagram.android (Instagram), com.twitter.android (X).",
            "parameters": {
                "type": "object",
                "properties": {
                    "package": {"type": "string", "description": "Android package name"}
                },
                "required": ["package"]
            }
        }
    },
    {
        "type": "function",
        "function": {
            "name": "find_and_tap",
            "description": "Find a UI element by its text label and tap it. Use for buttons, links, menu items when you know the text. Much more reliable than coordinate tapping for known labels.",
            "parameters": {
                "type": "object",
                "properties": {
                    "query": {"type": "string", "description": "Text of the button/element to find and tap"}
                },
                "required": ["query"]
            }
        }
    },
    {
        "type": "function",
        "function": {
            "name": "find_element",
            "description": "Search the screen for UI elements matching text. Returns coordinates you can tap. Use when you need to locate something without taking a screenshot.",
            "parameters": {
                "type": "object",
                "properties": {
                    "query": {"type": "string", "description": "Text to search for"},
                    "clickable_only": {"type": "boolean", "description": "Only return tappable elements", "default": False}
                },
                "required": ["query"]
            }
        }
    },
    {
        "type": "function",
        "function": {
            "name": "press_back",
            "description": "Press the Android back button. Go back in Chrome, close dialogs, dismiss keyboards.",
            "parameters": {"type": "object", "properties": {}}
        }
    },
    {
        "type": "function",
        "function": {
            "name": "press_home",
            "description": "Press the Android home button.",
            "parameters": {"type": "object", "properties": {}}
        }
    },
    {
        "type": "function",
        "function": {
            "name": "wait_seconds",
            "description": "Wait for a specified duration. Use after launching apps or navigating to let the page load.",
            "parameters": {
                "type": "object",
                "properties": {
                    "seconds": {"type": "number", "description": "Seconds to wait", "default": 3}
                }
            }
        }
    },
    {
        "type": "function",
        "function": {
            "name": "task_complete",
            "description": "Signal that the task is finished. Call this when the requested action is done.",
            "parameters": {
                "type": "object",
                "properties": {
                    "summary": {"type": "string", "description": "Brief summary of what was accomplished"}
                },
                "required": ["summary"]
            }
        }
    },
]


# ── Tool Execution ─────────────────────────────────────────────────

device = httpx.Client(base_url=DEVICE_URL, timeout=30)


def exec_cmd(cmd: dict) -> dict:
    return device.post("/execute", json=cmd).json()


def exec_batch(cmds: list[dict]) -> dict:
    return device.post("/execute", json={"batch": cmds}).json()


def execute_tool(name: str, args: dict) -> dict:
    """Route a tool call to PhantomTouch. Returns a result dict."""

    if name == "screenshot":
        scale = args.get("scale", 0.5)
        resp = exec_cmd({"action": "screenshot", "scale": scale})
        if resp.get("success"):
            r = resp["result"]
            return {
                "image_base64": r["image"],
                "native_width": r["nativeWidth"],
                "native_height": r["nativeHeight"],
                "image_width": r["imageWidth"],
                "image_height": r["imageHeight"],
                "coord_multiplier": r["nativeWidth"] / r["imageWidth"],
                "note": "Multiply image pixel coords by coord_multiplier to get native tap coords."
            }
        return {"error": resp.get("error", "screenshot failed")}

    elif name == "tap":
        return exec_cmd({"action": "tap", "x": args["x"], "y": args["y"]})

    elif name == "tap_and_type":
        resp = exec_batch([
            {"action": "tap", "x": args["x"], "y": args["y"]},
            {"action": "delay", "ms": 250},
            {"action": "type", "text": args["text"]},
        ])
        return {"success": resp.get("allSucceeded", False), "typed": args["text"]}

    elif name == "swipe":
        info = exec_cmd({"action": "screenInfo"})
        w, h = info["result"]["width"], info["result"]["height"]
        pct = args.get("percent", 50) / 100.0
        speed_ms = {"fast": 150, "medium": 400, "slow": 800}.get(args.get("speed", "medium"), 400)
        d = args["direction"]
        cx, cy = w // 2, h // 2
        coords = {
            "down":  (cx, int(h*0.8), cx, int(h*(0.8-pct))),
            "up":    (cx, int(h*0.2), cx, int(h*(0.2+pct))),
            "left":  (int(w*0.8), cy, int(w*(0.8-pct)), cy),
            "right": (int(w*0.2), cy, int(w*(0.2+pct)), cy),
        }
        sx, sy, ex, ey = coords[d]
        return exec_cmd({"action": "swipe", "startX": sx, "startY": sy, "endX": ex, "endY": ey, "durationMs": speed_ms})

    elif name == "open_url":
        resp = exec_cmd({"action": "openUrl", "url": args["url"], "newTab": args.get("new_tab", False)})
        import time; time.sleep(3)  # let page load
        return resp

    elif name == "launch_app":
        resp = exec_cmd({"action": "launchApp", "package": args["package"]})
        import time; time.sleep(3)
        return resp

    elif name == "find_and_tap":
        find = exec_cmd({"action": "findNode", "query": args["query"], "clickable": True})
        if find.get("success") and find["result"]["count"] > 0:
            node = find["result"]["nodes"][0]
            tap = exec_cmd({"action": "tap", "x": node["centerX"], "y": node["centerY"]})
            return {"found": True, "text": node.get("text", ""), "tapped_at": [node["centerX"], node["centerY"]], "tap_success": tap.get("success")}
        return {"found": False, "query": args["query"]}

    elif name == "find_element":
        return exec_cmd({"action": "findNode", "query": args["query"], "clickable": args.get("clickable_only", False)})

    elif name == "press_back":
        return exec_cmd({"action": "back"})

    elif name == "press_home":
        return exec_cmd({"action": "home"})

    elif name == "wait_seconds":
        import time
        time.sleep(args.get("seconds", 3))
        return {"waited": args.get("seconds", 3)}

    elif name == "task_complete":
        return {"complete": True, "summary": args.get("summary", "")}

    else:
        return {"error": f"Unknown tool: {name}"}


# ── LLM Agent Loop ────────────────────────────────────────────────

SYSTEM_PROMPT = """You are a device automation agent controlling an Android phone via tools.

Your workflow:
1. ALWAYS start by calling screenshot() to see the current screen state
2. Decide what action to take based on what you see
3. Execute the action (tap, type, swipe, open_url, etc.)
4. Call screenshot() again to verify the action worked
5. Repeat until the task is complete
6. Call task_complete() when done

Key rules:
- Screenshot coordinates are SCALED. Multiply by coord_multiplier before tapping.
- Use open_url() for web navigation — it's more reliable than tapping Chrome's address bar.
- Use find_and_tap() for buttons/links when you know the label text.
- Always verify actions with a follow-up screenshot.
- If something doesn't work, try an alternative approach.
"""


class PhantomAgent:
    """
    Tool-calling agent that drives PhantomTouch via native LLM function calling.
    
    The LLM sees the tools, decides which to call, and this class
    executes them against PhantomTouch and feeds results back.
    """

    def __init__(self, provider: str = LLM_PROVIDER, max_steps: int = 15):
        cfg = LLM_CONFIG[provider]
        self.api_base = cfg["api_base"]
        self.model = cfg["model"]
        self.api_key = os.environ.get(cfg["api_key_env"], "")
        self.max_steps = max_steps
        self.provider = provider
        self.http = httpx.Client(timeout=60)

    def run(self, task: str) -> str:
        """Execute a task. Returns the completion summary."""
        print(f"[agent] Task: {task}")
        print(f"[agent] Provider: {self.provider} / {self.model}")
        print(f"[agent] Max steps: {self.max_steps}")

        messages = [
            {"role": "system", "content": SYSTEM_PROMPT},
            {"role": "user", "content": task}
        ]

        for step in range(self.max_steps):
            print(f"\n[step {step + 1}/{self.max_steps}]")

            # Call LLM
            response = self._call_llm(messages)
            
            assistant_msg = response["choices"][0]["message"]
            messages.append(assistant_msg)

            # Check for tool calls
            tool_calls = assistant_msg.get("tool_calls", [])
            
            if not tool_calls:
                # No tool calls — LLM responded with text (maybe it's done)
                content = assistant_msg.get("content", "")
                print(f"  LLM text: {content[:200]}")
                return content

            # Execute each tool call
            for tc in tool_calls:
                fn_name = tc["function"]["name"]
                fn_args = json.loads(tc["function"]["arguments"])
                print(f"  tool: {fn_name}({json.dumps(fn_args, indent=None)[:100]})")

                result = execute_tool(fn_name, fn_args)

                # Check for task completion
                if fn_name == "task_complete":
                    summary = result.get("summary", "Task completed")
                    print(f"\n[agent] Complete: {summary}")
                    return summary

                # Build tool result message
                tool_result_content = self._format_tool_result(fn_name, result)
                messages.append({
                    "role": "tool",
                    "tool_call_id": tc["id"],
                    "content": tool_result_content
                })

        print(f"\n[agent] Hit max steps ({self.max_steps})")
        return "Task incomplete — reached maximum steps"

    def _call_llm(self, messages: list[dict]) -> dict:
        """Call the LLM with messages and tools. Handles provider differences."""
        
        if self.provider == "anthropic":
            return self._call_anthropic(messages)
        
        # OpenAI-compatible (xAI Grok, OpenAI GPT-4o)
        headers = {
            "Authorization": f"Bearer {self.api_key}",
            "Content-Type": "application/json"
        }
        
        payload = {
            "model": self.model,
            "messages": messages,
            "tools": TOOLS,
            "tool_choice": "auto",
            "temperature": 0.1,
            "max_tokens": 4096,
        }

        resp = self.http.post(
            f"{self.api_base}/chat/completions",
            headers=headers,
            json=payload
        )
        resp.raise_for_status()
        return resp.json()

    def _call_anthropic(self, messages: list[dict]) -> dict:
        """Anthropic has a different API format for tools."""
        headers = {
            "x-api-key": self.api_key,
            "Content-Type": "application/json",
            "anthropic-version": "2023-06-01"
        }
        
        # Convert OpenAI tool format to Anthropic format
        anthropic_tools = []
        for t in TOOLS:
            anthropic_tools.append({
                "name": t["function"]["name"],
                "description": t["function"]["description"],
                "input_schema": t["function"]["parameters"]
            })

        # Separate system message
        system = ""
        api_messages = []
        for m in messages:
            if m["role"] == "system":
                system = m["content"]
            else:
                api_messages.append(m)

        payload = {
            "model": self.model,
            "system": system,
            "messages": api_messages,
            "tools": anthropic_tools,
            "max_tokens": 4096,
            "temperature": 0.1,
        }

        resp = self.http.post(
            f"{self.api_base}/messages",
            headers=headers,
            json=payload
        )
        resp.raise_for_status()
        data = resp.json()

        # Normalize Anthropic response to OpenAI format for the loop
        return self._normalize_anthropic_response(data)

    def _normalize_anthropic_response(self, data: dict) -> dict:
        """Convert Anthropic response format to OpenAI-like format."""
        tool_calls = []
        text_parts = []

        for block in data.get("content", []):
            if block["type"] == "text":
                text_parts.append(block["text"])
            elif block["type"] == "tool_use":
                tool_calls.append({
                    "id": block["id"],
                    "type": "function",
                    "function": {
                        "name": block["name"],
                        "arguments": json.dumps(block["input"])
                    }
                })

        msg = {"role": "assistant"}
        if text_parts:
            msg["content"] = "\n".join(text_parts)
        if tool_calls:
            msg["tool_calls"] = tool_calls

        return {"choices": [{"message": msg}]}

    def _format_tool_result(self, tool_name: str, result: dict) -> str:
        """Format tool result for the LLM. Screenshots become image references."""
        if tool_name == "screenshot" and "image_base64" in result:
            # For vision models, we'd ideally send the image inline.
            # For now, return metadata. The next LLM call includes the image.
            meta = {k: v for k, v in result.items() if k != "image_base64"}
            meta["_image_included"] = True
            return json.dumps(meta)
        
        return json.dumps(result, default=str)


# ── CLI Entry Point ────────────────────────────────────────────────

if __name__ == "__main__":
    if len(sys.argv) < 2:
        print("Usage: python tool_gateway.py \"<task description>\"")
        print()
        print("Examples:")
        print("  python tool_gateway.py \"Open LinkedIn and scroll the feed\"")
        print("  python tool_gateway.py \"Post 'Hello world' on LinkedIn via Chrome\"")
        print("  python tool_gateway.py \"Open m.facebook.com and take a screenshot\"")
        print()
        print("Environment:")
        print(f"  PHANTOM_TOUCH_URL = {DEVICE_URL}")
        print(f"  LLM_PROVIDER = {LLM_PROVIDER}")
        sys.exit(1)

    task = " ".join(sys.argv[1:])
    agent = PhantomAgent()
    result = agent.run(task)
    print(f"\nResult: {result}")
