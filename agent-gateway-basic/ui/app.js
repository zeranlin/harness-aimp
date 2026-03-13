const routes = [
  { path: "/console/dashboard", label: "总览", title: "系统总览", description: "查看七层健康、总览指标与关键异常。" },
  { path: "/console/gateway", label: "L1 网关层", title: "L1 网关运营", description: "查看网关入口、鉴权、配额、审计与聚合治理结果。" },
  { path: "/console/scenarios", label: "L2 场景层", title: "L2 场景运营", description: "查看场景编排、状态、依赖和执行入口。" },
  { path: "/console/capabilities", label: "L3 能力层", title: "L3 原子能力", description: "查看能力目录、调用量、错误码分布与输入输出定义。" },
  { path: "/console/runtime", label: "L4 运行时", title: "L4 模型运行时", description: "查看模型运行时并发、路由、队列与重试策略。" },
  { path: "/console/knowledge", label: "L5 知识层", title: "L5 知识运营", description: "查看知识库、流水线与质量状态。" },
  { path: "/console/models", label: "L6 模型层", title: "L6 模型中心", description: "查看模型注册、评测和成本基线。" },
  { path: "/console/platform", label: "L7 平台层", title: "L7 平台底座", description: "查看平台脚本健康和底座可用性。" },
  { path: "/console/debug", label: "调试台", title: "调试工作台", description: "构造请求、查看契约转换、Trace 与 Replay。" },
];

let viewDetails = null;

async function getJson(url, options = {}) {
  const response = await fetch(url, options);
  const text = await response.text();
  try {
    return JSON.parse(text);
  } catch (error) {
    return { status: "error", message: text || "无效 JSON 响应" };
  }
}

function currentUrl() {
  return new URL(window.location.href);
}

function currentPath() {
  return window.location.pathname === "/console" ? "/console/dashboard" : window.location.pathname;
}

function navigate(path, options = {}) {
  const url = currentUrl();
  url.pathname = path;
  if (!options.keepQuery) {
    url.search = "";
  }
  window.history.pushState({}, "", url.toString());
  render();
}

function updateSearchParam(key, value) {
  const url = currentUrl();
  if (value) {
    url.searchParams.set(key, value);
  } else {
    url.searchParams.delete(key);
  }
  window.history.replaceState({}, "", url.toString());
}

function readSearchParam(key) {
  return currentUrl().searchParams.get(key) || "";
}

function navLink(route) {
  const active = currentPath() === route.path;
  return `<a href="${route.path}" data-path="${route.path}" class="${active ? "active" : ""}">${route.label}</a>`;
}

function card(label, value, tone = "") {
  return `<div class="card ${tone}"><div class="label">${label}</div><div class="value">${value}</div></div>`;
}

function sectionCard(title, body, tone = "") {
  return `<div class="card ${tone}"><div class="label">${title}</div><div style="margin-top:8px;color:var(--muted);line-height:1.55;">${body}</div></div>`;
}

function table(headers, rows, options = {}) {
  const tableId = options.tableId ? ` data-table-id="${options.tableId}"` : "";
  return `
    <div class="table-wrap"${tableId}>
      <table>
        <thead><tr>${headers.map((item) => `<th>${item}</th>`).join("")}</tr></thead>
        <tbody>${rows.join("") || `<tr><td colspan="${headers.length}">暂无数据</td></tr>`}</tbody>
      </table>
    </div>
  `;
}

function filterBar(options = {}) {
  const {
    searchId = "search",
    searchPlaceholder = "搜索",
    statusId = "status-filter",
    statuses = [],
  } = options;
  return `
    <div style="display:flex;gap:10px;flex-wrap:wrap;margin-top:16px;">
      <input id="${searchId}" placeholder="${searchPlaceholder}" style="max-width:320px;" value="${readSearchParam(searchId)}" />
      <select id="${statusId}" style="max-width:220px;">
        <option value="">全部状态</option>
        ${statuses.map((item) => `<option value="${item}" ${readSearchParam(statusId) === item ? "selected" : ""}>${item}</option>`).join("")}
      </select>
    </div>
  `;
}

