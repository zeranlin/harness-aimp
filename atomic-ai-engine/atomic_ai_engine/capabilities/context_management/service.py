from atomic_ai_engine.base import BaseCapability

class ContextManagementCapability(BaseCapability):
    capability_code = "context_management"
    depends_on_model_runtime = False
    l4_task_type = "context_management" if False else None

    def build_result(self, payload, model_response=None):
        return {"message": "上下文管理 能力骨架已建立，待补充具体实现", "implemented": False, "model_route": (model_response or {}).get("model_route") if model_response else None}
