# Embabel Travel Planner

An [Embabel](https://embabel.com) agent demo that suggests travel destinations based on user preferences, running against a local LLM via [ramalama](https://github.com/containers/ramalama).

> [Embabel](https://github.com/embabel/embabel-agent) (Em-BAY-bel) is a framework for authoring agentic flows on the JVM that seamlessly mix LLM-prompted interactions with code and domain models. Supports intelligent path finding towards goals. Written in Kotlin but offers a natural usage model from Java. From the creator of Spring.
## Prerequisites

- Java 21+
- Maven
- ramalama running locally with a compatible model (e.g. `llama3.2`)

Start ramalama:
```bash
ramalama serve llama3.2 --port 11434 --name llama --max-tokens=123456 --thinking=False
```

> **Note:** `llama3.2` is registered as `library/llama3.2` and requires 16–20 GB of memory.
> For a lighter alternative (~1 GB): `ramalama serve tinyllama --port 11434 --name llama --max-tokens=56789 --thinking=False`

## Running

```bash
mvn spring-boot:run
```

Then open [http://localhost:8080](http://localhost:8080) in your browser.

## What it does

Fill out the form with your travel preferences:

- **Region** — Americas, Southeast Asia, or Europe
- **Activities** — Hiking, Skiing, Diving, Beachtime, Culture, Shopping, Cycling (multi-select)
- **Additional Wishes** — any free-text notes (e.g. "family-friendly", "budget travel")

On submit, the app builds a prompt from your selections and invokes a single-step Embabel agent (`TravelPlannerAgent`) which asks the LLM for a concrete destination recommendation. The result is displayed on the next page.

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
