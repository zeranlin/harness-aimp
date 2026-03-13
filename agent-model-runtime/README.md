# agent-model-runtime

L4 模型调度与执行层（Java）。

当前定位：
- 作为 L3 `atomic-ai-engine` 的模型执行治理运行时
- 提供同步执行 MVP，并暴露运行态与作业视图

Entrypoints:
- `scripts/build.sh`
- `scripts/test.sh`
- `scripts/run.sh`
- `scripts/healthcheck.sh`

API:
- `GET /health`
- `POST /invoke`
- `POST /runtime/invoke`
- `GET /ops/runtime`
- `GET /ops/overview`
- `GET /ops/jobs`
- `GET /ops/routes`
- `GET /ops/circuits`

当前已具备的运行时能力：
- 基于 `task_type` 的模型路由
- 简单并发控制
- 有限重试
- 模型级熔断与备用路由
- 请求级作业记录
- 结果缓存复用
- 运营总览与最近作业查询
