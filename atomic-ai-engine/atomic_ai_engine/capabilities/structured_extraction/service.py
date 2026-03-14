from atomic_ai_engine.base import BaseCapability

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
            '  "breach_clause_present": true,\n'
            '  "authorization_issue": false,\n'
            '  "deviation_detected": false,\n'
            '  "risk_level": "low",\n'
            '  "review_summary": "中文摘要"\n'
            "}\n\n"
            "示例输出：\n"
            "{\n"
            '  "payment_terms_present": false,\n'
            '  "breach_clause_present": true,\n'
            '  "authorization_issue": false,\n'
            '  "deviation_detected": true,\n'
            '  "risk_level": "medium",\n'
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
        payment_terms_present = "付款" in document
        breach_clause_present = "违约" in document
        authorization_issue = "授权" in document and "不完整" in document
        deviation_detected = "偏离" in document
        risk_level = "high" if authorization_issue else "medium" if (breach_clause_present or deviation_detected) else "low"
        return {
            "fields": {
                "document_length": len(document),
                "risk_level": risk_level,
                "payment_terms_present": payment_terms_present,
                "breach_clause_present": breach_clause_present,
                "authorization_issue": authorization_issue,
                "deviation_detected": deviation_detected,
                "extracted_fields": {
                    "payment_terms_present": payment_terms_present,
                    "breach_clause_present": breach_clause_present,
                    "authorization_issue": authorization_issue,
                    "deviation_detected": deviation_detected,
                },
            },
            "model_route": (model_response or {}).get("model_route", "general-llm-v1"),
        }
