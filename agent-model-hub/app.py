import json
import sys
from datetime import datetime, timezone
from http.server import BaseHTTPRequestHandler, HTTPServer
from urllib.parse import urlparse

PORT = 8005


def now_iso():
    return datetime.now(timezone.utc).replace(microsecond=0).isoformat().replace('+00:00', 'Z')


class ModelHubStore:
    def __init__(self):
        self.models = {
            "model-general-llm": {
                "model_id": "model-general-llm",
                "name": "general-llm",
                "type": "llm",
                "provider": "open-model-provider",
                "status": "active",
                "current_version": "v1",
                "recommended_version": "v1",
                "capabilities": ["qa", "structured_extraction", "intent_understanding"],
            },
            "model-reranker": {
                "model_id": "model-reranker",
                "name": "reranker",
                "type": "reranker",
                "provider": "rank-provider",
                "status": "active",
                "current_version": "v1",
                "recommended_version": "v1",
                "capabilities": ["fusion_rerank", "relevance_filter"],
            },
            "model-qwen35-27b": {
                "model_id": "model-qwen35-27b",
                "name": "qwen3.5-27b",
                "type": "llm",
                "provider": "openai-compatible",
                "status": "active",
                "current_version": "qwen3.5-27b",
                "recommended_version": "qwen3.5-27b",
                "capabilities": ["pricing_inference", "structured_extraction", "document_extraction", "intent_understanding"],
                "endpoint": "http://112.111.54.86:10011/v1",
                "auth_env": "AGENT_MODEL_HUB_QWEN35_27B_API_KEY",
            },
            "model-ocr": {
                "model_id": "model-ocr",
                "name": "ocr",
                "type": "ocr",
                "provider": "vision-provider",
                "status": "standby",
                "current_version": "v1",
                "recommended_version": "v1",
                "capabilities": ["file_parse", "ocr_extract"],
            },
        }
        self.versions = {
            "model-general-llm": [
                {
                    "model_id": "model-general-llm",
                    "version": "v1",
                    "status": "active",
                    "released_at": now_iso(),
                    "compatibility": ["pricing_inference", "structured_extraction", "intent_understanding"],
                },
                {
                    "model_id": "model-general-llm",
                    "version": "v2-beta",
                    "status": "standby",
                    "released_at": now_iso(),
                    "compatibility": ["pricing_inference", "document_extraction"],
                },
            ],
            "model-reranker": [
                {
                    "model_id": "model-reranker",
                    "version": "v1",
                    "status": "active",
                    "released_at": now_iso(),
                    "compatibility": ["fusion_rerank", "relevance_filter"],
                }
            ],
            "model-qwen35-27b": [
                {
                    "model_id": "model-qwen35-27b",
                    "version": "qwen3.5-27b",
                    "status": "active",
                    "released_at": now_iso(),
                    "compatibility": ["pricing_inference", "structured_extraction", "document_extraction", "intent_understanding"],
                }
            ],
            "model-ocr": [
                {
                    "model_id": "model-ocr",
                    "version": "v1",
                    "status": "standby",
                    "released_at": now_iso(),
                    "compatibility": ["file_parse"],
                }
            ],
        }
        self.evaluations = [
            {
                "model_id": "model-general-llm",
                "version": "v1",
                "benchmark": "pricing-baseline",
                "score": 91,
                "latency_ms": 620,
                "cost_per_1k_tokens": 0.18,
                "winner": "general-llm-v1",
            },
            {
                "model_id": "model-general-llm",
                "version": "v1",
                "benchmark": "qa-baseline",
                "score": 94,
                "latency_ms": 540,
                "cost_per_1k_tokens": 0.18,
                "winner": "general-llm-v1",
            },
            {
                "model_id": "model-reranker",
                "version": "v1",
                "benchmark": "rerank-baseline",
                "score": 88,
                "latency_ms": 120,
                "cost_per_1k_tokens": 0.05,
                "winner": "reranker-v1",
            },
            {
                "model_id": "model-qwen35-27b",
                "version": "qwen3.5-27b",
                "benchmark": "document-extraction-baseline",
                "score": 95,
                "latency_ms": 780,
                "cost_per_1k_tokens": 0.22,
                "winner": "qwen3.5-27b",
            },
        ]
        self.route_recommendations = {
            "pricing_inference": {
                "task_type": "pricing_inference",
                "preferred_model": "qwen3.5-27b:qwen3.5-27b",
                "fallback_model": "general-llm:v1",
                "policy_mode": "quality-first",
            },
            "structured_extraction": {
                "task_type": "structured_extraction",
                "preferred_model": "qwen3.5-27b:qwen3.5-27b",
                "fallback_model": "general-llm:v1",
                "policy_mode": "quality-first",
            },
            "document_extraction": {
                "task_type": "document_extraction",
                "preferred_model": "qwen3.5-27b:qwen3.5-27b",
                "fallback_model": "ocr:v1",
                "policy_mode": "quality-first",
            },
            "intent_understanding": {
                "task_type": "intent_understanding",
                "preferred_model": "general-llm:v1",
                "fallback_model": "qwen3.5-27b:qwen3.5-27b",
                "policy_mode": "latency-first",
            },
        }
        self.cost_baseline = {
            "daily_budget": 500,
            "currency": "CNY",
            "models": [
                {"model_id": "model-general-llm", "version": "v1", "input_cost": 0.12, "output_cost": 0.18},
                {"model_id": "model-reranker", "version": "v1", "input_cost": 0.02, "output_cost": 0.05},
                {"model_id": "model-qwen35-27b", "version": "qwen3.5-27b", "input_cost": 0.16, "output_cost": 0.22},
                {"model_id": "model-ocr", "version": "v1", "input_cost": 0.08, "output_cost": 0.10},
            ],
        }
        self.next_model = 4

    def list_models(self):
        return list(self.models.values())

    def get_model(self, model_id):
        return self.models.get(model_id)

    def create_model(self, payload):
        model_id = payload.get("model_id") or f"model-generated-{self.next_model}"
        self.next_model += 1
        item = {
            "model_id": model_id,
            "name": payload.get("name") or model_id,
            "type": payload.get("type") or "llm",
            "provider": payload.get("provider") or "custom-provider",
            "status": payload.get("status") or "standby",
            "current_version": payload.get("current_version") or "v1",
            "recommended_version": payload.get("recommended_version") or payload.get("current_version") or "v1",
            "capabilities": payload.get("capabilities") or [],
        }
        self.models[model_id] = item
        self.versions[model_id] = [
            {
                "model_id": model_id,
                "version": item["current_version"],
                "status": "active" if item["status"] == "active" else "standby",
                "released_at": now_iso(),
                "compatibility": payload.get("compatibility") or [],
            }
        ]
        return item

    def list_versions(self, model_id):
        return self.versions.get(model_id)

    def add_version(self, model_id, payload):
        item = self.models.get(model_id)
        if not item:
            return None
        version_item = {
            "model_id": model_id,
            "version": payload.get("version") or f"v{len(self.versions.get(model_id, [])) + 1}",
            "status": payload.get("status") or "standby",
            "released_at": now_iso(),
            "compatibility": payload.get("compatibility") or [],
        }
        self.versions.setdefault(model_id, []).append(version_item)
        return version_item

    def activate_version(self, model_id, version):
        item = self.models.get(model_id)
        versions = self.versions.get(model_id)
        if not item or not versions:
            return None
        activated = None
        for version_item in versions:
            if version_item["version"] == version:
                version_item["status"] = "active"
                activated = version_item
            elif version_item["status"] == "active":
                version_item["status"] = "standby"
        if not activated:
            return None
        item["current_version"] = version
        item["recommended_version"] = version
        item["status"] = "active"
        return activated

    def list_evaluations(self):
        return self.evaluations

    def get_evaluations(self, model_id):
        return [item for item in self.evaluations if item["model_id"] == model_id]

    def get_route_recommendations(self):
        return list(self.route_recommendations.values())

    def get_route_recommendation(self, task_type):
        return self.route_recommendations.get(task_type)

    def get_cost_baseline(self):
        return self.cost_baseline

    def ops_overview(self):
        models = self.list_models()
        evaluations = self.list_evaluations()
        return {
            "status": "UP",
            "service": "agent-model-hub",
            "models": [
                {
                    "model_id": item["model_id"],
                    "name": f"{item['name']}-{item['current_version']}",
                    "type": item["type"],
                    "status": item["status"],
                    "provider": item["provider"],
                    "current_version": item["current_version"],
                    "recommended_version": item["recommended_version"],
                    "capabilities": item["capabilities"],
                }
                for item in models
            ],
            "evaluations": [
                {
                    "benchmark": item["benchmark"],
                    "winner": item["winner"],
                    "score": item["score"],
                    "latency_ms": item["latency_ms"],
                    "cost_per_1k_tokens": item["cost_per_1k_tokens"],
                }
                for item in evaluations
            ],
            "route_recommendations": self.get_route_recommendations(),
            "cost_baseline": self.get_cost_baseline(),
            "summary": {
                "model_count": len(models),
                "active_models": len([item for item in models if item["status"] == "active"]),
                "evaluation_count": len(evaluations),
                "route_count": len(self.route_recommendations),
                "remote_endpoints": len([item for item in models if item.get("endpoint")]),
            },
        }


