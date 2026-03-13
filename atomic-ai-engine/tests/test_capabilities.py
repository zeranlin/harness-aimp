from atomic_ai_engine import CapabilityEngine

engine = CapabilityEngine.from_config()
assert engine.invoke("rule_engine", {"text": "付款条款和违约责任"})["result"]["matched"] is True
assert engine.invoke("evidence_chain_locate", {"text": "供应商授权链不完整"})["result"]["count"] == 1
print("capability-tests ok")
