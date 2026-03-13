from atomic_ai_engine.registry import CapabilityRegistry

registry = CapabilityRegistry()
items = registry.list_capabilities()
assert len(items) >= 16
assert registry.get_capability("file_parse") is not None
print("registry-tests ok")
