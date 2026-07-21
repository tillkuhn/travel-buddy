#!/usr/bin/env python3
"""Mock OpenID Connect provider — implements just enough of the OAuth2
client_credentials grant for GatewayTokenService to obtain a bearer token.

Endpoint: POST /realms/mock/protocol/openid-connect/token
Body (application/x-www-form-urlencoded):
  grant_type=client_credentials&client_id=...&client_secret=...&scope=...

On success: 200 JSON {"access_token": "...", "token_type": "Bearer", "expires_in": N}
On bad credentials: 401 JSON {"error": "invalid_client"}

Configuration via env vars:
  MOCK_OIDC_PORT       port to listen on (default 9200)
  MOCK_TOKEN_TTL       token lifetime in seconds (default 8 — intentionally short for testing)
  MOCK_CLIENT_ID       expected client_id (default "mock-client")
  MOCK_CLIENT_SECRET   expected client_secret (default "mock-secret")
  MOCK_SHARED_SECRET   HMAC signing secret, must match mock_gateway.py (default provided)

Run: python3 mocks/mock_oidc.py
"""

import json
import os
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs

import common

PORT = int(os.environ.get("MOCK_OIDC_PORT", "9200"))
TOKEN_TTL = int(os.environ.get("MOCK_TOKEN_TTL", "8"))

TOKEN_PATH = "/realms/mock/protocol/openid-connect/token"


class Handler(BaseHTTPRequestHandler):
    server_version = "MockOIDC/1.0"

    def log_message(self, fmt, *args):
        print(f"[mock-oidc] {self.address_string()} - {fmt % args}")

    def _send_json(self, status: int, payload: dict):
        body = json.dumps(payload).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_POST(self):
        if self.path != TOKEN_PATH:
            self._send_json(404, {"error": "not_found", "path": self.path})
            return

        length = int(self.headers.get("Content-Length", "0"))
        raw_body = self.rfile.read(length) if length else b""
        form = parse_qs(raw_body.decode("utf-8"))

        grant_type = form.get("grant_type", [""])[0]
        client_id = form.get("client_id", [""])[0]
        client_secret = form.get("client_secret", [""])[0]
        scope = form.get("scope", [""])[0]

        if grant_type != "client_credentials":
            self._send_json(400, {"error": "unsupported_grant_type", "grant_type": grant_type})
            return

        if client_id != common.EXPECTED_CLIENT_ID or client_secret != common.EXPECTED_CLIENT_SECRET:
            print(f"[mock-oidc] REJECTED client_id={client_id!r} (bad credentials)")
            self._send_json(401, {"error": "invalid_client", "error_description": "bad client_id/client_secret"})
            return

        token_response = common.issue_token(client_id, TOKEN_TTL)
        exp = time.time() + TOKEN_TTL
        print(
            f"[mock-oidc] issued token {common.mask_token(token_response['access_token'])} "
            f"for client_id={client_id} scope={scope!r} "
            f"ttl={TOKEN_TTL}s expires_at={time.strftime('%H:%M:%S', time.localtime(exp))}"
        )
        self._send_json(200, token_response)


def main():
    server = ThreadingHTTPServer(("0.0.0.0", PORT), Handler)
    print(f"[mock-oidc] listening on http://localhost:{PORT}{TOKEN_PATH}")
    print(f"[mock-oidc] expected client_id={common.EXPECTED_CLIENT_ID!r} token_ttl={TOKEN_TTL}s")
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()
