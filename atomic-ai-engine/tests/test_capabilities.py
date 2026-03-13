from atomic_ai_engine import CapabilityEngine

engine = CapabilityEngine.from_config()
rule = engine.invoke("rule_engine", {"text": "付款条款和违约责任"})
assert rule["result"]["matched"] is True
assert "payment_clause" in rule["result"]["matched_rules"]
evidence = engine.invoke("evidence_chain_locate", {"text": "供应商授权链不完整"})
assert evidence["result"]["count"] >= 1
extract = engine.invoke("structured_extraction", {"document": "采购合同包含付款节点、违约责任和授权链不完整问题。"})
assert extract["result"]["fields"]["authorization_issue"] is True
assert extract["result"]["fields"]["risk_level"] == "high"
print("capability-tests ok")

detail = engine.ops_capability_detail("structured_extraction")
assert detail["manifest"]["capability_code"] == "structured_extraction"
