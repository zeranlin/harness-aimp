#!/usr/bin/env bash
set -euo pipefail

ROOT="$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)"
RUNTIME_DIR="$ROOT/.runtime"
mkdir -p "$RUNTIME_DIR"

MODEL_KEY="${AGENT_MODEL_HUB_QWEN35_27B_API_KEY:-test}"

start_service() {
  local name="$1"
  local port="$2"
  local command="$3"
  local log_file="$RUNTIME_DIR/${name}.log"

  if lsof -nP -iTCP:"$port" -sTCP:LISTEN >/dev/null 2>&1; then
    echo "[skip] $name already listening on $port"
    return 0
  fi

  echo "[start] $name on $port"
  nohup sh -c "$command" >"$log_file" 2>&1 &
}

wait_for_http() {
  local name="$1"
  local url="$2"
  local attempts="${3:-30}"
  local delay="${4:-1}"

  for _ in $(seq 1 "$attempts"); do
    if curl -fsS "$url" >/dev/null 2>&1; then
      echo "[ok] $name -> $url"
      return 0
    fi
    sleep "$delay"
  done

  echo "[error] $name did not become healthy: $url"
  return 1
}

start_service "l3-atomic-ai-engine" 8003 "cd '$ROOT/atomic-ai-engine' && exec ./scripts/run.sh"
wait_for_http "L3" "http://127.0.0.1:8003/health"

start_service "l2-agent-business-solution" 8002 "cd '$ROOT/agent-business-solution' && exec ./scripts/run.sh"
wait_for_http "L2" "http://127.0.0.1:8002/health"

start_service "l5-agent-knowledge-ops" 8004 "cd '$ROOT/agent-knowledge-ops' && exec ./scripts/run.sh"
wait_for_http "L5" "http://127.0.0.1:8004/health"

start_service "l6-agent-model-hub" 8005 "cd '$ROOT/agent-model-hub' && exec ./scripts/run.sh"
wait_for_http "L6" "http://127.0.0.1:8005/health"

start_service "l4-agent-model-runtime" 8081 "cd '$ROOT/agent-model-runtime' && AGENT_MODEL_HUB_QWEN35_27B_API_KEY='$MODEL_KEY' exec ./scripts/run.sh"
wait_for_http "L4" "http://127.0.0.1:8081/health"

start_service "l1-agent-gateway-basic" 8080 "cd '$ROOT/agent-gateway-basic' && exec ./scripts/run.sh"
wait_for_http "L1" "http://127.0.0.1:8080/health"

echo
echo "Environment restored."
echo "Debug console: http://127.0.0.1:8080/console/debug"
