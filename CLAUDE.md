# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Prerequisites

- Java 25+
- Maven
- RamaLama running locally with a compatible model (e.g. `tinyllama`, `llama3.2`, `granite:2b`)

## Commands

```bash
# Build
mvn package

# Run (starts web server on http://localhost:8080)
mvn spring-boot:run          # or: make run

# Run tests
mvn test                     # or: make test

# Run a single test class
mvn test -Dtest=TravelServiceTest

# Run a single test method
mvn test -Dtest=TravelServiceTest#planReturnsLlmSuggestion

# Start the LLM backend (ramalama) — default model is granite:2b
make llm-run                 # serves model on port 11434

# Test LLM connectivity directly
make llm-test                # POST to /v1/chat/completions
make llm-models              # list available models via /v1/models

# Override model at runtime
LLM_MODEL=tinyllama make llm-run
```

## LLM Backend

This app uses **ramalama** (llama.cpp server) as the LLM backend, not Ollama — even though the endpoint is at `http://localhost:11434`. Ramalama speaks the **OpenAI wire protocol** (`/v1/chat/completions`), not the native Ollama protocol.

The Embabel dependency is therefore `embabel-agent-starter-openai` (not `embabel-agent-starter-ollama`). Spring AI's `OllamaChatModel` must not be used here as it returns null message responses against this endpoint.

### Changing the model

Update `embabel.models.default-llm` in `application.properties` and add a matching entry in `src/main/resources/models/openai-models.yml` (overrides the built-in OpenAI model list). The `.bak` file in that directory shows the format for a local ramalama model entry.

Key properties:
```properties
embabel.agent.platform.models.openai.base-url=http://localhost:11434
embabel.agent.platform.models.openai.api-key=unused
embabel.models.default-llm=gpt-4.1-mini
```

The `default-llm` value must be a registered OpenAI model name, or a custom entry added to `openai-models.yml`.

### Remote AI gateway (bearer-token auth)

The app can also talk to a remote, OpenAI-compatible AI gateway that sits behind an OAuth2
identity provider (Keycloak `client_credentials` grant) instead of the local ramalama server.
This is **opt-in and gitignored** — the default committed configuration is unchanged.

To enable it, copy `application-remote.properties.example` to `application-remote.properties`
(gitignored) and fill in the gateway URL and credentials. Setting `gateway.auth.token-url` is
what activates the machinery.

**Architecture**: `GatewayAuthConfig` wires standard Spring Security OAuth2 client machinery
(`OAuth2AuthorizedClientManager` + `OAuth2ClientHttpRequestInterceptor`, from
`spring-boot-starter-oauth2-client`) directly into Embabel's HTTP client — no local proxy. This
works because Embabel's `OpenAiModelsConfig` looks up an `ObjectProvider<RestClient.Builder>`
qualified `"aiModelRestClientBuilder"` when building its `OpenAiApi` client; `GatewayAuthConfig`
publishes that exact bean with the OAuth2 interceptor attached, so it:
- Authorizes every outbound LLM HTTP call via `OAuth2AuthorizedClientManager`
- Injects fresh OAuth2 bearer tokens (auto-renewed ~60s before expiry, Spring Security's default)
- Talks directly to the real gateway URL (no `localhost:8080/gateway-proxy` indirection)
- Applies SSL/TLS via custom certificates at the JVM level (independent concern, see CERTIFICATES.md)

This replaced an earlier local-HTTP-proxy-based implementation — see
**[gateway-proxy-removal.md](gateway-proxy-removal.md)** for the design rationale and
**[CHALLENGES.md](CHALLENGES.md)** for the original technical investigation.

#### Quick Setup

1. **Copy example config**:
   ```bash
   cp src/main/resources/application-remote.properties.example \
      src/main/resources/application-remote.properties
   ```

2. **Fill in OAuth2 credentials** in `application-remote.properties`:
   ```properties
   gateway.auth.token-url=https://your-keycloak.example.com/realms/your-realm/protocol/openid-connect/token
   gateway.auth.client-id=your-client-id
   gateway.auth.client-secret=your-secret
   gateway.auth.scope=your-scope
   embabel.agent.platform.models.openai.base-url=https://your-gateway.example.com/model
   ```

3. **Configure SSL certificates** (if using company CA) - see **[CERTIFICATES.md](CERTIFICATES.md)**:
   ```properties
   gateway.auth.ssl.trust-store-path=certs/company-truststore.jks
   gateway.auth.ssl.trust-store-password=changeit
   ```

4. **Verify on startup** — you should see:
   ```
   INFO  GatewayAuthConfig - Gateway OAuth2 authorized client manager configured (client_credentials grant)
   INFO  GatewayAuthConfig - Created 'aiModelRestClientBuilder' RestClient.Builder with OAuth2 bearer-token interceptor
   ```

The whole config is `@ConditionalOnProperty("gateway.auth.token-url")`, so without setting this
property the gateway integration is disabled and the local ramalama flow is untouched.

## Architecture

The app is an **Embabel agent** travel planner with a Thymeleaf web UI. Main package: `net.timafe.travel`.

### Request flow

```
Browser POST /plan
  → TravelController          maps @RequestParam fields; renders result or propagates exception
  → TravelService             builds prompt from TravelRequest, invokes Embabel
  → AgentInvocation           Embabel framework entry point
  → TravelPlannerAgent        single @Action/@AchievesGoal — calls ai.withAutoLlm().generateText()
  → ramalama (OpenAI API)     returns suggestion string
  → TravelResult record       carries suggestion, prompt, and echoed inputs back to controller
```

### Key design points

- `TravelPlannerAgent` is minimal by design — prompt construction lives in `TravelService`, not the agent.
- The form submits via a native HTML POST. A JS `submit` event listener shows a loading overlay while waiting; the browser navigates away on response.
- `TravelRequest` and `TravelResult` are Java records. `activities` can be null (no selection) and is normalized to an empty list in `TravelService`.

### Thymeleaf templates

- `index.html` — form with region select, multi-select activities, free-text wishes; includes loading overlay fragments
- `result.html` — suggestion display, input tags, "Try again" (re-submits same params), prompt reveal toggle
- `fragments.html` — three fragments: `loading-styles`, `loading-overlay`, `loading-script`; the script attaches to the form's `submit` event and cycles through humorous messages

## Testing

Tests extend `EmbabelMockitoIntegrationTest` (from `embabel-agent-test`), which mocks the LLM layer. Key helper methods:

```java
whenGenerateText(p -> p.contains("Europe")).thenReturn("Visit Prague!");
verifyGenerateText(p -> p.contains("Europe"));
```

The only test class is `TravelServiceTest`. Tests cover prompt construction, null/blank activities, and result field mapping.

## Logging personality

`embabel.agent.logging.personality` themes agent lifecycle log messages: `starwars`, `severance`, `colossus`, `hitchhiker`, `montypython`. Currently commented out in `application.properties`.
