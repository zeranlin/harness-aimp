from dataclasses import dataclass
from typing import Any, Dict

@dataclass
class CapabilityManifest:
    capability_code: str
    name: str
    version: str
    status: str
    entrypoint: str
    input_schema: Dict[str, Any]
    output_schema: Dict[str, Any]
    depends_on_model_runtime: bool = False
