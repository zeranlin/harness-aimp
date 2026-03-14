#!/usr/bin/env bash
set -euo pipefail

ROOT="$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)"
RUNTIME_DIR="$ROOT/.runtime"

remove_pid_file() {
  local name="$1"
  rm -f "$RUNTIME_DIR/${name}.pid"
}

stop_port() {
  local name="$1"
  local port="$2"
  local pids

  pids="$(lsof -tiTCP:"$port" -sTCP:LISTEN || true)"
  if [ -z "$pids" ]; then
    echo "[skip] $name not running on $port"
    return 0
  fi

  echo "[stop] $name on $port"
  kill $pids
  remove_pid_file "$name"
}

stop_port "l1-agent-gateway-basic" 8080
stop_port "l4-agent-model-runtime" 8081
stop_port "l6-agent-model-hub" 8005
stop_port "l5-agent-knowledge-ops" 8004
stop_port "l2-agent-business-solution" 8002
stop_port "l3-atomic-ai-engine" 8003

echo
echo "Environment stopped."
