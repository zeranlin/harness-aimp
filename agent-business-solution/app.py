import json
import os
import sys
import time
import urllib.error
import urllib.request
from http.server import BaseHTTPRequestHandler, HTTPServer
from pathlib import Path
from uuid import uuid4

PORT = 8002
ROOT = Path(__file__).resolve().parent
CONFIG_FILE = ROOT / "config" / "scenarios.json"
SCENARIOS_DIR = ROOT / "scenarios"
EXECUTION_LOG = ROOT / "data" / "executions.log"
UPSTREAM_TIMEOUT_SECONDS = 2
UPSTREAM_MAX_ATTEMPTS = 2


def load_json(path):
    with open(path, "r", encoding="utf-8") as fh:
        return json.load(fh)


def append_jsonl(path, payload):
    path.parent.mkdir(parents=True, exist_ok=True)
    with open(path, "a", encoding="utf-8") as fh:
        fh.write(json.dumps(payload, ensure_ascii=False) + "\n")


def read_jsonl(path, limit=100):
    if not path.exists():
        return []
    with open(path, "r", encoding="utf-8") as fh:
        lines = [line.strip() for line in fh.readlines() if line.strip()]
    return [json.loads(line) for line in lines[-limit:]]


def post_json(url, payload, timeout=5):
    body = json.dumps(payload).encode("utf-8")
    request = urllib.request.Request(
        url,
        data=body,
        headers={"Content-Type": "application/json"},
        method="POST",
    )
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            return response.status, json.loads(response.read().decode("utf-8"))
    except urllib.error.HTTPError as exc:
        text = exc.read().decode("utf-8") or "{}"
        try:
            payload = json.loads(text)
        except json.JSONDecodeError:
            payload = {"status": "error", "message": text}
        return exc.code, payload
    except Exception as exc:
        return 502, {"status": "error", "message": str(exc)}


def call_dependency(service_name, url, payload, upstream_error_code):
    last_status = 502
    last_payload = {"status": "error", "message": f"{service_name} unavailable"}
    for attempt in range(1, UPSTREAM_MAX_ATTEMPTS + 1):
        status_code, response_payload = post_json(url, payload, timeout=UPSTREAM_TIMEOUT_SECONDS)
        if status_code < 500:
            return {
                "ok": status_code < 400,
                "status_code": status_code,
                "payload": response_payload,
                "attempts": attempt,
                "service": service_name,
                "error_code": None,
            }
        last_status = status_code
        last_payload = response_payload
    return {
        "ok": False,
        "status_code": last_status,
        "payload": last_payload,
        "attempts": UPSTREAM_MAX_ATTEMPTS,
        "service": service_name,
        "error_code": upstream_error_code,
    }


def summarize_executions(items):
    summary = {
        "total": len(items),
        "success": 0,
        "error": 0,
        "average_duration_ms": 0,
        "scenarios": {},
        "error_codes": {},
    }
    if not items:
        return summary

    total_duration = 0
    for item in items:
        total_duration += item.get("duration_ms", 0)
        status = item.get("status")
        if status == "success":
            summary["success"] += 1
        else:
            summary["error"] += 1
        scenario_code = item.get("scenario_code", "unknown")
        scenario_stats = summary["scenarios"].setdefault(
            scenario_code, {"total": 0, "success": 0, "error": 0}
        )
        scenario_stats["total"] += 1
        if status == "success":
            scenario_stats["success"] += 1
        else:
            scenario_stats["error"] += 1
        for error in item.get("errors", []):
            code = error.get("code", "UNKNOWN")
            summary["error_codes"][code] = summary["error_codes"].get(code, 0) + 1

    summary["average_duration_ms"] = int(total_duration / len(items))
    return summary


def record_execution(request_id, scenario_code, status, duration_ms, dependency_calls=None, errors=None):
    append_jsonl(
        EXECUTION_LOG,
        {
            "request_id": request_id,
            "scenario_code": scenario_code,
            "status": status,
            "duration_ms": duration_ms,
            "dependency_calls": dependency_calls or [],
            "errors": errors or [],
        },
    )


class ScenarioRegistry:
    def __init__(self, config_file=CONFIG_FILE, scenarios_dir=SCENARIOS_DIR):
        config = load_json(config_file)
        self.scenarios = {}
        for scenario_code in config.get("scenarios", []):
            manifest_path = scenarios_dir / scenario_code / "manifest.json"
            self.scenarios[scenario_code] = load_json(manifest_path)

    def list(self):
        return list(self.scenarios.values())

    def get(self, scenario_code):
        return self.scenarios.get(scenario_code)


