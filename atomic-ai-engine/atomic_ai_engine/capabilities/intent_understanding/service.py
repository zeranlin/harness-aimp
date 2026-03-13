from atomic_ai_engine.base import BaseCapability

class IntentUnderstandingCapability(BaseCapability):
    capability_code = "intent_understanding"
    depends_on_model_runtime = True
    l4_task_type = "intent_understanding"

    def build_result(self, payload, model_response=None):
        text = str(payload.get("text") or payload.get("question") or "")
        intent = "unknown"
        if any(token in text for token in ["采购", "招标", "投标"]):
            intent = "procurement_review"
        elif any(token in text for token in ["合同", "条款"]):
            intent = "contract_review"
        return {
            "intent": intent,
            "confidence": 0.93 if intent != "unknown" else 0.12,
            "model_route": (model_response or {}).get("model_route", "general-llm-v1"),
            "intent_reason": "基于关键词和模型路由的联合判断",
        }
