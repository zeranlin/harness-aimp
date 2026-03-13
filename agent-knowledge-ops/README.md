# agent-knowledge-ops

L5 知识运营层（Python）。

当前定位：
- 作为知识资产治理与运营层
- 向 L2 `agent-business-solution` 和 L3 `atomic-ai-engine` 提供治理后的知识资产摘要

Entrypoints:
- `scripts/build.sh`
- `scripts/test.sh`
- `scripts/run.sh`
- `scripts/healthcheck.sh`

API:
- `GET /health`
- `GET /ops/knowledge`
- `GET /ops/overview`
- `GET /knowledge-bases`
- `POST /knowledge-bases`
- `GET /knowledge-bases/{knowledge_base_id}`
- `POST /knowledge-bases/{knowledge_base_id}/publish`
- `GET /pipelines/runs`
- `GET /pipelines/runs/{pipeline_run_id}`
- `POST /pipelines/ingest`
- `POST /pipelines/quality-check`
- `GET /quality/reports`
- `GET /quality/reports/{knowledge_base_id}`
- `GET /audit/records`
- `GET /lineage/{knowledge_base_id}`

当前已具备的治理 MVP：
- 知识库注册表
- 导入流水线运行记录
- 质量报告
- 审计记录与血缘摘要
- 运营总览
