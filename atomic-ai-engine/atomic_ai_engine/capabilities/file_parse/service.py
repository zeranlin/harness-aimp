from atomic_ai_engine.base import BaseCapability


class FileParseCapability(BaseCapability):
    capability_code = "file_parse"
    depends_on_model_runtime = False

    def build_result(self, payload, model_response=None):
        text = str(payload.get("content") or payload.get("file_uri") or payload.get("text") or "")
        normalized = text.replace("。", "。\n")
        lines = [line.strip() for line in normalized.splitlines() if line.strip()]
        clauses = [line for line in lines if any(token in line for token in ["条款", "付款", "违约", "授权", "资格"])]
        return {
            "parsed_text": text,
            "document_type": "procurement_document" if text else "unknown",
            "page_count": max(1, len(text) // 500 + (1 if text else 0)) if text else 0,
            "section_count": max(1, len(lines)) if text else 0,
            "clause_count": len(clauses),
            "clauses": clauses[:5],
        }
