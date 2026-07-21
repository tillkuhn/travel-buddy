#!/usr/bin/env python3
"""Mock OpenAI-compatible AI gateway that enforces a valid, unexpired bearer token.

Mimics the real remote gateway that GatewayProxyController forwards requests to:
Embabel's base-url includes the model in the path (e.g. ".../mock-model") and
appends "/v1/chat/completions" itself, so this mock accepts ANY path ending in
"/chat/completions" to stay agnostic of the configured model name.

Auth enforcement:
  - Requires "Authorization: Bearer <token>"
  - Token must be a valid, non-expired token signed by mock_oidc.py (shared secret
    in common.py) -> 401 otherwise, mirroring a real gateway rejecting expired/
    invalid OAuth2 tokens.

Response: a minimal OpenAI chat.completion object with a bit of randomness in the
reply content, so two consecutive requests never return the exact same text
(useful to prove the round-trip actually happened rather than being cached).

Configuration via env vars:
  MOCK_GATEWAY_PORT      port to listen on (default 9300)
  MOCK_SHARED_SECRET     HMAC signing secret, must match mock_oidc.py (default provided)
  MOCK_FIXED_DESTINATION pin the destination for repeatable testing:
                           unset/"random" (default) -> pick randomly each request
                           "first"                  -> always DESTINATIONS[0] (Lisbon, Portugal)
                           any other value           -> used verbatim as the destination
                         The vibe/ticket/timestamp still vary, so two requests never
                         return byte-identical content even with a fixed destination.

Run: python3 mocks/mock_gateway.py
"""

import json
import os
import random
import time
import uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

import common

PORT = int(os.environ.get("MOCK_GATEWAY_PORT", "9300"))
FIXED_DESTINATION = os.environ.get("MOCK_FIXED_DESTINATION", "random")

DESTINATIONS = [
    "Lisbon, Portugal", "Kyoto, Japan", "Ljubljana, Slovenia", "Valparaiso, Chile",
    "Chiang Mai, Thailand", "Faroe Islands", "Tbilisi, Georgia", "Queenstown, New Zealand",
    "Sicily, Italy", "Cape Town, South Africa",
]
VIBES = [
    "sun-drenched coastal walks", "quiet mountain trails", "buzzing night markets",
    "hidden jazz bars", "centuries-old cobblestone alleys", "world-class street food",
    "dramatic cliffside views", "laid-back surf towns", "misty forest hikes",
]


def _extract_bearer(auth_header: str):
    if not auth_header or not auth_header.lower().startswith("bearer "):
        return None
    return auth_header[7:].strip()


def _mock_reply() -> str:
    if FIXED_DESTINATION == "random":
        dest = random.choice(DESTINATIONS)
    elif FIXED_DESTINATION == "first":
        dest = DESTINATIONS[0]
    else:
        dest = FIXED_DESTINATION
    vibe = random.choice(VIBES)
    ticket = random.randint(1000, 9999)
    return (
        f"[MOCK GATEWAY #{ticket}] Based on your preferences, {dest} is calling your name — "
        f"think {vibe} and a trip you won't stop talking about. "
        f"(This is a randomized mock response, generated at {time.strftime('%H:%M:%S')}.)"
    )


class Handler(BaseHTTPRequestHandler):
    server_version = "MockAIGateway/1.0"

    def log_message(self, fmt, *args):
        print(f"[mock-gateway] {self.address_string()} - {fmt % args}")

    def _send_json(self, status: int, payload: dict):
        body = json.dumps(payload).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def _unauthorized(self, token: str, reason: str):
        print(f"[mock-gateway] REJECTED {self.path} token={common.mask_token(token)} -> 401 ({reason})")
        self._send_json(
            401,
            {
                "error": {
                    "message": f"Invalid or expired token: {reason}",
                    "type": "invalid_request_error",
                    "code": "invalid_api_key",
                }
            },
        )

    def do_GET(self):
        if self.path.rstrip("/") == "/v1/models":
            self._send_json(200, {"object": "list", "data": [{"id": "mock-model", "object": "model"}]})
            return
        self._send_json(404, {"error": "not_found", "path": self.path})

    def do_POST(self):
        if not self.path.endswith("/chat/completions"):
            self._send_json(404, {"error": "not_found", "path": self.path})
            return

        token = _extract_bearer(self.headers.get("Authorization", ""))
        ok, info = common.verify_token(token)
        if not ok:
            self._unauthorized(token, info)
            return

        length = int(self.headers.get("Content-Length", "0"))
        raw_body = self.rfile.read(length) if length else b""
        try:
            request_json = json.loads(raw_body) if raw_body else {}
        except json.JSONDecodeError:
            request_json = {}
        model = request_json.get("model", "mock-model")

        print(
            f"[mock-gateway] ACCEPTED {self.path} token={common.mask_token(token)} "
            f"(sub={info.get('sub')}, model={model})"
        )

        content = _mock_reply()
        response = {
            "id": f"chatcmpl-{uuid.uuid4()}",
            "object": "chat.completion",
            "created": int(time.time()),
            "model": model,
            "choices": [
                {
                    "index": 0,
                    "message": {"role": "assistant", "content": content},
                    "finish_reason": "stop",
                }
            ],
            "usage": {
                "prompt_tokens": 20,
                "completion_tokens": len(content.split()),
                "total_tokens": 20 + len(content.split()),
            },
        }
        self._send_json(200, response)


def main():
    server = ThreadingHTTPServer(("0.0.0.0", PORT), Handler)
    print(f"[mock-gateway] listening on http://localhost:{PORT}  (accepts any path ending in /chat/completions)")
    print(f"[mock-gateway] destination mode: {FIXED_DESTINATION!r}")
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()
