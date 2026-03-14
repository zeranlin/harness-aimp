from uuid import uuid4
import time

from scenario_runtime.common import get_capability_engine, record_execution


class ProcurementFileReviewService:
    scenario_code = "procurement_file_review"
    required_field = "input.file_content"

    def __init__(self, manifest):
        self.manifest = manifest
        self.engine = get_capability_engine()

    def execute(self, request_payload):
        started_at = time.time()
        request_id = request_payload.get("request_id") or f"req-{uuid4().hex[:12]}"
        file_content = request_payload.get("input", {}).get("file_content") or request_payload.get("document") or request_payload.get("prompt") or ""
        if not file_content:
            duration_ms = int((time.time() - started_at) * 1000)
            errors = [{"code": "INVALID_INPUT", "message": f"{self.required_field} is required"}]
            record_execution(request_id, self.scenario_code, "error", duration_ms, [], errors)
            return 400, {
                "request_id": request_id,
                "scenario_code": self.scenario_code,
                "status": "error",
                "result": {},
                "evidence": [],
                "metrics": {"duration_ms": duration_ms},
                "errors": errors,
            }

        dependency_calls = []
        try:
            parsed = self.engine.invoke("file_parse", {"content": file_content}, request_id=f"{request_id}-parse")
            dependency_calls.append({"service": "atomic-ai-engine", "capability_code": "file_parse", "status": "success"})

            parsed_text = parsed.get("result", {}).get("parsed_text", file_content)
            rule_result = self.engine.invoke("rule_engine", {"text": parsed_text}, request_id=f"{request_id}-rule")
            dependency_calls.append({"service": "atomic-ai-engine", "capability_code": "rule_engine", "status": "success"})

            evidence = self.engine.invoke("evidence_chain_locate", {"text": parsed_text}, request_id=f"{request_id}-evidence")
            dependency_calls.append({"service": "atomic-ai-engine", "capability_code": "evidence_chain_locate", "status": "success"})

            matched = rule_result.get("result", {}).get("matched", False)
            fallback_used = False
            extraction_result = None
            if not matched:
                fallback_used = True
                extraction_result = self.engine.invoke("structured_extraction", {"document": parsed_text}, request_id=f"{request_id}-extract")
                dependency_calls.append({"service": "atomic-ai-engine", "capability_code": "structured_extraction", "status": "success", "uses_l4": True})

            duration_ms = int((time.time() - started_at) * 1000)
            extraction_fields = (extraction_result or {}).get("result", {}).get("fields", {})
            extracted_fields = extraction_fields.get("extracted_fields", {})
            review_summary = extracted_fields.get("review_summary") or "已完成采购文件场景编排。先执行文件解析与规则引擎，未命中规则时再进入模型兜底。"
            result = {
                "review_summary": review_summary,
                "parsed_document_type": parsed.get("result", {}).get("document_type", "unknown"),
                "rule_matched": matched,
                "matched_rules": rule_result.get("result", {}).get("matched_rules", []),
                "fallback_used": fallback_used,
                "risk_level": extraction_fields.get("risk_level", "low" if matched else "unknown"),
                "model_route": (extraction_result or {}).get("result", {}).get("model_route"),
                "evidence_count": evidence.get("result", {}).get("count", 0),
                "payment_terms_present": extracted_fields.get("payment_terms_present"),
                "breach_clause_present": extracted_fields.get("breach_clause_present"),
                "authorization_issue": extracted_fields.get("authorization_issue"),
                "deviation_detected": extracted_fields.get("deviation_detected"),
                "structured_result": extracted_fields if extracted_fields else {},
                "model_structure_error": (extraction_result or {}).get("result", {}).get("model_structure_error", ""),
            }
            response = {
                "request_id": request_id,
                "scenario_code": self.scenario_code,
                "status": "success",
                "result": result,
                "evidence": [
                    {"type": "sdk_capability", "source": "atomic-ai-engine", "snippet": f"规则命中={matched}，证据点={result['evidence_count']}"}
                ],
                "metrics": {"duration_ms": duration_ms},
                "errors": [],
            }
            record_execution(request_id, self.scenario_code, "success", duration_ms, dependency_calls, [])
            return 200, response
        except CapabilityError as exc:
            duration_ms = int((time.time() - started_at) * 1000)
            errors = [{"code": exc.code, "message": exc.message}]
            record_execution(request_id, self.scenario_code, "error", duration_ms, dependency_calls, errors)
            return 502, {
                "request_id": request_id,
                "scenario_code": self.scenario_code,
                "status": "error",
                "result": {},
                "evidence": [],
                "metrics": {"duration_ms": duration_ms},
                "errors": errors,
            }
