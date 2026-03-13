import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT))

from scenario_runtime.runtime import ScenarioRegistry, ScenarioRuntime
from scenario_runtime.common import summarize_executions


def run():
    registry = ScenarioRegistry()
    assert registry.get("intelligent_qa")["scenario_code"] == "intelligent_qa"
    assert registry.get("contract_review")["scenario_code"] == "contract_review"
    assert registry.get("compliance_review")["scenario_code"] == "compliance_review"

    runtime = ScenarioRuntime(registry)
    status_code, response = runtime.execute({"scenario_code": "missing"})
    assert status_code == 404
    assert response["message"] == "scenario not found"

    summary = summarize_executions([
        {"scenario_code": "intelligent_qa", "status": "success", "duration_ms": 10, "errors": []},
        {"scenario_code": "contract_review", "status": "success", "duration_ms": 20, "errors": []},
        {"scenario_code": "compliance_review", "status": "error", "duration_ms": 30, "errors": [{"code": "UPSTREAM_L3_UNAVAILABLE", "message": "timeout"}]},
    ])
    assert summary["total"] == 3
    assert summary["scenarios"]["compliance_review"]["error"] == 1
    assert summary["error_codes"]["UPSTREAM_L3_UNAVAILABLE"] == 1
    print("scenario-tests ok")


if __name__ == "__main__":
    run()
