from atomic_ai_engine.base import BaseCapability

class EvidenceChainLocateCapability(BaseCapability):
    capability_code = "evidence_chain_locate"
    depends_on_model_runtime = False

    def build_result(self, payload, model_response=None):
        text = payload.get("text") or payload.get("document") or ""
        return {"evidence_points": [{"snippet": str(text)[:32], "position": 1}] if text else [], "count": 1 if text else 0}
