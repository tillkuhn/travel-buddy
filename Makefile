
# Interesting links:
# Best LLMs for OpenCode - Tested Locally
# https://dev.to/rosgluk/best-llms-for-opencode-tested-locally-499l
# Clear winner for local: Qwen 3.5 27b Q3_XXS on llama.cpp
# Surprisingly good: Bigpicle (from OpenCode Zen) 
# ollama pull "qwen3.5:27b"
#
# Comparing LLMs performance on Ollama on 16GB VRAM GPU
# https://www.glukhov.org/llm-performance/benchmarks/choosing-best-llm-for-ollama-on-16gb-vram-gpu/
#
#
# Build Your Own Local AI Coding Agent with Gemma 4 and OpenCode
# https://towardsdatascience.com/build-your-own-local-ai-coding-agent-with-gemma-4-and-opencode-2/
# ollama pull "gemma4:e2b" or "gemma4:e4b"
# 
#LLM_MODEL ?= tinyllama # (481 MB, 2048 tokens context,  ~1.1B params)
#LLM_MODEL ?= granite:2b # meta-llama/Llama-3.2-1B-Instruct
LLM_MODEL ?= gemma4:e2b #  or e4b, from https://towardsdatascience.com/build-your-own-local-ai-coding-agent-with-gemma-4-and-opencode-2/
LLM_PORT ?= 11434

.PHONY: help
help:
	@grep -E "^$$PFX[0-9a-zA-Z_-]+:.*?## .*$$" $(MAKEFILE_LIST) | sort | awk 'BEGIN {FS = ":.*?## "}; {printf "  \033[36m%-20s\033[0m %s\n", $$1, $$2}'; echo "";\

.PHONY: run
run: ## runs the spring-boot app
	mvn spring-boot:run

.PHONY: test
test: ## run tests
	mvn test -pl .

# tinyllama llama3.2
.PHONY: llm-run
llm-run: ## run ramalama with LLM model, use 'LLM_MODEL=shortname make run' to run with a different model
	ramalama --dry-run serve $(LLM_MODEL) --port $(LLM_PORT) --name ramalama --max-tokens=5000 --thinking=False
	@ramalama serve $(LLM_MODEL) --port $(LLM_PORT) --name ramalama --max-tokens=5000 --thinking=False

.PHONY: ollama-run
ollama-run: ## run ollama with LLM model, use 'LLM_MODEL=
  OLLAMA_FLASH_ATTENTION="1" OLLAMA_KV_CACHE_TYPE="q8_0" /opt/homebrew/opt/ollama/bin/ollama serve

.PHONY: llm-shortnames
llm-shortnames: ## run ramalama info with filter on LLM shortnames
	ramalama info | jq  .Shortnames.Names
	curl -sSL localhost:$(LLM_PORT)/v1/models | jq .

.PHONY: llm-tests
llm-test: ## run curl chat
	curl -s http://localhost:$(LLM_PORT)/v1/chat/completions -H "Content-Type: application/json" -H "Authorization: Bearer test" \
		-d '{"model": "any-model","messages": [{"role": "user", "content": "Hello, who are you?"}]}' | jq .

.PHONY: llm-models
llm-models: ## show models by calling /v1/models endpoint
	curl -s http://localhost:$(LLM_PORT)/v1/models |jq .
