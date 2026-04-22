# Embabel Ollama Demo

A minimal [Embabel](https://embabel.com) agent demo running against a local LLM via [ramalama](https://github.com/containers/ramalama).

## Prerequisites

- Java 21
- Maven
- ramalama running locally with a compatible model (e.g. `llama3.2`)

Start ramalama:
```bash
ramalama serve library/llama3.2
```

## Running

```bash
# Interactive Spring Shell
mvn spring-boot:run

# Non-interactive: run a single command and exit
mvn spring-boot:run -Dspring-boot.run.arguments="joke"
mvn spring-boot:run -Dspring-boot.run.arguments="joke --topic cats"
```

## What it does

The `joke` shell command invokes a two-step Embabel agent pipeline:

1. **generateJoke** — asks the LLM to produce a joke (setup + punchline) about a topic as structured JSON
2. **explainJoke** — asks the LLM to explain why the joke is funny

The default topic is `software developers`.

## Configuration

Key settings in `src/main/resources/application.properties`:

| Property | Description |
|---|---|
| `embabel.agent.platform.models.openai.base-url` | ramalama server URL |
| `embabel.models.default-llm` | Model name as reported by ramalama (`/v1/models`) |
| `embabel.agent.logging.personality` | Optional themed logging (`starwars`, `hitchhiker`, `montypython`, `severance`, `colossus`) |

To switch models, update `embabel.models.default-llm` in `application.properties` and add a matching entry in `src/main/resources/models/openai-models.yml`.

## Note on ramalama vs Ollama

Despite the endpoint being `localhost:11434`, this app uses ramalama which speaks the **OpenAI wire protocol** — not the native Ollama protocol. The Embabel OpenAI starter (`embabel-agent-starter-openai`) is used accordingly.
