import json
from http.server import BaseHTTPRequestHandler, HTTPServer

PORT = 8003


class Handler(BaseHTTPRequestHandler):
    def _json(self, status_code, payload):
        body = json.dumps(payload).encode("utf-8")
        self.send_response(status_code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        if self.path == "/health":
            self._json(200, {"status": "UP", "service": "atomic-ai-service"})
            return
        self._json(404, {"status": "error", "message": "not found"})

    def do_POST(self):
        if self.path != "/invoke":
            self._json(404, {"status": "error", "message": "not found"})
            return

        length = int(self.headers.get("Content-Length", "0"))
        raw = self.rfile.read(length).decode("utf-8") if length > 0 else "{}"
        payload = json.loads(raw or "{}")
        document = payload.get("document", "")

        self._json(200, {
            "status": "ok",
            "service": "compliance",
            "provider": "atomic-ai-service",
            "capability": "structured-extraction",
            "evidence_count": 2 if document else 0,
            "risk_level": "medium" if document else "low",
        })


if __name__ == "__main__":
    server = HTTPServer(("0.0.0.0", PORT), Handler)
    print(f"atomic-ai-service listening on {PORT}")
    server.serve_forever()
