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
- `GET /executions/{request_id}`
- `POST /invoke`

MVP:
- 已注册场景：`intelligent_qa`
- 已接通下游：`atomic-ai-service`、`agent-model-runtime`
- 已具备执行记录：`data/executions.log`
