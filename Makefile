.PHONY: help
help:
	@grep -E "^$$PFX[0-9a-zA-Z_-]+:.*?## .*$$" $(MAKEFILE_LIST) | sort | awk 'BEGIN {FS = ":.*?## "}; {printf "  \033[36m%-20s\033[0m %s\n", $$1, $$2}'; echo "";\

.PHONY: run
run: ## run app
	mvn spring-boot:run

# tinyllama llama3.2
.PHONY: llama
llama: ## run tinyllama TheBloke/TinyLlama-1.1B-Chat-v1.0-GGUF (481 MB, 2048 tokens context,  ~1.1B params)
	ramalama serve tinyllama --port 11434 --name llama --max-tokens=56789 --thinking=False

chat: ## run curl chat
	curl -s http://localhost:11434/v1/chat/completions -H "Content-Type: application/json" -H "Authorization: Bearer test" -d '{"model": "any-model","messages": [{"role": "user", "content": "Hello, who are you?"}]}'

models: ## show models
	curl -s http://localhost:11434/v1/models |jq .