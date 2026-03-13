from atomic_ai_engine.base import BaseCapability

class FileParseCapability(BaseCapability):
    capability_code = "file_parse"
    depends_on_model_runtime = False

    def build_result(self, payload, model_response=None):
        text = payload.get("content") or payload.get("file_uri") or payload.get("text") or ""
        return {"parsed_text": str(text), "document_type": "procurement_document", "page_count": 1 if text else 0}
