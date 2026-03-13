# agent-model-hub

L6 模型层（Python）。

当前定位：
- 作为模型供给与治理层
- 向 L4 `agent-model-runtime` 提供模型池、版本、评测与成本基线

Entrypoints:
- `scripts/build.sh`
- `scripts/test.sh`
- `scripts/run.sh`
- `scripts/healthcheck.sh`

API:
- `GET /health`
- `GET /ops/models`
- `GET /ops/overview`
- `GET /models`
- `POST /models`
- `GET /models/{model_id}`
- `GET /models/{model_id}/versions`
- `POST /models/{model_id}/versions`
- `POST /models/{model_id}/activate`
- `GET /evaluations`
- `GET /evaluations/{model_id}`
- `GET /routes/recommendations`
- `GET /routes/recommendations/{task_type}`
- `GET /cost-baseline`

当前已具备的治理 MVP：
- 模型注册表
- 版本管理
- 评测报告
- 路由建议摘要
- 成本基线
- 运营总览
