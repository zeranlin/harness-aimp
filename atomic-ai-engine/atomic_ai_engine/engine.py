from uuid import uuid4
from .errors import CapabilityError
from .l4_client import ModelRuntimeClient
from .registry import CapabilityRegistry

class CapabilityEngine:
    def __init__(self, registry=None, l4_client=None):
        self.registry = registry or CapabilityRegistry()
        self.l4_client = l4_client or ModelRuntimeClient()

    @classmethod
    def from_config(cls):
        return cls()

    def list_capabilities(self):
        return [manifest.__dict__ for manifest in self.registry.list_capabilities()]

    def get_capability(self, capability_code):
        manifest = self.registry.get_capability(capability_code)
        return manifest.__dict__ if manifest else None

    def invoke(self, capability_code, payload, request_id=None, context=None, options=None):
        request_id = request_id or f"cap-{uuid4().hex[:12]}"
        service = self.registry.load_service(capability_code, l4_client=self.l4_client)
        if not service:
            raise CapabilityError("CAPABILITY_NOT_FOUND", f"capability {capability_code} not found")
        return service.execute(request_id=request_id, payload=payload, context=context or {}, options=options or {})
