# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Commands

```bash
# Build
mvn package

# Run (interactive Spring Shell)
mvn spring-boot:run

# Run a shell command non-interactively (e.g. invoke the joke agent)
mvn spring-boot:run -Dspring-boot.run.arguments="joke"
mvn spring-boot:run -Dspring-boot.run.arguments="joke --topic cats"
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

The app is a minimal **Embabel agent** demo with a Spring Shell interface:

- `EmbabelDemoApplication` — standard Spring Boot entry point
- `DemoShell` — `@ShellComponent` exposing the `joke [topic]` command; delegates to `AgentPlatform`
- `agents/JokeAgent` — `@Agent` with two `@Action` methods forming a pipeline:
  1. `generateJoke(UserInput)` → calls LLM to produce a `Joke(setup, punchline)` record as structured JSON
  2. `explainJoke(Joke)` → `@AchievesGoal`, calls LLM to explain why the joke is funny; returns final `String`

Embabel automatically chains the two actions: the output of `generateJoke` is injected as input to `explainJoke` because the types match.

## Logging personality

`embabel.agent.logging.personality` themes agent lifecycle log messages. Available values: `starwars`, `severance`, `colossus`, `hitchhiker`, `montypython`. Currently commented out in `application.properties`.
