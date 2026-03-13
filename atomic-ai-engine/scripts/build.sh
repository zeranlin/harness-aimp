#!/usr/bin/env sh
set -eu
python -m py_compile /Users/linzeran/code/2026-zn/harnees_aimp/atomic-ai-engine/app.py
find /Users/linzeran/code/2026-zn/harnees_aimp/atomic-ai-engine/atomic_ai_engine -name "*.py" -print0 | xargs -0 -n1 python -m py_compile
