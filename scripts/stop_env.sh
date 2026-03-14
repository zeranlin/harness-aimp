#!/usr/bin/env bash
set -euo pipefail

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
}

stop_port "L1" 8080
stop_port "L4" 8081
stop_port "L6" 8005
stop_port "L5" 8004
stop_port "L2" 8002
stop_port "L3" 8003

echo
echo "Environment stopped."
