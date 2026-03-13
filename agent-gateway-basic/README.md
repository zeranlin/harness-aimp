# agent-gateway-basic

L1 服务运营网关层（Java）。

Entrypoints:
- scripts/build.sh
- scripts/test.sh
- scripts/run.sh
- scripts/healthcheck.sh

Config:
- `config/routes.properties`
- `config/clients.properties`
- `data/audits.log` 运行时生成
- `data/metrics.log` 运行时生成

Client quota config:
- `client.<name>.requests_per_minute=<default_quota>`
- `client.<name>.service.<service>.requests_per_minute=<service_quota_override>`

Route health config:
- `service.<name>.health_url=<upstream_health_endpoint>`

API:
- `GET /`
- `GET /console`
- `GET /assets/app.js`
- `GET /health`
- `GET|POST /gateway/v1/invoke?service=<qa|compliance|pricing>`
- `GET /ops/metrics/overview`
- `GET /ops/metrics/history`
- `GET /ops/audits`
- `GET /ops/config`
- `GET /ops/upstreams`
- `GET /ops/overview`
- `GET /ops/layers`
- `GET /ops/l2/scenarios`
- `GET /ops/l3/capabilities`
- `GET /ops/l4/runtime`
- `GET /ops/l5/knowledge`
- `GET /ops/l6/models`
- `GET /ops/l7/platform`
- `POST /ops/reload`
- `POST /debug/request?service=<qa|compliance|pricing>`
- `GET /debug/trace/{request_id}`
- `POST /debug/replay/{request_id}`

Headers:
- `x-api-key: demo-key-ops`
- `x-api-key: demo-key-business`
- `x-api-key: demo-key-partner`
- `x-tenant-id: <tenant>`
- `x-operator-id: <operator>`

QA contract mapping:
- 当 `service=qa` 时，L1 会把网关请求转换为 L2 `agent-business-solution` 的标准契约。
- `prompt` 会映射为 `input.question`
- `service=qa` 会映射为 `scenario_code=intelligent_qa`
- `x-tenant-id`、`x-operator-id` 会映射为 `tenant_id`、`operator_id`

Compliance contract mapping:
- 当 `service=compliance` 时，L1 会把网关请求转换为 L3 `atomic-ai-service` 的标准能力契约。
- `document` 或 `prompt` 会映射为 `input.document`
- `service=compliance` 会映射为 `capability_code=structured_extraction`

Pricing contract mapping:
- 当 `service=pricing` 时，L1 会把网关请求转换为 L4 `agent-model-runtime` 的标准运行契约。
- `payload` 或 `prompt` 会映射为 `input.payload`
- `service=pricing` 会映射为 `task_type=pricing_inference`

Capabilities:
- API Key 鉴权
- 服务路由与策略控制
- 按客户端每分钟限流
- 审计记录
- 运营指标汇总
- HTTP upstream 转发到 L2/L3/L4
- 路由与客户端策略配置化
- 审计日志持久化到文件
- 按客户端 + 服务粒度限流
- Upstream 健康探测与短时熔断
- 统一运营总览接口
- 配置热重载
- 指标持久化与历史查询
- QA 请求契约已与 L2 `intelligent_qa` 场景对齐
- Compliance 与 Pricing 请求契约已分别对齐到 L3/L4 标准接口
- `/ops/overview` 已包含契约转换统计
- `/ops/overview` 已包含按契约类型聚合的错误码统计
- 已提供路由式控制台页面：`/console/dashboard`、`/console/debug`、`/console/scenarios`、`/console/runtime`、`/console/knowledge`、`/console/models`、`/console/platform`
- 已提供 L2-L7 聚合接口，供控制台读取
- 已提供 trace / replay 调试能力
