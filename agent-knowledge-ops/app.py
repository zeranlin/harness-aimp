import json
from http.server import BaseHTTPRequestHandler, HTTPServer

PORT = 8004


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
            self._json(200, {"status": "UP", "service": "agent-knowledge-ops"})
            return
        if self.path == "/ops/knowledge":
            self._json(200, {
                "status": "UP",
                "service": "agent-knowledge-ops",
                "knowledge_bases": [
                    {"name": "policy-docs", "status": "ready", "documents": 128},
                    {"name": "bid-cases", "status": "syncing", "documents": 42}
                ],
                "pipelines": [
                    {"name": "daily-ingest", "status": "healthy"},
                    {"name": "quality-check", "status": "healthy"}
                ],
                "quality": {"trusted_assets": 2, "issues": 0}
            })
            return
        self._json(404, {"status": "error", "message": "not found"})


if __name__ == "__main__":
    server = HTTPServer(("0.0.0.0", PORT), Handler)
    print(f"agent-knowledge-ops listening on {PORT}")
    server.serve_forever()
