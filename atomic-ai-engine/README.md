# atomic-ai-engine

L3 原子能力引擎层，当前以 Python SDK 形态交付。

## Current mode
- SDK first
- 内部按“一个能力一个单元”模块化
- 兼容提供一个轻量 HTTP 适配层，便于当前 L1/L2 迁移期间继续联调

## Entrypoints
- `python app.py` 启动兼容适配层（迁移期使用）
- `scripts/test.sh`
- `scripts/build.sh`

## SDK usage
```python
from atomic_ai_engine import CapabilityEngine
engine = CapabilityEngine.from_config()
result = engine.invoke(capability_code="file_parse", payload={"content": "采购文件内容"})
```

## First batch capabilities
- `file_parse`
- `rule_engine`
- `structured_extraction`
- `intent_understanding`
- `evidence_chain_locate`

## Full planned capabilities
- intent_understanding
- problem_decomposition
- multi_recall
- hybrid_retrieval
- fusion_rerank
- relevance_filter
- knowledge_aggregation
- context_management
- file_parse
- structured_extraction
- clause_extraction
- technical_spec_extraction
- rule_engine
- knowledge_graph_retrieval
- logic_tree_explanation
- evidence_chain_locate


## Ops endpoints (adapter mode)
- `GET /ops/overview`
- `GET /ops/capabilities`
- `GET /ops/capabilities/{capability_code}`

These endpoints expose capability-level calls, error codes, average latency, and recent invocation records.
