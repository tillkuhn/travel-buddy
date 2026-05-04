# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

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

# Start the LLM backend (ramalama)
make llm                     # serves tinyllama on port 11434

# Test LLM connectivity directly
make llm-test                # POST to /v1/chat/completions
make llm-models              # list available models via /v1/models
```

## LLM Backend

This app uses **ramalama** (llama.cpp server) as the LLM backend, not Ollama — even though the endpoint is at `http://localhost:11434`. Ramalama speaks the **OpenAI wire protocol** (`/v1/chat/completions`), not the native Ollama protocol.

The Embabel dependency is therefore `embabel-agent-starter-openai` (not `embabel-agent-starter-ollama`). Spring AI's `OllamaChatModel` must not be used here as it returns null message responses against this endpoint.

### Changing the model

Update `embabel.models.default-llm` in `application.properties` and add a matching entry in `src/main/resources/models/openai-models.yml` (overrides the built-in OpenAI model list). The `.bak` file in that directory is an example for a local ramalama model.

Key properties:
```properties
embabel.agent.platform.models.openai.base-url=http://localhost:11434
embabel.agent.platform.models.openai.api-key=unused
embabel.models.default-llm=gpt-4.1-mini
```

## Architecture

The app is an **Embabel agent** travel planner with a Thymeleaf web UI. Main package: `net.timafe.travel`.

### Request flow

```
Browser POST /plan
  → TravelController          catches exceptions; on error re-renders index with error message
  → TravelService             builds prompt from TravelRequest, invokes Embabel
  → AgentInvocation           Embabel framework entry point
  → TravelPlannerAgent        single @Action/@AchievesGoal — calls ai.withAutoLlm().generateText()
  → ramalama (OpenAI API)     returns suggestion string
  → TravelResult record       carries suggestion, prompt, and echoed inputs back to controller
```

### Key design points

- `TravelController` catches all exceptions from the service layer, logs them, and returns a user-friendly error on the `index` template rather than letting Spring show a 500 page.
- The form submits via `fetch()` (not a native form POST) so the loading overlay can be dismissed programmatically on both success and error without leaving the user waiting indefinitely.
- `TravelPlannerAgent` is minimal by design — prompt construction lives in `TravelService`, not the agent.

### Thymeleaf templates

- `index.html` — form with region select, multi-select activities, free-text wishes, and an `error-banner` div (`th:if="${error}"`) for LLM failures
- `result.html` — suggestion display, input tags, "Try again" (re-submits same params), prompt reveal toggle
- `fragments.html` — three fragments: `loading-styles`, `loading-overlay`, `loading-script`; the script uses `fetch` and clears the `setInterval` on response

## Testing

Tests extend `EmbabelMockitoIntegrationTest` (from `embabel-agent-test`), which mocks the LLM layer. Key helper methods:

```java
whenGenerateText(p -> p.contains("Europe")).thenReturn("Visit Prague!");
verifyGenerateText(p -> p.contains("Europe"));
```

The only test class is `TravelServiceTest`. Tests cover prompt construction, null/blank activities, and result field mapping.

## Logging personality

`embabel.agent.logging.personality` themes agent lifecycle log messages: `starwars`, `severance`, `colossus`, `hitchhiker`, `montypython`. Currently commented out in `application.properties`.
