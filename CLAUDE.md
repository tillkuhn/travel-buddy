# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Commands

```bash
# Build
mvn package

# Run (starts web server on http://localhost:8080)
mvn spring-boot:run
```

There are no tests yet (`src/test/java` is empty).

## LLM Backend

This app uses **ramalama** (llama.cpp server) as the LLM backend, not Ollama — even though the endpoint is at `http://localhost:11434`. Ramalama speaks the **OpenAI wire protocol** (`/v1/chat/completions`), not the native Ollama protocol.

The Embabel dependency is therefore `embabel-agent-starter-openai` (not `embabel-agent-starter-ollama`). Spring AI's `OllamaChatModel` must not be used here as it returns null message responses against this endpoint.

### Model registration

Embabel's OpenAI starter loads model definitions from `classpath:models/openai-models.yml`. The file at `src/main/resources/models/openai-models.yml` overrides the built-in one (which only lists real OpenAI GPT models) with the locally available ramalama model.

To change the model: update both `openai-models.yml` (add the entry) and `embabel.models.default-llm` in `application.properties`.

Key properties:
```properties
embabel.agent.platform.models.openai.base-url=http://localhost:11434
embabel.agent.platform.models.openai.api-key=unused
embabel.models.default-llm=library/llama3.2
```

## Architecture

The app is an **Embabel agent** travel planner with a Thymeleaf web UI:

- `EmbabelDemoApplication` — standard Spring Boot entry point
- `TravelController` — Spring MVC `@Controller` serving the form (`GET /`) and handling submissions (`POST /plan`); builds the LLM prompt from user inputs and invokes `AgentPlatform`
- `agents/TravelPlannerAgent` — `@Agent` with a single `@Action`/`@AchievesGoal` method that takes a `UserInput` prompt and returns a destination suggestion as a `String`

### Web UI (Thymeleaf templates)

- `templates/index.html` — input form with:
  - Region select box (Americas / Southeast Asia / Europe)
  - Multi-select for activities (Hiking, Skiing, Diving, Beachtime, Culture, Shopping, Cycling)
  - Free-text field for additional wishes
- `templates/result.html` — displays the LLM suggestion with the selected inputs echoed back

## Logging personality

`embabel.agent.logging.personality` themes agent lifecycle log messages. Available values: `starwars`, `severance`, `colossus`, `hitchhiker`, `montypython`. Currently commented out in `application.properties`.