function pretty(payload) {
  return JSON.stringify(payload, null, 2);
}

function toAlertTone(statusText) {
  const value = String(statusText || "").toLowerCase();
  if (["up", "ok", "healthy", "normal", "active", "running"].some((item) => value.includes(item) || value.includes("正常") || value.includes("活动"))) {
    return { tone: "good", label: "绿", text: "正常" };
  }
  if (["degraded", "warning", "partial", "testing", "draft"].some((item) => value.includes(item)) || value.includes("测试") || value.includes("草稿") || value.includes("降级")) {
    return { tone: "warn", label: "黄", text: "关注" };
  }
  return { tone: "bad", label: "红", text: "异常" };
}

function alertBadge(statusText) {
  const meta = toAlertTone(statusText);
  return `<span class="badge ${meta.tone}">${meta.label} · ${meta.text}</span>`;
}

function detailButton(kind, index) {
  return `<button class="secondary detail-trigger" data-detail-kind="${kind}" data-detail-index="${index}">查看详情</button>`;
}

function openDetail(kind, index) {
  const datasets = window.__consoleDatasets || {};
  const list = datasets[kind] || [];
  const item = list[index];
  if (!item) return;
  viewDetails = { title: item.__detailTitle || item.name || item.project || item.layer || kind, payload: item };
  const drawer = document.getElementById("detail-drawer");
  const title = document.getElementById("detail-title");
  const body = document.getElementById("detail-body");
  if (!drawer || !title || !body) return;
  title.textContent = viewDetails.title;
  body.textContent = pretty(item);
  drawer.classList.add("open");
}

function closeDetail() {
  viewDetails = null;
  const drawer = document.getElementById("detail-drawer");
  if (drawer) drawer.classList.remove("open");
}

function renderLayerCards(items) {
  return (items || []).map((item) => {
    const meta = toAlertTone(item.status);
    return sectionCard(`${item.layer} · ${item.project}`, `${alertBadge(item.status)}<br/>${item.health_mode}<br/>${item.target}`, meta.tone);
  }).join("");
}

async function renderDashboard() {
  const [overview, layers] = await Promise.all([getJson("/ops/overview"), getJson("/ops/layers")]);
  const services = overview.metrics?.services || [];
  const audit = overview.audit_summary || {};
  const matrixRows = (layers.items || []).map((item, index) => {
    const meta = toAlertTone(item.status);
    return `<tr><td>${item.layer}</td><td>${item.project}</td><td>${alertBadge(item.status)}</td><td>${item.health_mode}</td><td>${item.target}</td><td>${detailButton("layers", index)}</td></tr>`;
  });
  window.__consoleDatasets = { ...(window.__consoleDatasets || {}), layers: (layers.items || []).map((item) => ({ ...item, __detailTitle: `${item.layer} ${item.project}` })) };
  const green = (layers.items || []).filter((item) => toAlertTone(item.status).tone === "good").length;
  const yellow = (layers.items || []).filter((item) => toAlertTone(item.status).tone === "warn").length;
  const red = (layers.items || []).filter((item) => toAlertTone(item.status).tone === "bad").length;
  return `
    <div class="toolbar"><h2>运营快照</h2><button id="refresh-dashboard">刷新</button></div>
    <div class="grid stats" style="margin-top:16px;">
      ${card("纳管服务", services.length)}
      ${card("审计总量", audit.total || 0)}
      ${card("允许请求", audit.allow || 0, "good")}
      ${card("拒绝请求", audit.deny || 0, audit.deny ? "bad" : "")}
    </div>
    <div class="grid stats" style="margin-top:16px;">
      ${card("绿色状态", green, "good")}
      ${card("黄色状态", yellow, yellow ? "warn" : "")}
      ${card("红色状态", red, red ? "bad" : "")}
      ${card("层级总数", (layers.items || []).length)}
    </div>
    <div class="grid cards" style="margin-top:16px;">${renderLayerCards(layers.items)}</div>
    <div style="margin-top:16px;">${table(["层级", "项目", "告警状态", "检查方式", "聚合目标", "明细"], matrixRows, { tableId: "dashboard-matrix" })}</div>
  `;
}

