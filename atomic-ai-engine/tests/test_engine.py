from atomic_ai_engine import CapabilityEngine

engine = CapabilityEngine.from_config()
assert engine.get_capability("structured_extraction")["capability_code"] == "structured_extraction"
response = engine.invoke("file_parse", {"content": "采购文件正文包含付款条款与违约责任。"}, request_id="sdk-001")
assert response["status"] == "success"
assert response["result"]["document_type"] == "procurement_document"
assert response["result"]["clause_count"] >= 1
intent = engine.invoke("intent_understanding", {"question": "采购评审里废标条款怎么判断？"})
assert intent["result"]["intent"] == "procurement_review"
print("engine-tests ok")

overview = engine.ops_overview()
assert "summary" in overview
assert overview["summary"]["total_calls"] >= 2
