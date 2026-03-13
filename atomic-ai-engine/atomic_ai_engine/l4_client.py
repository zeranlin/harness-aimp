import json
import urllib.request
import urllib.error

class ModelRuntimeClient:
    def __init__(self, base_url="http://127.0.0.1:8081"):
        self.base_url = base_url.rstrip("/")

    def invoke(self, task_type, payload):
        body = json.dumps(payload).encode("utf-8")
        req = urllib.request.Request(
            f"{self.base_url}/runtime/invoke",
            data=body,
            headers={"Content-Type": "application/json"},
            method="POST",
        )
        try:
            with urllib.request.urlopen(req, timeout=3) as resp:
                return resp.status, json.loads(resp.read().decode("utf-8"))
        except urllib.error.HTTPError as exc:
            text = exc.read().decode("utf-8") or "{}"
            try:
                payload = json.loads(text)
            except json.JSONDecodeError:
                payload = {"status": "error", "message": text}
            return exc.code, payload
        except Exception as exc:
            return 502, {"status": "error", "message": str(exc), "task_type": task_type}