async function renderGateway() {
  const overview = await getJson("/ops/overview");
  const services = overview.metrics?.services || [];
  const audit = overview.audit_summary || {};
  const transforms = overview.contract_transformations?.items || [];
  const transformRows = transforms.map((item, index) =>
    `<tr><td>${item.service}</td><td>${item.target}</td><td>${item.transform_count}</td><td>${detailButton("transforms", index)}</td></tr>`
  );
  window.__consoleDatasets = { ...(window.__consoleDatasets || {}), transforms: transforms.map((item) => ({ ...item, __detailTitle: `${item.service} 契约转换` })) };
  return `
    <div class="toolbar"><h2>网关运营</h2><button id="refresh-gateway">刷新</button></div>
    <div class="grid stats" style="margin-top:16px;">
      ${card("服务数", services.length)}
      ${card("允许", audit.allow || 0, "good")}
      ${card("拒绝", audit.deny || 0, audit.deny ? "bad" : "")}
      ${card("审计总数", audit.total || 0)}
    </div>
    <div style="margin-top:16px;">${table(["服务", "目标契约", "转换次数", "明细"], transformRows, { tableId: "gateway-transforms" })}</div>
  `;
}

async function renderDebug() {
  return `
    <div class="toolbar"><h2>契约调试</h2><div></div></div>
    <div class="split" style="margin-top:16px;">
      <div class="stack">
        <div class="field"><label>服务</label><select id="service"><option value="qa">qa</option><option value="compliance">compliance</option><option value="pricing">pricing</option></select></div>
        <div class="field"><label>x-api-key</label><input id="api-key" value="demo-key-ops" /></div>
        <div class="field"><label>x-tenant-id</label><input id="tenant-id" value="demo" /></div>
        <div class="field"><label>x-operator-id</label><input id="operator-id" value="operator-1" /></div>
        <div class="field"><label>请求体</label><textarea id="request-body">{ "request_id": "req-ui-001", "prompt": "采购评审里废标条款怎么判断？" }</textarea></div>
        <div class="field"><label>Trace 请求 ID</label><input id="trace-id" value="req-ui-001" /></div>
        <div style="display:flex;gap:8px;flex-wrap:wrap;">
          <button id="send-debug">发送调试请求</button>
          <button class="secondary" id="load-trace">查看 Trace</button>
          <button class="secondary" id="replay-trace">执行 Replay</button>
        </div>
      </div>
      <div class="stack">
        <div><div class="label">转换后契约</div><pre id="transformed-request">{}</pre></div>
        <div><div class="label">网关响应</div><pre id="gateway-response">{}</pre></div>
        <div><div class="label">Trace / Replay 结果</div><pre id="trace-response">{}</pre></div>
      </div>
    </div>
  `;
}

