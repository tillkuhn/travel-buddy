# AGENTS.md

See **CLAUDE.md** for full details (LLM backend, gateway proxy, architecture). Summary below.

## Commands

```bash
mvn package                                          # build
mvn spring-boot:run                                  # or: make run — starts web server on :8080
mvn test                                              # or: make test
mvn test -Dtest=TravelServiceTest                     # single test class
mvn test -Dtest=TravelServiceTest#planReturnsLlmSuggestion  # single test method
make llm-run                                          # start ramalama LLM backend on :11434 (needed for manual/integration testing against a real model)
```

CI (`.github/workflows/ci.yml`) runs `mvn --batch-mode verify` on Java 25, triggered only on changes to `pom.xml` or `src/**`.

## Key gotchas

- **Backend is ramalama, not Ollama**, despite listening on the default Ollama port 11434. It speaks the OpenAI wire protocol. Do not use Spring AI's `OllamaChatModel` — it returns null against this endpoint. Dependency is `embabel-agent-starter-openai`.
- Model config lives in two places that must stay in sync: `embabel.models.default-llm` in `application.properties` and a matching entry in `src/main/resources/models/openai-models.yml`.
- **Unit tests don't need a running LLM** — `TravelServiceTest` extends `EmbabelMockitoIntegrationTest`, which mocks the LLM layer via `whenGenerateText(...)`/`verifyGenerateText(...)`. Only `make llm-test`/`make llm-run` talk to a real model.
- Only one test class exists: `TravelServiceTest`. `TravelPlannerAgent` itself is intentionally minimal (no prompt logic) — prompt construction belongs in `TravelService`, not the agent.
- The optional remote AI gateway (OAuth2 client-credentials, wired via `GatewayAuthConfig` using standard Spring Security OAuth2 client machinery — no local proxy) is disabled by default and gitignored (`application-remote.properties`). It's gated by `@ConditionalOnProperty("gateway.auth.token-url")` — don't assume it's active unless that property is set.
- Package root: `net.timafe.travel` (single small module, no monorepo).
