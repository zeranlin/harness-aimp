from atomic_ai_engine.base import BaseCapability

class LogicTreeExplanationCapability(BaseCapability):
    capability_code = "logic_tree_explanation"
    depends_on_model_runtime = True
    l4_task_type = "logic_tree_explanation" if True else None

    def build_result(self, payload, model_response=None):
        return {"message": "逻辑树解释 能力骨架已建立，待补充具体实现", "implemented": False, "model_route": (model_response or {}).get("model_route") if model_response else None}