async function renderScenarios() {
  const payload = await getJson("/ops/l2/scenarios");
  const items = payload.items || [];
  const rows = items.map((item, index) =>
    `<tr><td>${item.scenario_code}</td><td>${item.name}</td><td>${item.status}</td><td>${item.orchestrator_type}</td><td>${(item.dependencies || []).join(", ")}</td><td>${detailButton("scenarios", index)}</td></tr>`
  );
  window.__consoleDatasets = { ...(window.__consoleDatasets || {}), scenarios: items.map((item) => ({ ...item, __detailTitle: `${item.scenario_code} 场景详情` })) };
  return `
    <div class="toolbar"><h2>场景注册表</h2><button id="refresh-scenarios">刷新</button></div>
    <div class="grid stats" style="margin-top:16px;">
      ${card("场景数", items.length)}
      ${card("活动场景", items.filter((item) => item.status === "active").length, "good")}
      ${card("依赖总数", items.reduce((sum, item) => sum + (item.dependencies || []).length, 0))}
      ${card("编排类型", [...new Set(items.map((item) => item.orchestrator_type))].length)}
    </div>
    ${filterBar({ searchId: "scenario-search", searchPlaceholder: "搜索场景编码或名称", statusId: "scenario-status", statuses: [...new Set(items.map((item) => item.status))] })}
    <div style="margin-top:16px;">${table(["场景编码", "名称", "状态", "编排类型", "依赖服务", "明细"], rows, { tableId: "scenario-table" })}</div>
  `;
}

async function renderCapabilities() {
  const payload = await getJson("/ops/l3/capabilities");
  const items = payload.items || [];
  const stats = payload.stats || {};
  const capabilityStats = stats.capabilities || {};
  const gatewayRejects = payload.gateway_rejects || {};
  const rows = items.map((item, index) => {
    const itemStats = capabilityStats[item.capability_code] || {};
    return `<tr><td>${item.capability_code}</td><td>${item.name}</td><td>${item.status}</td><td>${itemStats.calls || 0}</td><td>${itemStats.average_duration_ms || 0}</td><td>${itemStats.last_status || "-"}</td><td>${detailButton("capabilities", index)}</td></tr>`;
  });
  const errorRows = Object.entries(stats.error_codes || {}).map(
    ([code, count]) => `<tr><td>${code}</td><td>${count}</td></tr>`
  );
  const rejectRows = Object.entries(gatewayRejects.error_codes || {}).map(
    ([code, count]) => `<tr><td>${code}</td><td>${count}</td></tr>`
  );
  window.__consoleDatasets = { ...(window.__consoleDatasets || {}), capabilities: items.map((item) => ({ ...item, stats: capabilityStats[item.capability_code] || {}, __detailTitle: `${item.capability_code} 能力详情` })) };
  return `
    <div class="toolbar"><h2>原子能力目录</h2><button id="refresh-capabilities">刷新</button></div>
    <div class="grid stats" style="margin-top:16px;">
      ${card("能力数", items.length)}
      ${card("调用量", stats.total_calls || 0, "good")}
      ${card("平均耗时", stats.average_duration_ms || 0)}
      ${card("错误种类", Object.keys(stats.error_codes || {}).length, Object.keys(stats.error_codes || {}).length ? "bad" : "")}
    </div>
    <div class="grid stats" style="margin-top:16px;">
      ${card("成功调用", stats.success_calls || 0, "good")}
      ${card("失败调用", stats.error_calls || 0, stats.error_calls ? "bad" : "")}
      ${card("网关拒绝", gatewayRejects.count || 0, (gatewayRejects.count || 0) ? "warn" : "")}
      ${card("活跃能力", items.filter((item) => item.status === "active").length)}
    </div>
    ${filterBar({ searchId: "capability-search", searchPlaceholder: "搜索能力编码或名称", statusId: "capability-status", statuses: [...new Set(items.map((item) => item.status))] })}
    <div style="margin-top:16px;">${table(["能力编码", "名称", "状态", "调用量", "平均耗时(ms)", "最近状态", "明细"], rows, { tableId: "capability-table" })}</div>
    <div style="margin-top:16px;">${table(["能力错误码", "次数"], errorRows, { tableId: "capability-errors" })}</div>
    <div style="margin-top:16px;">${table(["网关拒绝原因", "次数"], rejectRows, { tableId: "capability-rejects" })}</div>
  `;
}

