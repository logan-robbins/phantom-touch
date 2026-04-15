"""
PhantomTouch Relay Server
==========================
Runs on your Azure VM (or any server with a public IP).

Architecture:
  ┌─────────────────────────────────┐
  │  Azure VM                       │
  │                                 │
  │  ┌───────────┐  ┌────────────┐  │
  │  │ Gateway   │→ │ Relay      │  │
  │  │ (MCP /    │  │ :9090 HTTP │←─── LLM tool calls come in here
  │  │  Agent)   │  │ :9091 WS   │←─── Phone connects here (outbound)
  │  └───────────┘  └────────────┘  │
  └─────────────────────────────────┘
         ↑                  ↓ WebSocket (phone initiated)
  ┌──────┴──────────────────▼───────┐
  │  Android Phone                  │
  │  PhantomTouch app               │
  │  ┌────────────────────────────┐ │
  │  │ WS Client → connects to   │ │
  │  │ relay on VM, receives      │ │
  │  │ commands, sends results    │ │
  │  └────────────────────────────┘ │
  └─────────────────────────────────┘

The relay exposes the same HTTP API as PhantomTouch locally:
  POST /execute  → proxied to phone over WebSocket
  GET  /health   → checks if phone is connected

So the gateway/MCP server doesn't know or care that the phone
is remote — it just hits http://localhost:9090 instead of :8080.

Run:
  pip install websockets aiohttp
  python relay_server.py

  # Or with auth token:
  RELAY_TOKEN=mysecrettoken python relay_server.py

Environment:
  RELAY_HTTP_PORT  — HTTP API port (default 9090)
  RELAY_WS_PORT    — WebSocket port for phone (default 9091)
  RELAY_TOKEN      — Shared secret between relay and phone (optional but recommended)
"""

import asyncio
import json
import os
import time
import uuid
import logging
from typing import Optional

import websockets
from aiohttp import web

logging.basicConfig(level=logging.INFO, format="%(asctime)s [%(levelname)s] %(message)s")
log = logging.getLogger("relay")

HTTP_PORT = int(os.environ.get("RELAY_HTTP_PORT", "9090"))
WS_PORT = int(os.environ.get("RELAY_WS_PORT", "9091"))
RELAY_TOKEN = os.environ.get("RELAY_TOKEN", "")

# ── Phone Connection State ─────────────────────────────────────────

class PhoneConnection:
    """Manages the WebSocket connection to the phone."""

    def __init__(self):
        self.ws: Optional[websockets.WebSocketServerProtocol] = None
        self.connected_at: Optional[float] = None
        self.device_info: dict = {}
        self._pending: dict[str, asyncio.Future] = {}
        self._lock = asyncio.Lock()

    @property
    def is_connected(self) -> bool:
        return self.ws is not None and self.ws.open

    async def send_command(self, command: dict, timeout: float = 30.0) -> dict:
        """Send a command to the phone and wait for the response."""
        if not self.is_connected:
            return {"success": False, "error": "Phone not connected"}

        request_id = str(uuid.uuid4())
        envelope = {
            "id": request_id,
            "type": "command",
            "payload": command,
            "timestamp": time.time()
        }

        future: asyncio.Future = asyncio.get_event_loop().create_future()
        self._pending[request_id] = future

        try:
            await self.ws.send(json.dumps(envelope))
            result = await asyncio.wait_for(future, timeout=timeout)
            return result
        except asyncio.TimeoutError:
            return {"success": False, "error": f"Phone did not respond within {timeout}s"}
        except websockets.ConnectionClosed:
            return {"success": False, "error": "Phone disconnected during command"}
        finally:
            self._pending.pop(request_id, None)

    def resolve(self, request_id: str, result: dict):
        """Called when the phone sends a response."""
        future = self._pending.get(request_id)
        if future and not future.done():
            future.set_result(result)

    def disconnect(self):
        self.ws = None
        self.connected_at = None
        self.device_info = {}
        # Cancel all pending requests
        for rid, future in self._pending.items():
            if not future.done():
                future.set_result({"success": False, "error": "Phone disconnected"})
        self._pending.clear()


phone = PhoneConnection()


# ── WebSocket Server (phone connects here) ─────────────────────────

