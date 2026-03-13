from scenario_runtime.common import ScenarioService


class ComplianceReviewService(ScenarioService):
    scenario_code = "compliance_review"
    required_field = "input.review_text"
    l3_capability_code = "structured_extraction"
    l4_task_type = "compliance_review"
    request_field = "payload"

    def extract_input(self, request_payload):
        return request_payload.get("input", {}).get("review_text") or request_payload.get("document") or request_payload.get("prompt") or ""

    def build_success_result(self, source_text, capability_call, model_call):
        risk_level = capability_call["payload"].get("risk_level", "unknown")
        return {
            "review_summary": "已完成合规审查编排，输出风险等级与处理建议。",
            "findings": [
                "已完成规则要点抽取",
                f"候选证据数 {capability_call['payload'].get('evidence_count', 0)}",
                f"风险等级 {risk_level}",
            ],
            "risk_level": risk_level,
            "recommended_action": "建议进入人工复核" if risk_level in {"medium", "high"} else "可自动通过",
            "model_route": model_call["payload"].get("model_route", "unknown"),
        }
