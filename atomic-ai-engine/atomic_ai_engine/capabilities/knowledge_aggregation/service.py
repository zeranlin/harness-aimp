from atomic_ai_engine.base import BaseCapability

class KnowledgeAggregationCapability(BaseCapability):
    capability_code = "knowledge_aggregation"
    depends_on_model_runtime = True
    l4_task_type = "knowledge_aggregation" if True else None

    def build_result(self, payload, model_response=None):
        return {"message": "知识聚合/补全 能力骨架已建立，待补充具体实现", "implemented": False, "model_route": (model_response or {}).get("model_route") if model_response else None}
