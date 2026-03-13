import json
import os
import sys
from http.server import BaseHTTPRequestHandler, HTTPServer

from scenario_runtime.common import EXECUTION_LOG, ROOT, UPSTREAM_MAX_ATTEMPTS, UPSTREAM_TIMEOUT_SECONDS, read_jsonl, summarize_executions
from scenario_runtime.runtime import ScenarioRegistry, ScenarioRuntime

PORT = 8002


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
            execution = next((item for item in reversed(read_jsonl(EXECUTION_LOG, limit=200)) if item.get("request_id") == request_id), None)
            if not execution:
                self._json(404, {"status": "error", "message": "execution not found"})
                return
            self._json(200, execution)
            return
        if self.path == "/ops/overview":
            executions = read_jsonl(EXECUTION_LOG, limit=200)
            self._json(200, {
                "status": "UP",
                "service": "agent-business-solution",
                "summary": summarize_executions(executions),
                "registered_scenarios": [item["scenario_code"] for item in self.registry.list()],
                "recent_executions": executions[-20:],
                "retry_policy": {
                    "upstream_timeout_seconds": UPSTREAM_TIMEOUT_SECONDS,
                    "max_attempts": UPSTREAM_MAX_ATTEMPTS,
                },
            })
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
    import importlib.util
    test_path = ROOT / "tests" / "test_scenarios.py"
    spec = importlib.util.spec_from_file_location("test_scenarios", test_path)
    module = importlib.util.module_from_spec(spec)
    assert spec.loader is not None
    spec.loader.exec_module(module)
    module.run()
    print("self-test ok")


if __name__ == "__main__":
    if len(sys.argv) > 1 and sys.argv[1] == "--self-test":
        self_test()
        sys.exit(0)

    os.makedirs(ROOT / "data", exist_ok=True)
    server = HTTPServer(("0.0.0.0", PORT), Handler)
    print(f"agent-business-solution listening on {PORT}")
    server.serve_forever()
