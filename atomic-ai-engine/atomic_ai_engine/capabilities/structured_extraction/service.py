from atomic_ai_engine.base import BaseCapability

class StructuredExtractionCapability(BaseCapability):
    capability_code = "structured_extraction"
    depends_on_model_runtime = True
    l4_task_type = "structured_extraction"

    def build_result(self, payload, model_response=None):
        document = payload.get("document") or payload.get("text") or ""
        return {"fields": {"document_length": len(str(document)), "risk_level": "medium" if document else "low"}, "model_route": (model_response or {}).get("model_route", "general-llm-v1")}
