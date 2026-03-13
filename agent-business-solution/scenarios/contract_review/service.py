from scenario_runtime.common import ScenarioService


class ContractReviewService(ScenarioService):
    scenario_code = "contract_review"
    required_field = "input.contract_text"
    l3_capability_code = "structured_extraction"
    l4_task_type = "contract_review"
    request_field = "payload"

    def extract_input(self, request_payload):
        return request_payload.get("input", {}).get("contract_text") or request_payload.get("document") or request_payload.get("prompt") or ""

    def build_success_result(self, source_text, capability_call, model_call):
        evidence_count = capability_call["payload"].get("evidence_count", 0)
        risk_level = capability_call["payload"].get("risk_level", "unknown")
        return {
            "review_summary": "已完成合同审查编排，已对合同文本完成结构化提取并输出审查摘要。",
            "review_points": [
                "已完成基础条款提取",
                f"识别到 {evidence_count} 条候选证据",
                f"风险等级为 {risk_level}",
            ],
            "risk_level": risk_level,
            "model_route": model_call["payload"].get("model_route", "unknown"),
            "document_length": len(source_text),
        }
