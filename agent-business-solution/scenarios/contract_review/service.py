from scenario_runtime.common import SdkBackedScenarioService


class ContractReviewService(SdkBackedScenarioService):
    scenario_code = "contract_review"
    required_field = "input.contract_text"
    l3_capability_code = "structured_extraction"
    def extract_input(self, request_payload):
        return request_payload.get("input", {}).get("contract_text") or request_payload.get("document") or request_payload.get("prompt") or ""

    def build_success_result(self, source_text, capability_response):
        fields = capability_response.get("result", {}).get("fields", {})
        risk_level = fields.get("risk_level", "unknown")
        extracted = fields.get("extracted_fields", {})
        return {
            "review_summary": "已完成合同审查编排，当前通过 L3 SDK 直接完成结构化提取与模型兜底。",
            "review_points": [
                "已完成基础条款提取",
                f"支付条款命中={extracted.get('payment_terms_present', False)}",
                f"违约条款命中={extracted.get('breach_clause_present', False)}",
            ],
            "risk_level": risk_level,
            "model_route": capability_response.get("result", {}).get("model_route", "unknown"),
            "document_length": len(source_text),
        }
