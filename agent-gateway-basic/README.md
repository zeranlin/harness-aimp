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
- `GET /health`
- `GET|POST /gateway/v1/invoke?service=<qa|compliance|pricing>`
- `GET /ops/metrics/overview`
- `GET /ops/metrics/history`
- `GET /ops/audits`
- `GET /ops/config`
- `GET /ops/upstreams`
- `GET /ops/overview`
- `POST /ops/reload`

Headers:
- `x-api-key: demo-key-ops`
- `x-api-key: demo-key-business`
- `x-api-key: demo-key-partner`

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
