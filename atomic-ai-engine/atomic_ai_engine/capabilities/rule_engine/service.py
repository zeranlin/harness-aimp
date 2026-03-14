from atomic_ai_engine.base import BaseCapability

class RuleEngineCapability(BaseCapability):
    capability_code = "rule_engine"
    depends_on_model_runtime = False

    def build_result(self, payload, model_response=None):
        return {
            "matched": False,
            "matched_rules": [],
            "confidence": 0.0,
            "decision": "fallback_required",
            "mode": "temporary_force_fallback",
        }