async function renderRuntime() {
  const payload = await getJson("/ops/l4/runtime");
  const routes = payload.routes || [];
  const rows = routes.map((item, index) =>
    `<tr><td>${item.task_type}</td><td>${item.model_route}</td><td>${detailButton("runtimeRoutes", index)}</td></tr>`
  );
  window.__consoleDatasets = { ...(window.__consoleDatasets || {}), runtimeRoutes: routes.map((item) => ({ ...item, __detailTitle: `${item.task_type} 路由详情` })) };
  return `
    <div class="toolbar"><h2>运行时治理</h2><button id="refresh-runtime">刷新</button></div>
    <div class="grid stats" style="margin-top:16px;">
      ${card("执行中", payload.inflight ?? "-")}
      ${card("队列深度", payload.queue_depth ?? "-")}
      ${card("并发上限", payload.max_concurrency ?? "-")}
      ${card("熔断器", (payload.circuit_breakers || []).length, (payload.circuit_breakers || []).length ? "warn" : "")}
    </div>
    <div style="margin-top:16px;">${table(["任务类型", "模型路由", "明细"], rows, { tableId: "runtime-table" })}</div>
  `;
}

async function renderKnowledge() {
  const payload = await getJson("/ops/l5/knowledge");
  const knowledgeBases = payload.knowledge_bases || [];
  const pipelines = payload.pipelines || [];
  const rows = knowledgeBases.map((item, index) =>
    `<tr><td>${item.name}</td><td>${item.status}</td><td>${item.documents}</td><td>${detailButton("knowledgeBases", index)}</td></tr>`
  );
  const pipelineRows = pipelines.map((item, index) =>
    `<tr><td>${item.name}</td><td>${item.status}</td><td>${detailButton("knowledgePipelines", index)}</td></tr>`
  );
  window.__consoleDatasets = {
    ...(window.__consoleDatasets || {}),
    knowledgeBases: knowledgeBases.map((item) => ({ ...item, __detailTitle: `${item.name} 知识库详情` })),
    knowledgePipelines: pipelines.map((item) => ({ ...item, __detailTitle: `${item.name} 流水线详情` })),
  };
  return `
    <div class="toolbar"><h2>知识运营</h2><button id="refresh-knowledge">刷新</button></div>
    <div class="grid stats" style="margin-top:16px;">
      ${card("知识库", knowledgeBases.length)}
      ${card("流水线", pipelines.length)}
      ${card("可信资产", payload.quality?.trusted_assets ?? 0, "good")}
      ${card("问题数", payload.quality?.issues ?? 0, (payload.quality?.issues ?? 0) ? "warn" : "")}
    </div>
    ${filterBar({ searchId: "knowledge-search", searchPlaceholder: "搜索知识库名称", statusId: "knowledge-status", statuses: [...new Set(knowledgeBases.map((item) => item.status))] })}
    <div style="margin-top:16px;">${table(["知识库", "状态", "文档数", "明细"], rows, { tableId: "knowledge-table" })}</div>
    <div style="margin-top:16px;">${table(["流水线", "状态", "明细"], pipelineRows, { tableId: "knowledge-pipelines" })}</div>
  `;
}

