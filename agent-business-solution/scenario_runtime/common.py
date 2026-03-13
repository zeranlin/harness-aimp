import json
import time
import urllib.error
import urllib.request
from pathlib import Path
from uuid import uuid4

ROOT = Path(__file__).resolve().parent.parent
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
        scenario_stats = summary["scenarios"].setdefault(scenario_code, {"total": 0, "success": 0, "error": 0})
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
                {"service": capability_call["service"], "status_code": capability_call["status_code"], "attempts": capability_call["attempts"]},
                {"service": model_call["service"], "status_code": model_call["status_code"], "attempts": model_call["attempts"]},
            ],
            [],
        )
        return 200, response
