from atomic_ai_engine.base import BaseCapability

class RuleEngineCapability(BaseCapability):
    capability_code = "rule_engine"
    depends_on_model_runtime = False

    def build_result(self, payload, model_response=None):
        text = (payload.get("text") or payload.get("document") or "").lower()
        matched = any(token in text for token in ["付款", "违约", "授权", "偏离"])
        return {"matched": matched, "matched_rules": ["payment_clause", "authorization_chain"] if matched else [], "confidence": 0.88 if matched else 0.2}
