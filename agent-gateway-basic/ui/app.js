const routes = [
  { path: "/console/dashboard", label: "Dashboard", title: "System Dashboard", description: "查看七层健康、总览指标与关键异常。" },
  { path: "/console/debug", label: "Debug Studio", title: "Debug Studio", description: "构造请求、查看契约转换、trace 与 replay。" },
  { path: "/console/scenarios", label: "Scenarios", title: "L2 Scenarios", description: "查看场景编排、状态与执行入口。" },
  { path: "/console/runtime", label: "Model Runtime", title: "L4 Runtime", description: "查看模型运行时并发、路由与重试策略。" },
  { path: "/console/knowledge", label: "Knowledge Ops", title: "L5 Knowledge", description: "查看知识库与数据流水线状态。" },
  { path: "/console/models", label: "Model Hub", title: "L6 Models", description: "查看模型注册、评测与成本基线。" },
  { path: "/console/platform", label: "Platform", title: "L7 Platform", description: "查看基础设施、脚本健康与底座状态。" },
];

async function getJson(url, options = {}) {
  const response = await fetch(url, options);
  const text = await response.text();
  try {
    return JSON.parse(text);
  } catch (error) {
    return { status: "error", message: text || "invalid json" };
  }
}

function navigate(path) {
  window.history.pushState({}, "", path);
  render();
}

function navLink(route) {
  const active = window.location.pathname === route.path || (window.location.pathname === "/console" && route.path === "/console/dashboard");
  return `<a href="${route.path}" data-path="${route.path}" class="${active ? "active" : ""}">${route.label}</a>`;
}

function card(label, value, tone = "") {
  return `<div class="card ${tone}"><div class="label">${label}</div><div class="value">${value}</div></div>`;
}

function sectionCard(title, body, tone = "") {
  return `<div class="card ${tone}"><div class="label">${title}</div><div style="margin-top:8px;color:var(--muted);line-height:1.55;">${body}</div></div>`;
}

function pretty(payload) {
  return JSON.stringify(payload, null, 2);
}

async function renderDashboard() {
  const [overview, layers] = await Promise.all([getJson("/ops/overview"), getJson("/ops/layers")]);
  const services = overview.metrics?.services || [];
  const audit = overview.audit_summary || {};
  const layerCards = (layers.items || []).map((item) =>
    sectionCard(`${item.layer} · ${item.project}`, `${item.status} · ${item.health_mode}<br/>${item.target}`, item.healthy ? "good" : "bad")
  ).join("");
  return `
    <div class="toolbar"><h2>Operations Snapshot</h2><button id="refresh-dashboard">刷新</button></div>
    <div class="grid stats" style="margin-top:16px;">
      ${card("Tracked Services", services.length)}
      ${card("Audit Total", audit.total || 0)}
      ${card("Allowed", audit.allow || 0)}
      ${card("Denied", audit.deny || 0)}
    </div>
    <div class="grid cards" style="margin-top:16px;">${layerCards}</div>
  `;
}

async function renderDebug() {
  return `
    <div class="toolbar"><h2>Contract Debugging</h2><div></div></div>
    <div class="split" style="margin-top:16px;">
      <div class="stack">
        <div class="field"><label>Service</label><select id="service"><option value="qa">qa</option><option value="compliance">compliance</option><option value="pricing">pricing</option></select></div>
        <div class="field"><label>x-api-key</label><input id="api-key" value="demo-key-ops" /></div>
        <div class="field"><label>x-tenant-id</label><input id="tenant-id" value="demo" /></div>
        <div class="field"><label>x-operator-id</label><input id="operator-id" value="operator-1" /></div>
        <div class="field"><label>Request Body</label><textarea id="request-body">{ "request_id": "req-ui-001", "prompt": "采购评审里废标条款怎么判断？" }</textarea></div>
        <div class="field"><label>Trace Request ID</label><input id="trace-id" value="req-ui-001" /></div>
        <div style="display:flex;gap:8px;flex-wrap:wrap;">
          <button id="send-debug">发送调试请求</button>
          <button class="secondary" id="load-trace">查看 Trace</button>
          <button class="secondary" id="replay-trace">Replay</button>
        </div>
      </div>
      <div class="stack">
        <div><div class="label">Transformed Contract</div><pre id="transformed-request">{}</pre></div>
        <div><div class="label">Gateway Response</div><pre id="gateway-response">{}</pre></div>
        <div><div class="label">Trace / Replay</div><pre id="trace-response">{}</pre></div>
      </div>
    </div>
  `;
}

async function renderScenarios() {
  const payload = await getJson("/ops/l2/scenarios");
  const items = payload.items || [];
  return `
    <div class="toolbar"><h2>Scenario Registry</h2><button id="refresh-scenarios">刷新</button></div>
    <div class="grid cards" style="margin-top:16px;">
      ${items.map((item) => sectionCard(item.scenario_code, `${item.name} · ${item.status} · ${item.orchestrator_type}<br/>Dependencies: ${(item.dependencies || []).join(", ")}`)).join("") || sectionCard("Status", payload.message || "No scenarios")}
    </div>
  `;
}

