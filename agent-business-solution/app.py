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
SCENARIO_FILE = ROOT / "scenarios" / "intelligent_qa" / "manifest.json"
EXECUTION_LOG = ROOT / "data" / "executions.log"


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
    except Exception as exc:  # pragma: no cover - surfaced via API contract
        return 502, {"status": "error", "message": str(exc)}


class ScenarioRegistry:
    def __init__(self):
        self.scenarios = {"intelligent_qa": load_json(SCENARIO_FILE)}

    def list(self):
        return list(self.scenarios.values())

    def get(self, scenario_code):
        return self.scenarios.get(scenario_code)


class IntelligentQaService:
    def __init__(self, manifest):
        self.manifest = manifest

    def execute(self, request_payload):
        started_at = time.time()
        request_id = request_payload.get("request_id") or f"req-{uuid4().hex[:12]}"
        question = (
            request_payload.get("input", {}).get("question")
            or request_payload.get("prompt")
            or ""
        )
        if not question:
            return 400, self.error_response(
                request_id,
                "INVALID_INPUT",
                "input.question is required",
                started_at,
            )

        capability_status, capability_payload = post_json(
            "http://127.0.0.1:8003/invoke",
            {"document": question, "request_id": request_id, "capability": "intent-retrieval"},
        )
        if capability_status >= 400:
            return 502, self.error_response(
                request_id,
                "UPSTREAM_L3_ERROR",
                capability_payload.get("message", "atomic-ai-service failed"),
                started_at,
            )

        model_status, model_payload = post_json(
            "http://127.0.0.1:8081/invoke",
            {
                "request_id": request_id,
                "scenario_code": "intelligent_qa",
                "question": question,
                "evidence_count": capability_payload.get("evidence_count", 0),
            },
        )
        if model_status >= 400:
            return 502, self.error_response(
                request_id,
                "UPSTREAM_L4_ERROR",
                model_payload.get("message", "agent-model-runtime failed"),
                started_at,
            )

        duration_ms = int((time.time() - started_at) * 1000)
        response = {
            "request_id": request_id,
            "scenario_code": "intelligent_qa",
            "status": "success",
            "result": {
                "answer": (
                    "已完成智能问答编排，先由原子能力完成证据预处理，"
                    "再由模型运行时生成结构化回答。"
                ),
                "summary": f"问题长度 {len(question)}，证据数 {capability_payload.get('evidence_count', 0)}。",
                "risk_level": capability_payload.get("risk_level", "unknown"),
                "model_route": model_payload.get("model_route", "unknown"),
            },
            "evidence": [
                {
                    "type": "capability_output",
                    "source": "atomic-ai-service",
                    "snippet": f"已识别 {capability_payload.get('evidence_count', 0)} 条候选证据。",
                }
            ],
            "metrics": {"duration_ms": duration_ms},
            "errors": [],
        }
        execution = {
            "request_id": request_id,
            "scenario_code": "intelligent_qa",
            "status": "success",
            "duration_ms": duration_ms,
            "dependency_calls": [
                {"service": "atomic-ai-service", "status_code": capability_status},
                {"service": "agent-model-runtime", "status_code": model_status},
            ],
        }
        append_jsonl(EXECUTION_LOG, execution)
        return 200, response

    def error_response(self, request_id, code, message, started_at):
        duration_ms = int((time.time() - started_at) * 1000)
        execution = {
            "request_id": request_id,
            "scenario_code": "intelligent_qa",
            "status": "error",
            "duration_ms": duration_ms,
            "dependency_calls": [],
            "errors": [{"code": code, "message": message}],
        }
        append_jsonl(EXECUTION_LOG, execution)
        return {
            "request_id": request_id,
            "scenario_code": "intelligent_qa",
            "status": "error",
            "result": {},
            "evidence": [],
            "metrics": {"duration_ms": duration_ms},
            "errors": [{"code": code, "message": message}],
        }


class Handler(BaseHTTPRequestHandler):
    registry = ScenarioRegistry()
    service = IntelligentQaService(registry.get("intelligent_qa"))

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
        self._json(404, {"status": "error", "message": "not found"})

    def do_POST(self):
        if self.path != "/invoke":
            self._json(404, {"status": "error", "message": "not found"})
            return
        length = int(self.headers.get("Content-Length", "0"))
        raw = self.rfile.read(length).decode("utf-8") if length > 0 else "{}"
        payload = json.loads(raw or "{}")
        scenario_code = payload.get("scenario_code") or "intelligent_qa"
        if scenario_code != "intelligent_qa":
            self._json(404, {"status": "error", "message": "scenario not found"})
            return
        status_code, response = self.service.execute(payload)
        self._json(status_code, response)


def self_test():
    registry = ScenarioRegistry()
    scenario = registry.get("intelligent_qa")
    assert scenario["scenario_code"] == "intelligent_qa"
    assert scenario["status"] == "active"
    response = IntelligentQaService(scenario).error_response(
        "req-selftest", "INVALID_INPUT", "input.question is required", time.time()
    )
    assert response["status"] == "error"
    assert response["errors"][0]["code"] == "INVALID_INPUT"
    print("self-test ok")


if __name__ == "__main__":
    if len(sys.argv) > 1 and sys.argv[1] == "--self-test":
        self_test()
        sys.exit(0)

    os.makedirs(ROOT / "data", exist_ok=True)
    server = HTTPServer(("0.0.0.0", PORT), Handler)
    print(f"agent-business-solution listening on {PORT}")
    server.serve_forever()
