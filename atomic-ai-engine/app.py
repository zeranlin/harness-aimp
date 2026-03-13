import json
from http.server import BaseHTTPRequestHandler, HTTPServer

from atomic_ai_engine import CapabilityEngine
from atomic_ai_engine.errors import CapabilityError

PORT = 8003
engine = CapabilityEngine.from_config()

class Handler(BaseHTTPRequestHandler):
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
            self._json(200, {"status": "UP", "service": "atomic-ai-engine", "mode": "sdk-adapter"})
            return
        if self.path == "/capabilities":
            self._json(200, {"items": engine.list_capabilities()})
            return
        self._json(404, {"status": "error", "message": "not found"})

    def do_POST(self):
        if self.path != "/invoke":
            self._json(404, {"status": "error", "message": "not found"})
            return
        length = int(self.headers.get("Content-Length", "0"))
        raw = self.rfile.read(length).decode("utf-8") if length > 0 else "{}"
        payload = json.loads(raw or "{}")
        capability_code = payload.get("capability_code") or payload.get("capability") or "structured_extraction"
        request_id = payload.get("request_id")
        capability_payload = payload.get("input") or payload
        try:
            response = engine.invoke(capability_code=capability_code, payload=capability_payload, request_id=request_id)
        except CapabilityError as exc:
            self._json(404, {"request_id": request_id, "status": "error", "errors": [{"code": exc.code, "message": exc.message}]})
            return
        if capability_code == "structured_extraction":
            result = response.get("result", {})
            compat = {
                "status": "ok",
                "request_id": response.get("request_id"),
                "service": "compliance",
                "provider": "atomic-ai-engine",
                "capability_code": capability_code,
                "evidence_count": 2 if result else 0,
                "risk_level": result.get("fields", {}).get("risk_level", "low"),
                "result": result,
            }
            self._json(200, compat)
            return
        self._json(200, response)

if __name__ == "__main__":
    server = HTTPServer(("0.0.0.0", PORT), Handler)
    print(f"atomic-ai-engine adapter listening on {PORT}")
    server.serve_forever()
