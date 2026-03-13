from uuid import uuid4
import time

from scenario_runtime.common import SdkBackedScenarioService, record_execution
from atomic_ai_engine.errors import CapabilityError


class IntelligentQaService(SdkBackedScenarioService):
    scenario_code = "intelligent_qa"
    required_field = "input.question"
    l3_capability_code = "intent_understanding"

    def extract_input(self, request_payload):
        return request_payload.get("input", {}).get("question") or request_payload.get("prompt") or ""

    def execute(self, request_payload):
        started_at = time.time()
        request_id = request_payload.get("request_id") or f"req-{uuid4().hex[:12]}"
        question = self.extract_input(request_payload)
        if not question:
            return 400, self.error_response(request_id, "INVALID_INPUT", f"{self.required_field} is required", started_at)

        dependency_calls = []
        try:
            intent = self.engine.invoke("intent_understanding", {"question": question}, request_id=f"{request_id}-intent")
            dependency_calls.append({"service": "atomic-ai-engine", "capability_code": "intent_understanding", "status": "success", "sdk_mode": True})

            evidence = self.engine.invoke("evidence_chain_locate", {"text": question}, request_id=f"{request_id}-evidence")
            dependency_calls.append({"service": "atomic-ai-engine", "capability_code": "evidence_chain_locate", "status": "success", "sdk_mode": True})

            extraction = self.engine.invoke("structured_extraction", {"document": question}, request_id=f"{request_id}-extract")
            dependency_calls.append({"service": "atomic-ai-engine", "capability_code": "structured_extraction", "status": "success", "sdk_mode": True, "uses_l4": True})

            duration_ms = int((time.time() - started_at) * 1000)
            fields = extraction.get("result", {}).get("fields", {})
            result = {
                "answer": "已完成智能问答编排，当前通过 L3 SDK 先识别意图与证据，再通过结构化提取生成回答依据。",
                "summary": f"识别意图={intent.get('result', {}).get('intent', 'unknown')}，证据点={evidence.get('result', {}).get('count', 0)}。",
                "risk_level": fields.get("risk_level", "unknown"),
                "model_route": extraction.get("result", {}).get("model_route", "unknown"),
                "intent": intent.get("result", {}).get("intent", "unknown"),
                "evidence_count": evidence.get("result", {}).get("count", 0),
            }
            response = {
                "request_id": request_id,
                "scenario_code": self.scenario_code,
                "status": "success",
                "result": result,
                "evidence": evidence.get("result", {}).get("evidence_points", []),
                "metrics": {"duration_ms": duration_ms},
                "errors": [],
            }
            record_execution(request_id, self.scenario_code, "success", duration_ms, dependency_calls, [])
            return 200, response
        except CapabilityError as exc:
            return 502, self.error_response(request_id, exc.code, exc.message, started_at, dependency_calls)