async function renderModels() {
  const payload = await getJson("/ops/l6/models");
  const models = payload.models || [];
  const evaluations = payload.evaluations || [];
  const rows = models.map((item, index) =>
    `<tr><td>${item.name}</td><td>${item.type}</td><td>${item.status}</td><td>${detailButton("models", index)}</td></tr>`
  );
  const evalRows = evaluations.map((item, index) =>
    `<tr><td>${item.benchmark}</td><td>${item.winner}</td><td>${detailButton("evaluations", index)}</td></tr>`
  );
  window.__consoleDatasets = {
    ...(window.__consoleDatasets || {}),
    models: models.map((item) => ({ ...item, __detailTitle: `${item.name} 模型详情` })),
    evaluations: evaluations.map((item) => ({ ...item, __detailTitle: `${item.benchmark} 评测详情` })),
  };
  return `
    <div class="toolbar"><h2>模型中心</h2><button id="refresh-models">刷新</button></div>
    <div class="grid stats" style="margin-top:16px;">
      ${card("模型数", models.length)}
      ${card("评测项", evaluations.length)}
      ${card("日预算", payload.cost_baseline?.daily_budget ?? 0)}
      ${card("币种", payload.cost_baseline?.currency ?? "-")}
    </div>
    ${filterBar({ searchId: "model-search", searchPlaceholder: "搜索模型名称", statusId: "model-status", statuses: [...new Set(models.map((item) => item.status))] })}
    <div style="margin-top:16px;">${table(["模型", "类型", "状态", "明细"], rows, { tableId: "model-table" })}</div>
    <div style="margin-top:16px;">${table(["基准", "胜出模型", "明细"], evalRows, { tableId: "evaluation-table" })}</div>
  `;
}

async function renderPlatform() {
  const payload = await getJson("/ops/l7/platform");
  const items = [
    { item: "healthcheck", status: payload.healthcheck },
    { item: "build_ready", status: payload.build_ready },
    { item: "test_ready", status: payload.test_ready },
    { item: "run_ready", status: payload.run_ready },
  ];
  const rows = items.map((item, index) => `<tr><td>${item.item}</td><td>${String(item.status)}</td><td>${detailButton("platformItems", index)}</td></tr>`);
  window.__consoleDatasets = { ...(window.__consoleDatasets || {}), platformItems: items.map((item) => ({ ...item, __detailTitle: `${item.item} 平台检查` })) };
  return `
    <div class="toolbar"><h2>平台底座</h2><button id="refresh-platform">刷新</button></div>
    <div class="grid stats" style="margin-top:16px;">
      ${card("服务状态", payload.status || "-")}
      ${card("健康检查", payload.healthcheck ? "正常" : "异常", payload.healthcheck ? "good" : "bad")}
      ${card("构建脚本", payload.build_ready ? "就绪" : "缺失", payload.build_ready ? "good" : "bad")}
      ${card("运行脚本", payload.run_ready ? "就绪" : "缺失", payload.run_ready ? "good" : "bad")}
    </div>
    <div style="margin-top:16px;">${table(["项目项", "状态", "明细"], rows, { tableId: "platform-table" })}</div>
  `;
}

function rowText(row) {
  return row.textContent.toLowerCase();
}

function applyTableFilters(searchId, statusId, tableSelector, statusColumnIndex) {
  const searchNode = document.getElementById(searchId);
  const statusNode = document.getElementById(statusId);
  const tableNode = document.querySelector(`[data-table-id="${tableSelector}"] table`);
  if (!searchNode || !statusNode || !tableNode) return;
  const update = () => {
    const searchValue = searchNode.value.trim().toLowerCase();
    const statusValue = statusNode.value.trim().toLowerCase();
    updateSearchParam(searchId, searchNode.value.trim());
    updateSearchParam(statusId, statusNode.value.trim());
    tableNode.querySelectorAll("tbody tr").forEach((row) => {
      const cells = row.querySelectorAll("td");
      const textMatch = !searchValue || rowText(row).includes(searchValue);
      const statusMatch = !statusValue || (cells[statusColumnIndex] && cells[statusColumnIndex].textContent.toLowerCase().includes(statusValue));
      row.style.display = textMatch && statusMatch ? "" : "none";
    });
  };
  searchNode.addEventListener("input", update);
  statusNode.addEventListener("change", update);
  update();
}

async function renderRoute(route) {
  if (route.path === "/console/dashboard") return renderDashboard();
  if (route.path === "/console/gateway") return renderGateway();
  if (route.path === "/console/debug") return renderDebug();
  if (route.path === "/console/scenarios") return renderScenarios();
  if (route.path === "/console/capabilities") return renderCapabilities();
  if (route.path === "/console/runtime") return renderRuntime();
  if (route.path === "/console/knowledge") return renderKnowledge();
  if (route.path === "/console/models") return renderModels();
  if (route.path === "/console/platform") return renderPlatform();
  return renderDashboard();
}

