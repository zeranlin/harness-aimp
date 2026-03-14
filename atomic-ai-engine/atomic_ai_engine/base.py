import time

class BaseCapability:
    capability_code = ""
    depends_on_model_runtime = False
    l4_task_type = None

    def __init__(self, manifest, l4_client=None):
        self.manifest = manifest
        self.l4_client = l4_client

    def execute(self, request_id, payload, context=None, options=None):
        started = time.time()
        model_payload = None
        model_response = None
        if self.depends_on_model_runtime and self.l4_client and self.l4_task_type:
            model_payload = self.build_model_payload(request_id=request_id, payload=payload, context=context or {}, options=options or {})
            _, model_response = self.l4_client.invoke(self.l4_task_type, model_payload)
        result = self.build_result(payload, model_response)
        return {
            "request_id": request_id,
            "capability_code": self.capability_code,
            "status": "success",
            "result": result,
            "evidence": self.build_evidence(payload, result),
            "errors": [],
            "metrics": {"duration_ms": int((time.time() - started) * 1000)},
        }

    def build_result(self, payload, model_response=None):
        return {"echo": payload, "model_response": model_response or {}}

    def build_evidence(self, payload, result):
        return []

    def build_model_payload(self, request_id, payload, context=None, options=None):
        return {"request_id": request_id, "task_type": self.l4_task_type, "input": payload}
