import json
from http.server import BaseHTTPRequestHandler, HTTPServer

PORT = 8005


class Handler(BaseHTTPRequestHandler):
    def _json(self, status_code, payload):
        body = json.dumps(payload).encode("utf-8")
        self.send_response(status_code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, format, *args):
        return

    def do_GET(self):
        if self.path == "/health":
            self._json(200, {"status": "UP", "service": "agent-model-hub"})
            return
        if self.path == "/ops/models":
            self._json(200, {
                "status": "UP",
                "service": "agent-model-hub",
                "models": [
                    {"name": "general-llm-v1", "type": "llm", "status": "active"},
                    {"name": "reranker-v1", "type": "reranker", "status": "active"},
                    {"name": "ocr-v1", "type": "ocr", "status": "standby"}
                ],
                "evaluations": [
                    {"benchmark": "pricing-baseline", "winner": "general-llm-v1"},
                    {"benchmark": "qa-baseline", "winner": "general-llm-v1"}
                ],
                "cost_baseline": {"daily_budget": 500, "currency": "CNY"}
            })
            return
        self._json(404, {"status": "error", "message": "not found"})


if __name__ == "__main__":
    server = HTTPServer(("0.0.0.0", PORT), Handler)
    print(f"agent-model-hub listening on {PORT}")
    server.serve_forever()
