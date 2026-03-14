from atomic_ai_engine.base import BaseCapability
import json

class StructuredExtractionCapability(BaseCapability):
    capability_code = "structured_extraction"
    depends_on_model_runtime = True
    l4_task_type = "structured_extraction"

    def build_model_payload(self, request_id, payload, context=None, options=None):
        document = str(payload.get("document") or payload.get("text") or "")
        prompt = (
            "你是采购与合规审查助手。请基于下面的文档内容，输出结构化审查结论。\n\n"
            "请严格遵守以下要求：\n"
            "1. 只输出 JSON\n"
            "2. 不要输出 Thinking Process\n"
            "3. 不要输出解释说明\n"
            "4. 不要输出 Markdown\n"
            "5. 所有字段必须完整\n\n"
            "输出格式：\n"
            "{\n"
            '  "payment_terms_present": true,\n'
            '  "payment_terms_reason": "识别到付款比例、付款节点或支付条件描述。",\n'
            '  "breach_clause_present": true,\n'
            '  "breach_clause_reason": "识别到违约责任、违约金或履约处罚描述。",\n'
            '  "authorization_issue": false,\n'
            '  "authorization_issue_reason": "未发现授权链异常，或文档未出现授权委托不完整描述。",\n'
            '  "deviation_detected": false,\n'
            '  "deviation_reason": "未发现明显技术参数或商务条款偏离描述。",\n'
            '  "risk_level": "low",\n'
            '  "risk_reasons": ["风险判断依据 1", "风险判断依据 2"],\n'
            '  "review_summary": "中文摘要"\n'
            "}\n\n"
            "示例输出：\n"
            "{\n"
            '  "payment_terms_present": false,\n'
            '  "payment_terms_reason": "文档未出现明确付款节点、比例或支付条件。",\n'
            '  "breach_clause_present": true,\n'
            '  "breach_clause_reason": "文档出现违约责任或违约处罚描述。",\n'
            '  "authorization_issue": false,\n'
            '  "authorization_issue_reason": "未发现授权链异常线索。",\n'
            '  "deviation_detected": true,\n'
            '  "deviation_reason": "文档存在技术参数或条款偏离说明。",\n'
            '  "risk_level": "medium",\n'
            '  "risk_reasons": ["存在违约责任条款", "存在技术或条款偏离说明"],\n'
            '  "review_summary": "文件存在违约责任和条款偏离描述，建议进一步复核。"\n'
            "}\n\n"
            "如果文档信息不足，也必须返回合法 JSON，并根据现有内容给出最接近的判断。\n\n"
            "文档内容：\n"
            f"{document}"
        )
        return {
            "request_id": request_id,
            "task_type": self.l4_task_type,
            "input": payload,
            "prompt": prompt,
        }

    def build_result(self, payload, model_response=None):
        document = str(payload.get("document") or payload.get("text") or "")
        runtime_result = ((model_response or {}).get("result") or {}) if isinstance(model_response, dict) else {}
        structured_json = runtime_result.get("structured_result_json") or runtime_result.get("structured_result") or "{}"
        try:
            structured_payload = json.loads(structured_json) if isinstance(structured_json, str) else (structured_json or {})
        except json.JSONDecodeError:
            structured_payload = {}
        payment_terms_present = structured_payload.get("payment_terms_present", "付款" in document)
        breach_clause_present = structured_payload.get("breach_clause_present", "违约" in document)
        authorization_issue = structured_payload.get("authorization_issue", ("授权" in document and "不完整" in document))
        deviation_detected = structured_payload.get("deviation_detected", ("偏离" in document))
        risk_level = structured_payload.get("risk_level") or ("high" if authorization_issue else "medium" if (breach_clause_present or deviation_detected) else "low")
        review_summary = structured_payload.get("review_summary", "")
        payment_terms_reason = structured_payload.get("payment_terms_reason", "已根据文档内容判断是否存在付款条款。")
        breach_clause_reason = structured_payload.get("breach_clause_reason", "已根据文档内容判断是否存在违约条款。")
        authorization_issue_reason = structured_payload.get("authorization_issue_reason", "已根据文档内容判断是否存在授权链异常。")
        deviation_reason = structured_payload.get("deviation_reason", "已根据文档内容判断是否存在技术或条款偏离。")
        risk_reasons = structured_payload.get("risk_reasons", [])
        return {
            "fields": {
                "document_length": len(document),
                "risk_level": risk_level,
                "payment_terms_present": payment_terms_present,
                "payment_terms_reason": payment_terms_reason,
                "breach_clause_present": breach_clause_present,
                "breach_clause_reason": breach_clause_reason,
                "authorization_issue": authorization_issue,
                "authorization_issue_reason": authorization_issue_reason,
                "deviation_detected": deviation_detected,
                "deviation_reason": deviation_reason,
                "risk_reasons": risk_reasons,
                "review_summary": review_summary,
                "extracted_fields": {
                    "payment_terms_present": payment_terms_present,
                    "payment_terms_reason": payment_terms_reason,
                    "breach_clause_present": breach_clause_present,
                    "breach_clause_reason": breach_clause_reason,
                    "authorization_issue": authorization_issue,
                    "authorization_issue_reason": authorization_issue_reason,
                    "deviation_detected": deviation_detected,
                    "deviation_reason": deviation_reason,
                    "risk_level": risk_level,
                    "risk_reasons": risk_reasons,
                    "review_summary": review_summary,
                },
            },
            "model_route": (model_response or {}).get("model_route", "general-llm-v1"),
            "model_structure_error": runtime_result.get("structure_error", ""),
        }
