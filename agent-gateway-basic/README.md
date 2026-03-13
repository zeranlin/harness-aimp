# agent-gateway-basic

L1 服务运营网关层（Java）。

Entrypoints:
- scripts/build.sh
- scripts/test.sh
- scripts/run.sh
- scripts/healthcheck.sh

API:
- `GET /health`
- `GET|POST /gateway/v1/invoke?service=<qa|compliance|pricing>`
- `GET /ops/metrics/overview`
- `GET /ops/audits`

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
