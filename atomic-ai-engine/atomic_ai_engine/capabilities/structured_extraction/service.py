from atomic_ai_engine.base import BaseCapability

class StructuredExtractionCapability(BaseCapability):
    capability_code = "structured_extraction"
    depends_on_model_runtime = True
    l4_task_type = "structured_extraction"

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
