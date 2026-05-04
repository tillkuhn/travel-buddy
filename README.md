# 🗺️ Embabel Travel Buddy

An [Embabel](https://embabel.com) agent demo in top of [spring-boot](https://spring.io/projects/spring-boot) that suggests travel destinations based on user preferences, running against a local LLM served by [RamaLama](https://github.com/containers/ramalama).

![](./impression.png)

> [Embabel](https://github.com/embabel/embabel-agent) (Em-BAY-bel) is a framework for authoring agentic flows on the JVM that seamlessly mix LLM-prompted interactions with code and domain models. Supports intelligent path finding towards goals. Written in Kotlin but offers a natural usage model from Java. From the creator of Spring.
 
 
> [RamaLama](https://ramalama.ai/) is a LI for running AI models in containers on your machine. Instead of manually managing model runtimes and dependencies, you plug into an existing container engine such as Podman or Docker ([Source](https://www.redhat.com/en/blog/run-containerized-ai-models-locally-ramalama)).

## Prerequisites

- Java 25+
- Maven
- RamaLama running locally with a compatible model (e.g. `tinyllama`,`llama3.2`)

Install / start ramalama:
```bash
$ brew info ramalama
==> ramalama ✔: stable 0.19.0 (bottled)
Goal of RamaLama is to make working with AI boring
(...)
```
```bash
$ ramalama serve tinyllama --port 11434 --name llama --max-tokens=2000 --thinking=False
0.19: Pulling from ramalama/ramalama
Status: Image is up to date for quay.io/ramalama/ramalama:0.19
(...)
main: server is listening on http://0.0.0.0:11434
```

## Does Model <insert-fancy-LLM-here> run on my hardware?

Check out [llmfit](https://github.com/AlexsJones/llmfit) ...

> A terminal tool that right-sizes LLM models to your system's RAM, CPU, and GPU. Detects your hardware, scores each model across quality, speed, fit, and context dimensions, and tells you which ones will actually run well on your machine.

```
brew info llmfit
==> llmfit ✔: stable 0.9.19 (bottled), HEAD
Find what models run on your hardware
```

![](./docs/llmfit.png)

**NOTE:** RamaLama defaults to the Ollama registry transport. To make it easier for users, RamaLama uses shortname files, which contain aliases for fully specified AI Models, on brew-ramalama it's located in `/opt/homebrew/Cellar/ramalama/0.20.0/libexec/share/ramalama/shortnames.conf`, or just run `ramalama info`

## Running the app

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

Key LLM settings in `src/main/resources/application.properties`:

| Property                                        | Description                                                                                |
|-------------------------------------------------|--------------------------------------------------------------------------------------------|
| `embabel.agent.platform.models.openai.base-url` | ramalama server URL, e.g. http://localhost:11434                                           |
| `embabel.models.default-llm`                    | Model name as reported by ramalama (`/v1/models`), e.g. gpt-4.1-mini                       |
| `embabel.agent.logging.personality`             | Optional themed logging (`starwars`, `hitchhiker`, `montypython`, `severance`, `colossus`) |

To switch models, update `embabel.models.default-llm` in `application.properties` and add a matching entry in `src/main/resources/models/openai-models.yml`.

## Note on ramalama vs Ollama

Despite the endpoint being `localhost:11434`, this app uses ramalama which speaks the **OpenAI wire protocol** — not the native Ollama protocol. The Embabel OpenAI starter (`embabel-agent-starter-openai`) is used accordingly.

