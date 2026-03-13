# agent-business-solution

L2 AI 服务层（Python），负责场景解决方案与智能体编排。

Entrypoints:
- scripts/build.sh
- scripts/test.sh
- scripts/run.sh
- scripts/healthcheck.sh

API:
- `GET /health`
- `GET /scenarios`
- `GET /scenarios/intelligent_qa`
- `GET /scenarios/contract_review`
- `GET /scenarios/compliance_review`
- `GET /scenarios/procurement_file_review`
- `GET /executions/{request_id}`
- `GET /ops/overview`
- `POST /invoke`

Current scope:
- 已注册场景：`intelligent_qa`、`contract_review`、`compliance_review`、`procurement_file_review`
- 已具备多场景注册与分发骨架
- 已具备独立场景模块：`scenarios/*/service.py`
- 已具备通用运行时：`scenario_runtime/runtime.py`
- 已具备场景级测试：`tests/test_scenarios.py`
- 已接通下游：`atomic-ai-engine`、`agent-model-runtime`
- `procurement_file_review` 已直接通过 L3 SDK 调用 `file_parse -> rule_engine -> evidence_chain_locate -> structured_extraction`
- `intelligent_qa`、`contract_review` 与 `compliance_review` 已切换为直接调用 L3 SDK 的场景路径
- 已具备执行记录：`data/executions.log`
- 已具备下游超时与重试策略：2 秒超时，最多 2 次尝试
- 已具备统一错误码：`INVALID_INPUT`、`UPSTREAM_L3_UNAVAILABLE`、`UPSTREAM_L4_UNAVAILABLE`