class ScenarioService:
    scenario_code = ""
    required_field = ""
    l3_capability_code = "structured_extraction"
    l4_task_type = ""
    request_field = ""

    def __init__(self, manifest):
        self.manifest = manifest

    def extract_input(self, request_payload):
        return ""

    def build_success_result(self, source_text, capability_call, model_call):
        raise NotImplementedError

    def error_response(self, request_id, code, message, started_at, dependency_calls=None):
        duration_ms = int((time.time() - started_at) * 1000)
        errors = [{"code": code, "message": message}]
        record_execution(request_id, self.scenario_code, "error", duration_ms, dependency_calls, errors)
        return {
            "request_id": request_id,
            "scenario_code": self.scenario_code,
            "status": "error",
            "result": {},
            "evidence": [],
            "metrics": {"duration_ms": duration_ms},
            "errors": errors,
        }

    def execute(self, request_payload):
        started_at = time.time()
        request_id = request_payload.get("request_id") or f"req-{uuid4().hex[:12]}"
        source_text = self.extract_input(request_payload)
        if not source_text:
            return 400, self.error_response(
                request_id,
                "INVALID_INPUT",
                f"{self.required_field} is required",
                started_at,
            )

        capability_call = call_dependency(
            "atomic-ai-service",
            "http://127.0.0.1:8003/invoke",
            {
                "request_id": request_id,
                "tenant_id": request_payload.get("tenant_id", ""),
                "operator_id": request_payload.get("operator_id", ""),
                "capability_code": self.l3_capability_code,
                "input": {"document": source_text},
            },
            "UPSTREAM_L3_UNAVAILABLE",
        )
        if not capability_call["ok"]:
            return 502, self.error_response(
                request_id,
                capability_call["error_code"] or "UPSTREAM_L3_ERROR",
                capability_call["payload"].get("message", "atomic-ai-service failed"),
                started_at,
                [{
                    "service": capability_call["service"],
                    "status_code": capability_call["status_code"],
                    "attempts": capability_call["attempts"],
                }],
            )

        model_call = call_dependency(
            "agent-model-runtime",
            "http://127.0.0.1:8081/invoke",
            {
                "request_id": request_id,
                "scenario_code": self.scenario_code,
                "task_type": self.l4_task_type,
                self.request_field: source_text,
                "evidence_count": capability_call["payload"].get("evidence_count", 0),
            },
            "UPSTREAM_L4_UNAVAILABLE",
        )
        if not model_call["ok"]:
            return 502, self.error_response(
                request_id,
                model_call["error_code"] or "UPSTREAM_L4_ERROR",
                model_call["payload"].get("message", "agent-model-runtime failed"),
                started_at,
                [
                    {
                        "service": capability_call["service"],
                        "status_code": capability_call["status_code"],
                        "attempts": capability_call["attempts"],
                    },
                    {
                        "service": model_call["service"],
                        "status_code": model_call["status_code"],
                        "attempts": model_call["attempts"],
                    },
                ],
            )

        duration_ms = int((time.time() - started_at) * 1000)
        response = {
            "request_id": request_id,
            "scenario_code": self.scenario_code,
            "status": "success",
            "result": self.build_success_result(source_text, capability_call, model_call),
            "evidence": [
                {
                    "type": "capability_output",
                    "source": "atomic-ai-service",
                    "snippet": f"已识别 {capability_call['payload'].get('evidence_count', 0)} 条候选证据。",
                }
            ],
            "metrics": {"duration_ms": duration_ms},
            "errors": [],
        }
        record_execution(
            request_id,
            self.scenario_code,
            "success",
            duration_ms,
            [
                {
                    "service": capability_call["service"],
                    "status_code": capability_call["status_code"],
                    "attempts": capability_call["attempts"],
                },
                {
                    "service": model_call["service"],
                    "status_code": model_call["status_code"],
                    "attempts": model_call["attempts"],
                },
            ],
            [],
        )
        return 200, response


class IntelligentQaService(ScenarioService):
    scenario_code = "intelligent_qa"
    required_field = "input.question"
    l3_capability_code = "intent_retrieval"
    l4_task_type = "qa_generation"
    request_field = "question"

    def extract_input(self, request_payload):
        return request_payload.get("input", {}).get("question") or request_payload.get("prompt") or ""

    def build_success_result(self, source_text, capability_call, model_call):
        return {
            "answer": "已完成智能问答编排，先由原子能力完成证据预处理，再由模型运行时生成结构化回答。",
            "summary": f"问题长度 {len(source_text)}，证据数 {capability_call['payload'].get('evidence_count', 0)}。",
            "risk_level": capability_call["payload"].get("risk_level", "unknown"),
            "model_route": model_call["payload"].get("model_route", "unknown"),
        }


