from atomic_ai_engine.base import BaseCapability

class IntentUnderstandingCapability(BaseCapability):
    capability_code = "intent_understanding"
    depends_on_model_runtime = True
    l4_task_type = "intent_understanding"

    def build_result(self, payload, model_response=None):
        text = payload.get("text") or payload.get("question") or ""
        return {"intent": "procurement_review" if text else "unknown", "confidence": 0.91 if text else 0.0, "model_route": (model_response or {}).get("model_route", "general-llm-v1")}
