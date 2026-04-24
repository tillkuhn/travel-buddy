# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Commands

```bash
# Build
mvn package

# Run (starts web server on http://localhost:8080)
mvn spring-boot:run

# Run tests
mvn test

# Run a single test class
mvn test -Dtest=TravelServiceTest

# Run a single test method
mvn test -Dtest=TravelServiceTest#planReturnsLlmSuggestion
```

## LLM Backend

This app uses **ramalama** (llama.cpp server) as the LLM backend, not Ollama — even though the endpoint is at `http://localhost:11434`. Ramalama speaks the **OpenAI wire protocol** (`/v1/chat/completions`), not the native Ollama protocol.

The Embabel dependency is therefore `embabel-agent-starter-openai` (not `embabel-agent-starter-ollama`). Spring AI's `OllamaChatModel` must not be used here as it returns null message responses against this endpoint.

### Model registration

Embabel's OpenAI starter loads model definitions from `classpath:models/openai-models.yml`. The file at `src/main/resources/models/openai-models.yml` overrides the built-in one (which only lists real OpenAI GPT models) with the locally available ramalama model. Note: `openai-models.yml.bak` exists in that directory — rename it to `openai-models.yml` and update `embabel.models.default-llm` to switch to a local ramalama model.

To change the model: update both `openai-models.yml` (add the entry) and `embabel.models.default-llm` in `application.properties`.

Key properties:
```properties
embabel.agent.platform.models.openai.base-url=http://localhost:11434
embabel.agent.platform.models.openai.api-key=unused
embabel.models.default-llm=library/llama3.2
```

## Architecture

The app is an **Embabel agent** travel planner with a Thymeleaf web UI. The main package is `net.timafe.travel`.

- `TravelApplication` — standard Spring Boot entry point
- `TravelController` — Spring MVC `@Controller` serving the form (`GET /`) and handling submissions (`POST /plan`)
- `TravelService` — builds the LLM prompt from `TravelRequest` inputs and invokes the Embabel agent via `AgentInvocation`, returning a `TravelResult`
- `TravelRequest` / `TravelResult` — Java records for the request/response DTOs
- `agents/TravelPlannerAgent` — `@Agent` with a single `@Action`/`@AchievesGoal` method that takes a `UserInput` prompt and calls `ai.withAutoLlm().generateText()` to return a destination suggestion

### Web UI (Thymeleaf templates)

- `templates/index.html` — input form with region select, multi-select activities, and free-text wishes
- `templates/result.html` — displays the LLM suggestion, user inputs echoed as tags, "Try again" button, and a toggle to reveal the raw prompt sent to the LLM
- `templates/fragments.html` — shared Thymeleaf fragments: loading overlay styles, animated spinner markup, and rotating humorous loading messages

## Testing

Tests extend `EmbabelMockitoIntegrationTest` (from `embabel-agent-test`), which provides Mockito-based mocking of the LLM layer. The test class is `TravelServiceTest`.

## Logging personality

`embabel.agent.logging.personality` themes agent lifecycle log messages. Available values: `starwars`, `severance`, `colossus`, `hitchhiker`, `montypython`. Currently commented out in `application.properties`.
