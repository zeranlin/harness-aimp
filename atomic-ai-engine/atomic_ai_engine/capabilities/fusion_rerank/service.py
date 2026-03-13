from atomic_ai_engine.base import BaseCapability

class FusionRerankCapability(BaseCapability):
    capability_code = "fusion_rerank"
    depends_on_model_runtime = False
    l4_task_type = "fusion_rerank" if False else None

    def build_result(self, payload, model_response=None):
        return {"message": "融合重排序 能力骨架已建立，待补充具体实现", "implemented": False, "model_route": (model_response or {}).get("model_route") if model_response else None}
