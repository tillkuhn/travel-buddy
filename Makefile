
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

MOCK_OIDC_PORT ?= 9200
MOCK_GATEWAY_PORT ?= 9300
MOCK_TOKEN_TTL ?= 10
MOCK_FIXED_DESTINATION ?= random
MOCK_DIR := mocks

.PHONY: help
help:
	@grep -E "^$$PFX[0-9a-zA-Z_-]+:.*?## .*$$" $(MAKEFILE_LIST) | sort | awk 'BEGIN {FS = ":.*?## "}; {printf "  \033[36m%-20s\033[0m %s\n", $$1, $$2}'; echo "";\

.PHONY: run
run: ## [scenario 1, default] runs the app against local ramalama (localhost:11434)
	@[ -f application-local.properties ] && echo "⚠️  ./application-local.properties exists and will override these defaults - see 'make run-remote'" || true
	@lsof -tiTCP:8080 -sTCP:LISTEN >/dev/null 2>&1 && echo "⚠️  port 8080 is already in use - the app may fail to start; run 'make stop' first if a stale instance (e.g. from run-mock) is still running" || true
	mvn spring-boot:run

.PHONY: run-mock
run-mock: mocks-start ## [scenario 2] runs the app against the mock OIDC + AI gateway (make mocks-start/-stop), config is committed (application-mock.properties)
	mvn spring-boot:run -Dspring-boot.run.profiles=mock

.PHONY: run-remote
run-remote: ## [scenario 3] runs the app against the real remote AI gateway; requires ./application-local.properties (gitignored, not in version control)
	@[ -f application-local.properties ] || { \
		echo "❌ ./application-local.properties not found."; \
		echo "   Copy src/main/resources/application-local.properties.example to ./application-local.properties (project root)"; \
		echo "   and fill in the real gateway URL + OAuth2 credentials. See README.md."; \
		exit 1; \
	}
	mvn spring-boot:run

.PHONY: stop
stop: ## kill any app instance (from run/run-mock/run-remote) still listening on port 8080
	@PID=$$(lsof -tiTCP:8080 -sTCP:LISTEN 2>/dev/null); \
	if [ -n "$$PID" ]; then \
		kill $$PID && echo "stopped app on port 8080 (pid $$PID)"; \
	else \
		echo "nothing listening on port 8080"; \
	fi

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

.PHONY: mocks-start
mocks-start: ## start mock OIDC (auth) + mock AI gateway in background if not already running, see mocks/README.md
	@if lsof -nP -iTCP:$(MOCK_OIDC_PORT) -sTCP:LISTEN >/dev/null 2>&1; then \
		echo "mock-oidc:    already running on $(MOCK_OIDC_PORT)"; \
	else \
		MOCK_TOKEN_TTL=$(MOCK_TOKEN_TTL) MOCK_OIDC_PORT=$(MOCK_OIDC_PORT) nohup python3 -u $(MOCK_DIR)/mock_oidc.py > $(MOCK_DIR)/.mock_oidc.log 2>&1 & echo $$! > $(MOCK_DIR)/.mock_oidc.pid; \
		sleep 1; \
		echo "mock-oidc:    http://localhost:$(MOCK_OIDC_PORT) (pid $$(cat $(MOCK_DIR)/.mock_oidc.pid), token ttl $(MOCK_TOKEN_TTL)s)"; \
	fi
	@if lsof -nP -iTCP:$(MOCK_GATEWAY_PORT) -sTCP:LISTEN >/dev/null 2>&1; then \
		echo "mock-gateway: already running on $(MOCK_GATEWAY_PORT)"; \
	else \
		MOCK_GATEWAY_PORT=$(MOCK_GATEWAY_PORT) MOCK_FIXED_DESTINATION=$(MOCK_FIXED_DESTINATION) nohup python3 -u $(MOCK_DIR)/mock_gateway.py > $(MOCK_DIR)/.mock_gateway.log 2>&1 & echo $$! > $(MOCK_DIR)/.mock_gateway.pid; \
		sleep 1; \
		echo "mock-gateway: http://localhost:$(MOCK_GATEWAY_PORT) (pid $$(cat $(MOCK_DIR)/.mock_gateway.pid))"; \
	fi

.PHONY: mocks-stop
mocks-stop: ## stop mock OIDC + AI gateway started via mocks-start
	-@[ -f $(MOCK_DIR)/.mock_oidc.pid ] && kill $$(cat $(MOCK_DIR)/.mock_oidc.pid) 2>/dev/null; rm -f $(MOCK_DIR)/.mock_oidc.pid
	-@[ -f $(MOCK_DIR)/.mock_gateway.pid ] && kill $$(cat $(MOCK_DIR)/.mock_gateway.pid) 2>/dev/null; rm -f $(MOCK_DIR)/.mock_gateway.pid
	@echo "mocks stopped"

.PHONY: mocks-status
mocks-status: ## show whether mocks are running and on which ports
	@lsof -nP -iTCP:$(MOCK_OIDC_PORT) -sTCP:LISTEN 2>/dev/null || echo "mock-oidc: not running on $(MOCK_OIDC_PORT)"
	@lsof -nP -iTCP:$(MOCK_GATEWAY_PORT) -sTCP:LISTEN 2>/dev/null || echo "mock-gateway: not running on $(MOCK_GATEWAY_PORT)"

.PHONY: mocks-logs molo
mocks-logs molo: ## tail logs of both backgrounded mocks (started via mocks-start/run-mock), ctrl-c to stop watching (alias: molo)
	tail -f $(MOCK_DIR)/.mock_oidc.log $(MOCK_DIR)/.mock_gateway.log
