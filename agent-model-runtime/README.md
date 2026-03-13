# agent-model-runtime

L4 模型调度与执行层（Java）。

当前定位：
- 作为 L3 `atomic-ai-engine` 的模型执行治理运行时
- 提供同步执行 MVP，并补齐异步任务提交、查询与取消

Entrypoints:
- `scripts/build.sh`
- `scripts/test.sh`
- `scripts/run.sh`
- `scripts/healthcheck.sh`

API:
- `GET /health`
- `POST /runtime/invoke`
- `POST /invoke`
- `POST /runtime/async-jobs`
- `GET /runtime/async-jobs/{job_id}`
- `POST /runtime/async-jobs/{job_id}/cancel`
- `GET /ops/runtime`
- `GET /ops/overview`
- `GET /ops/jobs`
- `GET /ops/routes`
- `GET /ops/circuits`

当前已具备的运行时能力：
- 优先消费 L6 `agent-model-hub` 的路由建议
- 当 L6 提供远程模型 endpoint 和认证环境变量时，直接按该元数据执行远程模型调用
- 基于 `task_type` 的模型路由
- 简单并发控制
- 有限重试
- 模型级熔断与备用路由
- 请求级作业记录
- 同步与异步任务视图
- 结果缓存复用
- 运营总览、最近作业与熔断状态查询

当前远程模型执行约定：
- L6 为推荐路由返回 `preferred_endpoint`、`preferred_auth_env`、`preferred_provider`
- L4 通过环境变量读取认证信息，不在仓库中保存密钥
- 当远程模型不可用或超时时，L4 回退到本地备用模型路由
