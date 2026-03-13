from scenario_runtime.common import CONFIG_FILE, SCENARIOS_DIR, load_json
from scenarios.contract_review.service import ContractReviewService
from scenarios.compliance_review.service import ComplianceReviewService
from scenarios.intelligent_qa.service import IntelligentQaService


class ScenarioRegistry:
    def __init__(self, config_file=CONFIG_FILE, scenarios_dir=SCENARIOS_DIR):
        config = load_json(config_file)
        self.scenarios = {}
        for scenario_code in config.get("scenarios", []):
            manifest_path = scenarios_dir / scenario_code / "manifest.json"
            self.scenarios[scenario_code] = load_json(manifest_path)

    def list(self):
        return list(self.scenarios.values())

    def get(self, scenario_code):
        return self.scenarios.get(scenario_code)


class ScenarioRuntime:
    def __init__(self, registry):
        self.registry = registry
        self.services = {
            "intelligent_qa": IntelligentQaService(registry.get("intelligent_qa")),
            "contract_review": ContractReviewService(registry.get("contract_review")),
            "compliance_review": ComplianceReviewService(registry.get("compliance_review")),
        }

    def execute(self, payload):
        scenario_code = payload.get("scenario_code") or "intelligent_qa"
        service = self.services.get(scenario_code)
        if not service:
            return 404, {"status": "error", "message": "scenario not found"}
        return service.execute(payload)