STORE = ModelHubStore()


class Handler(BaseHTTPRequestHandler):
    def _json(self, status_code, payload):
        body = json.dumps(payload).encode("utf-8")
        self.send_response(status_code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def _read_json(self):
        length = int(self.headers.get("Content-Length") or 0)
        if length <= 0:
            return {}
        body = self.rfile.read(length).decode("utf-8")
        return json.loads(body) if body else {}

    def log_message(self, format, *args):
        return

    def do_GET(self):
        parsed = urlparse(self.path)
        path = parsed.path
        if path == "/health":
            self._json(200, {"status": "UP", "service": "agent-model-hub"})
            return
        if path in ("/ops/models", "/ops/overview"):
            self._json(200, STORE.ops_overview())
            return
        if path == "/models":
            self._json(200, {"status": "ok", "items": STORE.list_models()})
            return
        if path.endswith("/versions"):
            model_id = path.split("/")[-2]
            items = STORE.list_versions(model_id)
            if items is None:
                self._json(404, {"status": "error", "message": "model not found"})
                return
            self._json(200, {"status": "ok", "items": items})
            return
        if path.startswith("/models/"):
            model_id = path.split("/")[-1]
            item = STORE.get_model(model_id)
            if not item:
                self._json(404, {"status": "error", "message": "model not found"})
                return
            self._json(200, {"status": "ok", "item": item})
            return
        if path == "/evaluations":
            self._json(200, {"status": "ok", "items": STORE.list_evaluations()})
            return
        if path.startswith("/evaluations/"):
            model_id = path.split("/")[-1]
            self._json(200, {"status": "ok", "items": STORE.get_evaluations(model_id)})
            return
        if path == "/routes/recommendations":
            self._json(200, {"status": "ok", "items": STORE.get_route_recommendations()})
            return
        if path.startswith("/routes/recommendations/"):
            task_type = path.split("/")[-1]
            item = STORE.get_route_recommendation(task_type)
            if not item:
                self._json(404, {"status": "error", "message": "route recommendation not found"})
                return
            self._json(200, {"status": "ok", "item": item})
            return
        if path == "/cost-baseline":
            self._json(200, {"status": "ok", "item": STORE.get_cost_baseline()})
            return
        self._json(404, {"status": "error", "message": "not found"})

    def do_POST(self):
        parsed = urlparse(self.path)
        path = parsed.path
        payload = self._read_json()
        if path.endswith("/versions"):
            model_id = path.split("/")[-2]
            item = STORE.add_version(model_id, payload)
            if not item:
                self._json(404, {"status": "error", "message": "model not found"})
                return
            self._json(201, {"status": "ok", "item": item})
            return
        if path.endswith("/activate"):
            model_id = path.split("/")[-2]
            version = payload.get("version")
            item = STORE.activate_version(model_id, version)
            if not item:
                self._json(404, {"status": "error", "message": "model or version not found"})
                return
            self._json(200, {"status": "ok", "item": item})
            return
        if path == "/models":
            item = STORE.create_model(payload)
            self._json(201, {"status": "ok", "item": item})
            return
        self._json(404, {"status": "error", "message": "not found"})


def self_test():
    created = STORE.create_model({
        "model_id": "model-self-test",
        "name": "self-test-llm",
        "type": "llm",
        "provider": "test-provider",
        "status": "standby",
        "current_version": "v1",
        "capabilities": ["pricing_inference"],
    })
    assert created["model_id"] == "model-self-test"
    version = STORE.add_version("model-self-test", {"version": "v2", "compatibility": ["pricing_inference"]})
    assert version["version"] == "v2"
    activated = STORE.activate_version("model-self-test", "v2")
    assert activated["status"] == "active"
    overview = STORE.ops_overview()
    assert overview["summary"]["model_count"] >= 4
    assert overview["summary"]["evaluation_count"] >= 3


if __name__ == "__main__":
    if len(sys.argv) > 1 and sys.argv[1] == "--self-test":
        self_test()
        print("self-test ok")
        raise SystemExit(0)
    server = HTTPServer(("0.0.0.0", PORT), Handler)
    print(f"agent-model-hub listening on {PORT}")
    server.serve_forever()
