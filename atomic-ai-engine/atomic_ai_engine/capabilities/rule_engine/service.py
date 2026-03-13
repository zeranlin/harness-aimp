from atomic_ai_engine.base import BaseCapability

class RuleEngineCapability(BaseCapability):
    capability_code = "rule_engine"
    depends_on_model_runtime = False

    def build_result(self, payload, model_response=None):
        text = (payload.get("text") or payload.get("document") or "").lower()
        rules = {
            "payment_clause": ["付款", "payment"],
            "authorization_chain": ["授权", "authorization"],
            "breach_clause": ["违约", "breach"],
            "qualification_requirement": ["资格", "qualification"],
        }
        matched = []
        for rule, keywords in rules.items():
            if any(k.lower() in text for k in keywords):
                matched.append(rule)
        return {
            "matched": bool(matched),
            "matched_rules": matched,
            "confidence": 0.92 if matched else 0.18,
            "decision": "rule_hit" if matched else "fallback_required",
        }
