from __future__ import annotations

import time
import uuid


def generate_request_id() -> str:
    # 26-char Crockford base32 or hex-based ID
    return uuid.uuid4().hex.upper()[:26]


class TimingMiddleware:
    def __init__(self, app):
        self.app = app

    async def __call__(self, scope, receive, send):
        if scope["type"] == "http":
            t0 = time.time()
            if "state" not in scope:
                scope["state"] = {}
            scope["state"]["t0"] = t0
            request_id = generate_request_id()
            scope["state"]["request_id"] = request_id

            async def send_wrapper(message):
                if message["type"] == "http.response.start":
                    headers = list(message.get("headers", []))
                    headers.append((b"x-request-id", request_id.encode("ascii")))
                    message["headers"] = headers
                await send(message)

            await self.app(scope, receive, send_wrapper)
        else:
            await self.app(scope, receive, send)
