#!/usr/bin/env sh
set -eu
/Users/linzeran/code/2026-zn/harnees_aimp/atomic-ai-engine/scripts/build.sh
cd /Users/linzeran/code/2026-zn/harnees_aimp/atomic-ai-engine && PYTHONPATH=. python tests/test_registry.py && PYTHONPATH=. python tests/test_engine.py && PYTHONPATH=. python tests/test_capabilities.py
