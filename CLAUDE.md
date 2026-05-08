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
