from atomic_ai_engine import CapabilityEngine

engine = CapabilityEngine.from_config()
assert engine.get_capability("structured_extraction")["capability_code"] == "structured_extraction"
response = engine.invoke("file_parse", {"content": "采购文件正文"}, request_id="sdk-001")
assert response["status"] == "success"
assert response["result"]["document_type"] == "procurement_document"
print("engine-tests ok")