async function sendDebugRequest() {
  const service = document.getElementById("service").value;
  const payload = await getJson(`/debug/request?service=${encodeURIComponent(service)}`, {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
      "x-api-key": document.getElementById("api-key").value,
      "x-tenant-id": document.getElementById("tenant-id").value,
      "x-operator-id": document.getElementById("operator-id").value,
    },
    body: document.getElementById("request-body").value,
  });
  document.getElementById("transformed-request").textContent = pretty(payload.transformed_request || payload);
  document.getElementById("gateway-response").textContent = pretty(payload.gateway_response || payload);
}

async function loadTrace() {
  const requestId = document.getElementById("trace-id").value;
  const payload = await getJson(`/debug/trace/${encodeURIComponent(requestId)}`);
  document.getElementById("trace-response").textContent = pretty(payload);
}

async function replayTrace() {
  const requestId = document.getElementById("trace-id").value;
  const payload = await getJson(`/debug/replay/${encodeURIComponent(requestId)}`, { method: "POST" });
  document.getElementById("trace-response").textContent = pretty(payload);
}

function bindInteractions() {
  document.querySelectorAll("#nav a").forEach((link) => {
    link.addEventListener("click", (event) => {
      event.preventDefault();
      navigate(link.dataset.path);
    });
  });
  document.querySelectorAll(".detail-trigger").forEach((button) => {
    button.addEventListener("click", () => openDetail(button.dataset.detailKind, Number(button.dataset.detailIndex)));
  });
  const closeButton = document.getElementById("detail-close");
  if (closeButton) closeButton.addEventListener("click", closeDetail);
  const drawer = document.getElementById("detail-drawer");
  if (drawer) drawer.addEventListener("click", (event) => {
    if (event.target === drawer) closeDetail();
  });
  const send = document.getElementById("send-debug");
  if (send) send.addEventListener("click", sendDebugRequest);
  const trace = document.getElementById("load-trace");
  if (trace) trace.addEventListener("click", loadTrace);
  const replay = document.getElementById("replay-trace");
  if (replay) replay.addEventListener("click", replayTrace);
  const refreshers = {
    "refresh-dashboard": "/console/dashboard",
    "refresh-gateway": "/console/gateway",
    "refresh-scenarios": "/console/scenarios",
    "refresh-capabilities": "/console/capabilities",
    "refresh-runtime": "/console/runtime",
    "refresh-knowledge": "/console/knowledge",
    "refresh-models": "/console/models",
    "refresh-platform": "/console/platform",
  };
  Object.entries(refreshers).forEach(([id, path]) => {
    const node = document.getElementById(id);
    if (node) node.addEventListener("click", () => navigate(path, { keepQuery: true }));
  });
  applyTableFilters("scenario-search", "scenario-status", "scenario-table", 2);
  applyTableFilters("capability-search", "capability-status", "capability-table", 2);
  applyTableFilters("knowledge-search", "knowledge-status", "knowledge-table", 1);
  applyTableFilters("model-search", "model-status", "model-table", 2);
}

async function render() {
  const pathname = currentPath();
  const current = routes.find((item) => item.path === pathname) || routes[0];
  document.getElementById("nav").innerHTML = routes.map(navLink).join("");
  document.getElementById("route-tag").textContent = current.label;
  document.getElementById("route-title").textContent = current.title;
  document.getElementById("route-description").textContent = current.description;
  document.getElementById("view-root").innerHTML = await renderRoute(current);
  bindInteractions();
}

window.addEventListener("popstate", render);
window.addEventListener("keydown", (event) => {
  if (event.key === "Escape") closeDetail();
});
render();
