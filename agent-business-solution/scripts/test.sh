#!/usr/bin/env sh
set -eu

python -m py_compile /Users/linzeran/code/2026-zn/harnees_aimp/agent-business-solution/app.py
python -m py_compile /Users/linzeran/code/2026-zn/harnees_aimp/agent-business-solution/scenario_runtime/common.py
python -m py_compile /Users/linzeran/code/2026-zn/harnees_aimp/agent-business-solution/scenario_runtime/runtime.py
python -m py_compile /Users/linzeran/code/2026-zn/harnees_aimp/agent-business-solution/scenarios/intelligent_qa/service.py
python -m py_compile /Users/linzeran/code/2026-zn/harnees_aimp/agent-business-solution/scenarios/contract_review/service.py
python -m py_compile /Users/linzeran/code/2026-zn/harnees_aimp/agent-business-solution/scenarios/compliance_review/service.py
cd /Users/linzeran/code/2026-zn/harnees_aimp/agent-business-solution && python tests/test_scenarios.py
python /Users/linzeran/code/2026-zn/harnees_aimp/agent-business-solution/app.py --self-test
