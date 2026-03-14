#!/usr/bin/env bash
set -euo pipefail

ROOT="$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)"
RUNTIME_DIR="$ROOT/.runtime"
mkdir -p "$RUNTIME_DIR"

MODEL_KEY="${AGENT_MODEL_HUB_QWEN35_27B_API_KEY:-test}"

pid_file_for() {
  local name="$1"
  echo "$RUNTIME_DIR/${name}.pid"
}

is_pid_alive() {
  local pid="${1:-}"
  [ -n "$pid" ] && kill -0 "$pid" >/dev/null 2>&1
}

start_service() {
  local name="$1"
  local port="$2"
  local command="$3"
  local log_file="$RUNTIME_DIR/${name}.log"
  local pid_file
  pid_file="$(pid_file_for "$name")"

  if lsof -nP -iTCP:"$port" -sTCP:LISTEN >/dev/null 2>&1; then
    echo "[skip] $name already listening on $port"
    return 0
  fi

  echo "[start] $name on $port"
  rm -f "$pid_file"
  ROOT="$ROOT" COMMAND="$command" LOG_FILE="$log_file" PID_FILE="$pid_file" python3 - <<'PY'
import os
import subprocess

command = os.environ["COMMAND"]
log_file = os.environ["LOG_FILE"]
pid_file = os.environ["PID_FILE"]

with open(log_file, "ab") as log:
    process = subprocess.Popen(
        ["sh", "-lc", command],
        stdin=subprocess.DEVNULL,
        stdout=log,
        stderr=subprocess.STDOUT,
        start_new_session=True,
        close_fds=True,
    )

with open(pid_file, "w", encoding="utf-8") as fh:
    fh.write(str(process.pid))
PY
}

wait_for_service() {
  local name="$1"
  local port="$2"
  local url="$3"
  local pid_file="$4"
  local attempts="${5:-30}"
  local delay="${6:-1}"
  local pid=""

  if [ -f "$pid_file" ]; then
    pid="$(cat "$pid_file" 2>/dev/null || true)"
  fi

  for _ in $(seq 1 "$attempts"); do
    if [ -n "$pid" ] && ! is_pid_alive "$pid"; then
      echo "[error] $name exited before becoming healthy"
      tail -n 80 "$RUNTIME_DIR/${name}.log" || true
      return 1
    fi
    if curl -fsS "$url" >/dev/null 2>&1; then
      if lsof -nP -iTCP:"$port" -sTCP:LISTEN >/dev/null 2>&1; then
        break
      fi
    fi
    sleep "$delay"
  done

  if ! curl -fsS "$url" >/dev/null 2>&1; then
    echo "[error] $name did not become healthy: $url"
    tail -n 80 "$RUNTIME_DIR/${name}.log" || true
    return 1
  fi

  # Confirm the process remains alive briefly after health passes.
  for _ in $(seq 1 3); do
    if [ -n "$pid" ] && ! is_pid_alive "$pid"; then
      echo "[error] $name became unhealthy immediately after startup"
      tail -n 80 "$RUNTIME_DIR/${name}.log" || true
      return 1
    fi
    if ! lsof -nP -iTCP:"$port" -sTCP:LISTEN >/dev/null 2>&1; then
      echo "[error] $name is no longer listening on $port"
      tail -n 80 "$RUNTIME_DIR/${name}.log" || true
      return 1
    fi
    sleep 1
  done

  echo "[ok] $name -> $url"
}

start_service "l3-atomic-ai-engine" 8003 "cd '$ROOT/atomic-ai-engine' && exec ./scripts/run.sh"
wait_for_service "l3-atomic-ai-engine" 8003 "http://127.0.0.1:8003/health" "$(pid_file_for "l3-atomic-ai-engine")"

start_service "l2-agent-business-solution" 8002 "cd '$ROOT/agent-business-solution' && exec ./scripts/run.sh"
wait_for_service "l2-agent-business-solution" 8002 "http://127.0.0.1:8002/health" "$(pid_file_for "l2-agent-business-solution")"

start_service "l5-agent-knowledge-ops" 8004 "cd '$ROOT/agent-knowledge-ops' && exec ./scripts/run.sh"
wait_for_service "l5-agent-knowledge-ops" 8004 "http://127.0.0.1:8004/health" "$(pid_file_for "l5-agent-knowledge-ops")"

start_service "l6-agent-model-hub" 8005 "cd '$ROOT/agent-model-hub' && exec ./scripts/run.sh"
wait_for_service "l6-agent-model-hub" 8005 "http://127.0.0.1:8005/health" "$(pid_file_for "l6-agent-model-hub")"

start_service "l4-agent-model-runtime" 8081 "cd '$ROOT/agent-model-runtime' && AGENT_MODEL_HUB_QWEN35_27B_API_KEY='$MODEL_KEY' exec ./scripts/run.sh"
wait_for_service "l4-agent-model-runtime" 8081 "http://127.0.0.1:8081/health" "$(pid_file_for "l4-agent-model-runtime")"

start_service "l1-agent-gateway-basic" 8080 "cd '$ROOT/agent-gateway-basic' && exec ./scripts/run.sh"
wait_for_service "l1-agent-gateway-basic" 8080 "http://127.0.0.1:8080/health" "$(pid_file_for "l1-agent-gateway-basic")"

echo
echo "Environment restored."
echo "Debug console: http://127.0.0.1:8080/console/debug"
