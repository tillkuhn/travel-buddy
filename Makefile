#LLM_MODEL ?= tinyllama # (481 MB, 2048 tokens context,  ~1.1B params)
LLM_MODEL ?= granite:2b # meta-llama/Llama-3.2-1B-Instruct
LLM_PORT ?= 11434

.PHONY: help
help:
	@grep -E "^$$PFX[0-9a-zA-Z_-]+:.*?## .*$$" $(MAKEFILE_LIST) | sort | awk 'BEGIN {FS = ":.*?## "}; {printf "  \033[36m%-20s\033[0m %s\n", $$1, $$2}'; echo "";\

.PHONY: run
run: ## run app
	mvn spring-boot:run

.PHONY: test
test: ## run tests
	mvn test -pl .

# tinyllama llama3.2
.PHONY: llm
llm: ## run ramalama with LLM model, use 'LLM_MODEL=shortname make run' to run with a different model
	ramalama --dry-run serve $(LLM_MODEL) --port $(LLM_PORT) --name ramalama --max-tokens=5000 --thinking=False
	@ramalama serve $(LLM_MODEL) --port $(LLM_PORT) --name ramalama --max-tokens=5000 --thinking=False

.PHONY: llm-shortnames
llm-shortnames: ## run ramalama info with filter on LLM shortnames
	ramalama info | jq  .Shortnames.Names

	curl $(LLM_MODEL)/$(LLM_PORT)/v1/models | jq .

.PHONY: llm-tests
llm-test: ## run curl chat
	curl -s http://localhost:$(LLM_PORT)/v1/chat/completions -H "Content-Type: application/json" -H "Authorization: Bearer test" -d '{"model": "any-model","messages": [{"role": "user", "content": "Hello, who are you?"}]}'

.PHONY: llm-models
llm-models: ## show models by calling /v1/models endpoint
	curl -s http://localhost:$(LLM_PORT)/v1/models |jq .
