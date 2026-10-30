from __future__ import annotations

NOSNIFF = (b"x-content-type-options", b"nosniff")


class SecurityHeadersMiddleware:
    """Puts `X-Content-Type-Options: nosniff` on every response, including errors.

    The reverse proxy sets it too (deploy/Caddyfile), but the API must not depend on that: a scan of the service on its own
    port found it missing on /v1/health (BUG-2026-084).
    """

    def __init__(self, app):
        self.app = app

    async def __call__(self, scope, receive, send):
        if scope["type"] != "http":
            await self.app(scope, receive, send)
            return

        async def send_wrapper(message):
            if message["type"] == "http.response.start":
                headers = [h for h in message.get("headers", []) if h[0].lower() != NOSNIFF[0]]
                headers.append(NOSNIFF)
                message["headers"] = headers
            await send(message)

        await self.app(scope, receive, send_wrapper)
