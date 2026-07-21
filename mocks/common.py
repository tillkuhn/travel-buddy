"""Shared helpers for the mock OIDC provider and mock AI gateway.

Both mock servers are independent processes (no shared memory), so token
validity is proven with a signed, self-contained token (HMAC-SHA256 over a
small JSON payload) rather than a stateful lookup. This is a *mock*
simplification of real JWT/JOSE-signed OIDC access tokens/introspection.

Not for production use: the shared secret is a hardcoded default, tokens are
unencrypted (base64, not encrypted), and there is no key rotation/JWKS.
"""

import base64
import hashlib
import hmac
import json
import os
import time
import uuid

# Both mock processes must agree on this secret. Override via env var if desired.
SHARED_SECRET = os.environ.get("MOCK_SHARED_SECRET", "mock-shared-secret-change-me").encode("utf-8")

EXPECTED_CLIENT_ID = os.environ.get("MOCK_CLIENT_ID", "mock-client")
EXPECTED_CLIENT_SECRET = os.environ.get("MOCK_CLIENT_SECRET", "mock-secret")


def _b64url_encode(raw: bytes) -> str:
    return base64.urlsafe_b64encode(raw).rstrip(b"=").decode("ascii")


def _b64url_decode(s: str) -> bytes:
    padding = "=" * (-len(s) % 4)
    return base64.urlsafe_b64decode(s + padding)


def _sign(payload_b64: str) -> str:
    sig = hmac.new(SHARED_SECRET, payload_b64.encode("ascii"), hashlib.sha256).digest()
    return _b64url_encode(sig)


def mask_token(token: str, show: int = 2) -> str:
    """Shortens a token for logging, e.g. 'eyJzdWIi...IjM5PTI' -> 'ey*****I'."""
    if not token:
        return "<none>"
    if len(token) <= show * 2:
        return "*" * len(token)
    return f"{token[:show]}{'*' * 5}{token[-show:]}"


def issue_token(client_id: str, ttl_seconds: int) -> dict:
    """Creates a signed mock access token. Returns dict with access_token/expires_in/token_type."""
    now = int(time.time())
    payload = {
        "sub": client_id,
        "iat": now,
        "exp": now + ttl_seconds,
        "jti": str(uuid.uuid4()),
    }
    payload_b64 = _b64url_encode(json.dumps(payload).encode("utf-8"))
    signature = _sign(payload_b64)
    token = f"{payload_b64}.{signature}"
    return {
        "access_token": token,
        "token_type": "Bearer",
        "expires_in": ttl_seconds,
    }


def verify_token(token: str):
    """Verifies a mock access token.

    Returns (True, payload_dict) if valid and not expired, otherwise (False, reason_str).
    """
    if not token:
        return False, "missing token"
    parts = token.split(".")
    if len(parts) != 2:
        return False, "malformed token"
    payload_b64, signature = parts
    expected_sig = _sign(payload_b64)
    if not hmac.compare_digest(signature, expected_sig):
        return False, "invalid signature"
    try:
        payload = json.loads(_b64url_decode(payload_b64))
    except Exception:
        return False, "malformed payload"
    if int(time.time()) >= int(payload.get("exp", 0)):
        return False, "token expired"
    return True, payload
