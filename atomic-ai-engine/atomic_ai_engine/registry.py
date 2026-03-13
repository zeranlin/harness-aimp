import importlib
import json
from pathlib import Path
from .models import CapabilityManifest

class CapabilityRegistry:
    def __init__(self, capabilities_dir=None):
        self.capabilities_dir = capabilities_dir or (Path(__file__).resolve().parent / "capabilities")
        self.manifests = {}
        self._discover()

    def _discover(self):
        for manifest_path in sorted(self.capabilities_dir.glob("*/manifest.json")):
            data = json.loads(manifest_path.read_text())
            self.manifests[data["capability_code"]] = CapabilityManifest(**data)

    def list_capabilities(self):
        return list(self.manifests.values())

    def get_capability(self, capability_code):
        return self.manifests.get(capability_code)

    def load_service(self, capability_code, l4_client=None):
        manifest = self.get_capability(capability_code)
        if not manifest:
            return None
        module_path, class_name = manifest.entrypoint.rsplit('.', 1)
        module = importlib.import_module(module_path)
        cls = getattr(module, class_name)
        return cls(manifest, l4_client=l4_client)
