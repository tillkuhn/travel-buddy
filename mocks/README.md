# Mock OIDC + AI Gateway

Two dependency-free Python (stdlib only) HTTP servers that let you exercise the
`gateway.auth.*` / `GatewayAuthConfig` OAuth2 client-credentials code path
(short-lived OAuth2 bearer tokens against an OpenAI-compatible gateway) **without
network access to a real gateway or identity provider**. Useful when developing
offline or evaluating the token-refresh design.

- `mock_oidc.py` — mimics an OIDC provider's `client_credentials` token endpoint.
  Issues short-lived (default 8s) signed tokens.
- `mock_gateway.py` — mimics an OpenAI-compatible chat completions endpoint.
  Validates the bearer token's signature and expiry itself (no callback to the
  OIDC mock) and returns **401** for missing/invalid/expired tokens, or a
  randomized destination suggestion for valid ones.
- `common.py` — shared HMAC-signed token format used by both mocks so they can
  validate tokens independently, as two unrelated processes.

Requires only Python 3 (no pip installs).

## Running the mocks

```bash
# Terminal 1 — OIDC mock (short TTL so you can observe expiry quickly)
MOCK_TOKEN_TTL=10 python3 mocks/mock_oidc.py

# Terminal 2 — AI gateway mock
python3 mocks/mock_gateway.py
```

Defaults: OIDC on `:9200`, gateway on `:9300`. Override with `MOCK_OIDC_PORT` /
`MOCK_GATEWAY_PORT`. Expected client credentials default to
`mock-client` / `mock-secret` (override via `MOCK_CLIENT_ID` /
`MOCK_CLIENT_SECRET`; must match whatever you configure in the app).

## Manual smoke test (no app required)

```bash
# 1. wrong credentials -> 401 invalid_client
curl -s -X POST http://localhost:9200/realms/mock/protocol/openid-connect/token \
  -d grant_type=client_credentials -d client_id=wrong -d client_secret=wrong -d scope=x

# 2. get a valid token
TOKEN=$(curl -s -X POST http://localhost:9200/realms/mock/protocol/openid-connect/token \
  -d grant_type=client_credentials -d client_id=mock-client -d client_secret=mock-secret -d scope=mock-scope \
  | python3 -c "import sys,json; print(json.load(sys.stdin)['access_token'])")

# 3. call the gateway with it -> 200, random destination
curl -s -X POST http://localhost:9300/mock-model/v1/chat/completions \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"model":"mock-model","messages":[{"role":"user","content":"hi"}]}'

# 4. call again -> 200, DIFFERENT random destination (proves it's not cached/static)
curl -s -X POST http://localhost:9300/mock-model/v1/chat/completions \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"model":"mock-model","messages":[{"role":"user","content":"hi"}]}'

# 5. no token / garbage token -> 401
curl -s -o /dev/null -w "%{http_code}\n" -X POST http://localhost:9300/mock-model/v1/chat/completions
curl -s -o /dev/null -w "%{http_code}\n" -X POST http://localhost:9300/mock-model/v1/chat/completions \
  -H "Authorization: Bearer garbage.garbage"

# 6. wait past MOCK_TOKEN_TTL, reuse the OLD token -> 401 "token expired"
sleep 11
curl -s -X POST http://localhost:9300/mock-model/v1/chat/completions \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"model":"mock-model","messages":[{"role":"user","content":"hi"}]}'

# 7. fetch a fresh token and retry -> 200 again
TOKEN=$(curl -s -X POST http://localhost:9200/realms/mock/protocol/openid-connect/token \
  -d grant_type=client_credentials -d client_id=mock-client -d client_secret=mock-secret -d scope=mock-scope \
  | python3 -c "import sys,json; print(json.load(sys.stdin)['access_token'])")
curl -s -X POST http://localhost:9300/mock-model/v1/chat/completions \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"model":"mock-model","messages":[{"role":"user","content":"hi"}]}'
```

## Running the real app against the mocks

The easiest way (uses a committed, non-sensitive Spring profile — see
`src/main/resources/application-mock.properties` — so no file copying needed):

```bash
make run-mock   # starts the mocks (if not already running) and boots the app
                 # with -Dspring-boot.run.profiles=mock
```

Equivalent manual steps, if you're not using `make`:

1. Start both mocks (see above).
2. Run the app with the `mock` Spring profile active:
   ```bash
   mvn spring-boot:run -Dspring-boot.run.profiles=mock
   ```
   Alternatively, copy `mocks/application-local.properties.mock` to the project
   root as `application-local.properties` (gitignored) and run `mvn spring-boot:run`
   without any profile flag — same effect, but occupies the same gitignored file
   slot used for the real remote-gateway scenario (scenario 3), so don't use both
   at once.
3. Submit the form at [http://localhost:8080](http://localhost:8080). Watch the
   app log (`logging.level.net.timafe.travel.gateway=DEBUG`, already set) — the
   OAuth2 `aiModelRestClientBuilder` interceptor (see `GatewayAuthConfig`)
   authorizes each outbound LLM call, fetching/reusing a token via Spring
   Security's `OAuth2AuthorizedClientManager`.
4. **Token expiry test**: submit the form once, wait longer than
   `MOCK_TOKEN_TTL` (Spring's client-credentials provider renews ~60s before
   the reported expiry — see `ClientCredentialsOAuth2AuthorizedClientProvider`'s
   default clock skew — so with a TTL below 60s the app effectively fetches a
   new token on every request; that's expected and still proves the refresh
   path), then submit again. `mocks/.mock_oidc.log` will show a fresh `issued
   token` line each time, and the returned suggestion text differs between
   requests (random destination), confirming a real round trip rather than a
   cached response.

If you want to prove the mock gateway itself rejects an actually-expired token
(rather than just observing the app's proactive refresh), use the manual curl
steps above — steps 6/7 bypass the app's refresh-before-expiry margin entirely
and hit the gateway directly with a token you know has expired.

## Design notes / limitations

- Tokens are `base64url(json payload).base64url(hmac-sha256 signature)` — a
  minimal, self-contained scheme loosely inspired by JWT, **not** a real
  JWT/JOSE implementation (no header, no alg negotiation, no JWKS). Good enough
  to prove signature + expiry enforcement between two independent processes.
- The shared HMAC secret (`MOCK_SHARED_SECRET`, `common.py`) must match between
  `mock_oidc.py` and `mock_gateway.py` — that's how the gateway mock validates
  tokens without calling back to the OIDC mock (mirrors JWT-based OIDC access
  tokens more than opaque-token + introspection, but keeps this genuinely
  minimal).
- `mock_gateway.py` accepts **any** path ending in `/chat/completions`, so it
  works regardless of the model name in the configured base-url.
- No TLS — this is a local, unauthenticated-transport mock. `CERTIFICATES.md`'s
  SSL/truststore concern is out of scope here (mocks run on plain HTTP).
- Not thread-safe against concurrent OIDC/gateway restarts mid-token-lifetime;
  fine for manual/local testing, not intended as a long-running service.
