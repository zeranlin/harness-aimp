from scenario_runtime.common import SdkBackedScenarioService


class ComplianceReviewService(SdkBackedScenarioService):
    scenario_code = "compliance_review"
    required_field = "input.review_text"
    l3_capability_code = "structured_extraction"
    def extract_input(self, request_payload):
        return request_payload.get("input", {}).get("review_text") or request_payload.get("document") or request_payload.get("prompt") or ""

    def build_success_result(self, source_text, capability_response):
        fields = capability_response.get("result", {}).get("fields", {})
        risk_level = fields.get("risk_level", "unknown")
        issues = []
        if fields.get("authorization_issue"):
            issues.append("存在授权链异常")
        if fields.get("deviation_detected"):
            issues.append("检测到条款偏离")
        if not issues:
            issues.append("未发现明显合规异常")
        return {
            "review_summary": "已完成合规审查编排，当前通过 L3 SDK 直接获取结构化审查结果。",
            "findings": issues,
            "risk_level": risk_level,
            "recommended_action": "建议进入人工复核" if risk_level in {"medium", "high"} else "可自动通过",
            "model_route": capability_response.get("result", {}).get("model_route", "unknown"),
        }
