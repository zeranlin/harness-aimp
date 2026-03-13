from atomic_ai_engine.base import BaseCapability

class EvidenceChainLocateCapability(BaseCapability):
    capability_code = "evidence_chain_locate"
    depends_on_model_runtime = False

    def build_result(self, payload, model_response=None):
        text = str(payload.get("text") or payload.get("document") or "")
        evidence_points = []
        for token in ["付款", "违约", "授权", "资格", "偏离"]:
            idx = text.find(token)
            if idx >= 0:
                evidence_points.append({"keyword": token, "position": idx, "snippet": text[max(0, idx-8): idx+16]})
        if not evidence_points and text:
            evidence_points.append({"keyword": "summary", "position": 0, "snippet": text[:24]})
        return {"evidence_points": evidence_points[:5], "count": len(evidence_points)}
