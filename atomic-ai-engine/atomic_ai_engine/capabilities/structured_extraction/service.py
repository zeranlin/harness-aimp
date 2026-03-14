from atomic_ai_engine.base import BaseCapability

class StructuredExtractionCapability(BaseCapability):
    capability_code = "structured_extraction"
    depends_on_model_runtime = True
    l4_task_type = "structured_extraction"

    def build_model_payload(self, request_id, payload, context=None, options=None):
        document = str(payload.get("document") or payload.get("text") or "")
        prompt = (
            "你是采购与合规审查助手。请基于下面的文档内容，输出结构化审查结论。\n"
            "请重点判断并总结：\n"
            "1. 是否存在付款条款\n"
            "2. 是否存在违约条款\n"
            "3. 是否存在授权链异常\n"
            "4. 是否存在技术或条款偏离\n"
            "5. 综合风险等级（low/medium/high）\n"
            "6. 给出一段简短的中文审查摘要\n\n"
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
