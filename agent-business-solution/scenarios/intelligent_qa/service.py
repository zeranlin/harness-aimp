from scenario_runtime.common import ScenarioService


class IntelligentQaService(ScenarioService):
    scenario_code = "intelligent_qa"
    required_field = "input.question"
    l3_capability_code = "intent_retrieval"
    l4_task_type = "qa_generation"
    request_field = "question"

    def extract_input(self, request_payload):
        return request_payload.get("input", {}).get("question") or request_payload.get("prompt") or ""

    def build_success_result(self, source_text, capability_call, model_call):
        return {
            "answer": "已完成智能问答编排，先由原子能力完成证据预处理，再由模型运行时生成结构化回答。",
            "summary": f"问题长度 {len(source_text)}，证据数 {capability_call['payload'].get('evidence_count', 0)}。",
            "risk_level": capability_call["payload"].get("risk_level", "unknown"),
            "model_route": model_call["payload"].get("model_route", "unknown"),
        }
