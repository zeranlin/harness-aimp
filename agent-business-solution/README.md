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
- `GET /executions/{request_id}`
- `GET /ops/overview`
- `POST /invoke`

Current scope:
- 已注册场景：`intelligent_qa`、`contract_review`
- 已具备多场景注册与分发骨架
- 已接通下游：`atomic-ai-service`、`agent-model-runtime`
- 已具备执行记录：`data/executions.log`
- 已具备下游超时与重试策略：2 秒超时，最多 2 次尝试
- 已具备统一错误码：`INVALID_INPUT`、`UPSTREAM_L3_UNAVAILABLE`、`UPSTREAM_L4_UNAVAILABLE`
