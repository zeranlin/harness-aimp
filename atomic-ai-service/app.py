import json
from http.server import BaseHTTPRequestHandler, HTTPServer

PORT = 8003


def nested_get(payload, *keys):
    current = payload
    for key in keys:
        if not isinstance(current, dict):
            return ""
        current = current.get(key)
    return current or ""


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
        if self.path == "/capabilities":
            self._json(200, {
                "items": [
                    {
                        "capability_code": "structured_extraction",
                        "name": "Structured Extraction",
                        "status": "active",
                        "input": ["input.document"],
                        "outputs": ["evidence_count", "risk_level"]
                    }
                ]
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
        request_id = payload.get("request_id", "")
        capability_code = payload.get("capability_code") or payload.get("capability") or "structured_extraction"
        document = nested_get(payload, "input", "document") or payload.get("document", "")
        tenant_id = payload.get("tenant_id", "")
        operator_id = payload.get("operator_id", "")

        self._json(200, {
            "status": "ok",
            "request_id": request_id,
            "service": "compliance",
            "provider": "atomic-ai-service",
            "capability_code": capability_code,
            "evidence_count": 2 if document else 0,
            "risk_level": "medium" if document else "low",
            "tenant_id": tenant_id,
            "operator_id": operator_id,
        })


if __name__ == "__main__":
    server = HTTPServer(("0.0.0.0", PORT), Handler)
    print(f"atomic-ai-service listening on {PORT}")
    server.serve_forever()