class ContractReviewService(ScenarioService):
    scenario_code = "contract_review"
    required_field = "input.contract_text"
    l3_capability_code = "structured_extraction"
    l4_task_type = "contract_review"
    request_field = "payload"

    def extract_input(self, request_payload):
        return request_payload.get("input", {}).get("contract_text") or request_payload.get("document") or request_payload.get("prompt") or ""

    def build_success_result(self, source_text, capability_call, model_call):
        evidence_count = capability_call["payload"].get("evidence_count", 0)
        risk_level = capability_call["payload"].get("risk_level", "unknown")
        return {
            "review_summary": "已完成合同审查编排，已对合同文本完成结构化提取并输出审查摘要。",
            "review_points": [
                "已完成基础条款提取",
                f"识别到 {evidence_count} 条候选证据",
                f"风险等级为 {risk_level}",
            ],
            "risk_level": risk_level,
            "model_route": model_call["payload"].get("model_route", "unknown"),
            "document_length": len(source_text),
        }


class ScenarioRuntime:
    def __init__(self, registry):
        self.registry = registry
        self.services = {
            "intelligent_qa": IntelligentQaService(registry.get("intelligent_qa")),
            "contract_review": ContractReviewService(registry.get("contract_review")),
        }

    def execute(self, payload):
        scenario_code = payload.get("scenario_code") or "intelligent_qa"
        service = self.services.get(scenario_code)
        if not service:
            return 404, {"status": "error", "message": "scenario not found"}
        return service.execute(payload)


class Handler(BaseHTTPRequestHandler):
    registry = ScenarioRegistry()
    runtime = ScenarioRuntime(registry)

    def _json(self, status_code, payload):
        body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        self.send_response(status_code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, format, *args):
        return

    def do_GET(self):
        if self.path == "/health":
            self._json(200, {"status": "UP", "service": "agent-business-solution"})
            return
        if self.path == "/scenarios":
            self._json(200, {"items": self.registry.list()})
            return
        if self.path.startswith("/scenarios/"):
            scenario_code = self.path.split("/")[-1]
            scenario = self.registry.get(scenario_code)
            if not scenario:
                self._json(404, {"status": "error", "message": "scenario not found"})
                return
            self._json(200, scenario)
            return
        if self.path.startswith("/executions/"):
            request_id = self.path.split("/")[-1]
            execution = next(
                (item for item in reversed(read_jsonl(EXECUTION_LOG, limit=200)) if item.get("request_id") == request_id),
                None,
            )
            if not execution:
                self._json(404, {"status": "error", "message": "execution not found"})
                return
            self._json(200, execution)
            return
        if self.path == "/ops/overview":
            executions = read_jsonl(EXECUTION_LOG, limit=200)
            self._json(
                200,
                {
                    "status": "UP",
                    "service": "agent-business-solution",
                    "summary": summarize_executions(executions),
                    "registered_scenarios": [item["scenario_code"] for item in self.registry.list()],
                    "recent_executions": executions[-20:],
                    "retry_policy": {
                        "upstream_timeout_seconds": UPSTREAM_TIMEOUT_SECONDS,
                        "max_attempts": UPSTREAM_MAX_ATTEMPTS,
                    },
                },
            )
            return
        self._json(404, {"status": "error", "message": "not found"})

    def do_POST(self):
        if self.path != "/invoke":
            self._json(404, {"status": "error", "message": "not found"})
            return
        length = int(self.headers.get("Content-Length", "0"))
        raw = self.rfile.read(length).decode("utf-8") if length > 0 else "{}"
        payload = json.loads(raw or "{}")
        status_code, response = self.runtime.execute(payload)
        self._json(status_code, response)


def self_test():
    registry = ScenarioRegistry()
    assert registry.get("intelligent_qa")["scenario_code"] == "intelligent_qa"
    assert registry.get("contract_review")["scenario_code"] == "contract_review"
    runtime = ScenarioRuntime(registry)
    status_code, response = runtime.execute({"scenario_code": "missing"})
    assert status_code == 404
    assert response["message"] == "scenario not found"
    error_response = IntelligentQaService(registry.get("intelligent_qa")).error_response(
        "req-selftest", "INVALID_INPUT", "input.question is required", time.time()
    )
    assert error_response["status"] == "error"
    assert error_response["errors"][0]["code"] == "INVALID_INPUT"
    summary = summarize_executions(
        [
            {"scenario_code": "intelligent_qa", "status": "success", "duration_ms": 10, "errors": []},
            {"scenario_code": "contract_review", "status": "success", "duration_ms": 20, "errors": []},
            {
                "scenario_code": "contract_review",
                "status": "error",
                "duration_ms": 30,
                "errors": [{"code": "UPSTREAM_L3_UNAVAILABLE", "message": "timeout"}],
            },
        ]
    )
    assert summary["total"] == 3
    assert summary["scenarios"]["contract_review"]["total"] == 2
    assert summary["error_codes"]["UPSTREAM_L3_UNAVAILABLE"] == 1
    print("self-test ok")


if __name__ == "__main__":
    if len(sys.argv) > 1 and sys.argv[1] == "--self-test":
        self_test()
        sys.exit(0)

    os.makedirs(ROOT / "data", exist_ok=True)
    server = HTTPServer(("0.0.0.0", PORT), Handler)
    print(f"agent-business-solution listening on {PORT}")
    server.serve_forever()
