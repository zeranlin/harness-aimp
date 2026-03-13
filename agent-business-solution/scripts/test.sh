#!/usr/bin/env sh
set -eu

python -m py_compile /Users/linzeran/code/2026-zn/harnees_aimp/agent-business-solution/app.py
python /Users/linzeran/code/2026-zn/harnees_aimp/agent-business-solution/app.py --self-test