async def handle_phone_ws(websocket, path):
    """Handle incoming WebSocket connection from the phone."""
    global phone

    # Auth check
    if RELAY_TOKEN:
        try:
            auth_msg = await asyncio.wait_for(websocket.recv(), timeout=10)
            auth = json.loads(auth_msg)
            if auth.get("token") != RELAY_TOKEN:
                await websocket.send(json.dumps({"type": "auth_error", "error": "Invalid token"}))
                await websocket.close()
                return
            await websocket.send(json.dumps({"type": "auth_ok"}))
        except (asyncio.TimeoutError, json.JSONDecodeError):
            await websocket.close()
            return

    # Register connection
    if phone.is_connected:
        log.warning("New phone connection replacing existing one")
        try:
            await phone.ws.close()
        except:
            pass
        phone.disconnect()

    phone.ws = websocket
    phone.connected_at = time.time()
    log.info(f"Phone connected from {websocket.remote_address}")

    try:
        # Wait for device info message
        try:
            info_msg = await asyncio.wait_for(websocket.recv(), timeout=10)
            info = json.loads(info_msg)
            if info.get("type") == "device_info":
                phone.device_info = info.get("payload", {})
                log.info(f"Device: {phone.device_info}")
        except asyncio.TimeoutError:
            log.warning("Phone didn't send device info")

        # Main message loop
        async for message in websocket:
            try:
                msg = json.loads(message)
                msg_type = msg.get("type")

                if msg_type == "response":
                    # Phone is responding to a command we sent
                    request_id = msg.get("id")
                    result = msg.get("payload", {})
                    if request_id:
                        phone.resolve(request_id, result)
                    else:
                        log.warning(f"Response without id: {msg}")

                elif msg_type == "heartbeat":
                    await websocket.send(json.dumps({"type": "heartbeat_ack"}))

                else:
                    log.warning(f"Unknown message type: {msg_type}")

            except json.JSONDecodeError:
                log.warning(f"Invalid JSON from phone: {message[:100]}")

    except websockets.ConnectionClosed as e:
        log.info(f"Phone disconnected: {e}")
    finally:
        phone.disconnect()
        log.info("Phone connection cleared")


# ── HTTP Server (gateway connects here) ────────────────────────────
# Exposes the same API as PhantomTouch's local HTTP server.
# The gateway/MCP server just points at this instead of :8080.

async def handle_health(request: web.Request) -> web.Response:
    data = {
        "status": "ready" if phone.is_connected else "waiting",
        "phone_connected": phone.is_connected,
        "connected_at": phone.connected_at,
        "device_info": phone.device_info,
        "uptime_seconds": (time.time() - phone.connected_at) if phone.connected_at else 0,
    }
    return web.json_response(data)


async def handle_execute(request: web.Request) -> web.Response:
    """Proxy a command to the phone over WebSocket."""
    if not phone.is_connected:
        return web.json_response(
            {"success": False, "error": "Phone not connected. Waiting for WebSocket connection on port " + str(WS_PORT)},
            status=503
        )

    try:
        body = await request.json()
    except json.JSONDecodeError:
        return web.json_response({"success": False, "error": "Invalid JSON"}, status=400)

    # For batches, increase timeout proportionally
    is_batch = "batch" in body
    cmd_count = len(body.get("batch", [])) if is_batch else 1
    timeout = max(30, cmd_count * 10)  # 10s per command in batch

    result = await phone.send_command(body, timeout=timeout)
    return web.json_response(result)


async def handle_screen(request: web.Request) -> web.Response:
    """Quick screenshot endpoint."""
    if not phone.is_connected:
        return web.json_response({"success": False, "error": "Phone not connected"}, status=503)

    scale = float(request.query.get("scale", "0.5"))
    result = await phone.send_command({"action": "screenshot", "scale": scale})
    return web.json_response(result)


# ── Main ───────────────────────────────────────────────────────────

async def main():
    # Start WebSocket server for phone
    ws_server = await websockets.serve(handle_phone_ws, "0.0.0.0", WS_PORT)
    log.info(f"WebSocket server listening on :{WS_PORT} (phone connects here)")

    # Start HTTP server for gateway
    app = web.Application()
    app.router.add_get("/health", handle_health)
    app.router.add_post("/execute", handle_execute)
    app.router.add_get("/screen", handle_screen)

    runner = web.AppRunner(app)
    await runner.setup()
    site = web.TCPSite(runner, "0.0.0.0", HTTP_PORT)
    await site.start()
    log.info(f"HTTP API listening on :{HTTP_PORT} (gateway connects here)")
    log.info(f"Auth: {'enabled' if RELAY_TOKEN else 'disabled (set RELAY_TOKEN for production)'}")

    log.info("")
    log.info("Waiting for phone to connect...")
    log.info(f"  Phone should connect to: ws://<this-vm-ip>:{WS_PORT}")
    log.info(f"  Gateway should call:     http://localhost:{HTTP_PORT}/execute")

    # Keep running
    await asyncio.Future()


if __name__ == "__main__":
    asyncio.run(main())