async function renderRuntime() {
  const payload = await getJson("/ops/l4/runtime");
  return `
    <div class="toolbar"><h2>Runtime Governance</h2><button id="refresh-runtime">刷新</button></div>
    <div class="grid cards" style="margin-top:16px;">
      ${sectionCard("Runtime", `${payload.service || "unavailable"} · inflight ${payload.inflight ?? "-"} · queue ${payload.queue_depth ?? "-"}`)}
      ${sectionCard("Retry Policy", `max_attempts ${payload.retry_policy?.max_attempts ?? "-"}<br/>timeout_ms ${payload.retry_policy?.timeout_ms ?? "-"}`)}
      ${sectionCard("Routes", (payload.routes || []).map((item) => `${item.task_type} -> ${item.model_route}`).join("<br/>") || payload.message || "No routes")}
    </div>
  `;
}

async function renderKnowledge() {
  const payload = await getJson("/ops/l5/knowledge");
  return `
    <div class="toolbar"><h2>Knowledge Operations</h2><button id="refresh-knowledge">刷新</button></div>
    <div class="grid cards" style="margin-top:16px;">
      ${((payload.knowledge_bases || []).map((item) => sectionCard(item.name, `${item.status} · documents ${item.documents}`)).join("")) || sectionCard("Status", payload.message || "No data")}
      ${sectionCard("Pipelines", (payload.pipelines || []).map((item) => `${item.name} · ${item.status}`).join("<br/>") || "No pipelines")}
      ${sectionCard("Quality", `trusted_assets ${payload.quality?.trusted_assets ?? 0}<br/>issues ${payload.quality?.issues ?? 0}`)}
    </div>
  `;
}

async function renderModels() {
  const payload = await getJson("/ops/l6/models");
  return `
    <div class="toolbar"><h2>Model Hub</h2><button id="refresh-models">刷新</button></div>
    <div class="grid cards" style="margin-top:16px;">
      ${((payload.models || []).map((item) => sectionCard(item.name, `${item.type} · ${item.status}`)).join("")) || sectionCard("Status", payload.message || "No models")}
      ${sectionCard("Evaluations", (payload.evaluations || []).map((item) => `${item.benchmark} -> ${item.winner}`).join("<br/>") || "No evaluations")}
      ${sectionCard("Cost Baseline", `daily_budget ${payload.cost_baseline?.daily_budget ?? 0} ${payload.cost_baseline?.currency ?? ""}`)}
    </div>
  `;
}

async function renderPlatform() {
  const payload = await getJson("/ops/l7/platform");
  return `
    <div class="toolbar"><h2>Platform Foundation</h2><button id="refresh-platform">刷新</button></div>
    <div class="grid cards" style="margin-top:16px;">
      ${sectionCard("Health", `healthcheck ${payload.healthcheck}<br/>build_ready ${payload.build_ready}<br/>test_ready ${payload.test_ready}<br/>run_ready ${payload.run_ready}`)}
      ${sectionCard("Service", `${payload.service || "agent-platform-foundation"} · ${payload.status || "unknown"}`)}
    </div>
  `;
}

async function renderRoute(route) {
  if (route.path === "/console/dashboard") return renderDashboard();
  if (route.path === "/console/debug") return renderDebug();
  if (route.path === "/console/scenarios") return renderScenarios();
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
  const send = document.getElementById("send-debug");
  if (send) send.addEventListener("click", sendDebugRequest);
  const trace = document.getElementById("load-trace");
  if (trace) trace.addEventListener("click", loadTrace);
  const replay = document.getElementById("replay-trace");
  if (replay) replay.addEventListener("click", replayTrace);
  const refreshers = {
    "refresh-dashboard": "/console/dashboard",
    "refresh-scenarios": "/console/scenarios",
    "refresh-runtime": "/console/runtime",
    "refresh-knowledge": "/console/knowledge",
    "refresh-models": "/console/models",
    "refresh-platform": "/console/platform",
  };
  Object.entries(refreshers).forEach(([id, path]) => {
    const node = document.getElementById(id);
    if (node) node.addEventListener("click", () => navigate(path));
  });
}

async function render() {
  const pathname = window.location.pathname === "/console" ? "/console/dashboard" : window.location.pathname;
  const current = routes.find((item) => item.path === pathname) || routes[0];
  document.getElementById("nav").innerHTML = routes.map(navLink).join("");
  document.getElementById("route-tag").textContent = current.label;
  document.getElementById("route-title").textContent = current.title;
  document.getElementById("route-description").textContent = current.description;
  document.getElementById("view-root").innerHTML = await renderRoute(current);
  bindInteractions();
}

window.addEventListener("popstate", render);
render();
