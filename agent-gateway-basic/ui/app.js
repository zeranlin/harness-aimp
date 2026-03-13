async function getJson(url, options = {}) {
  const response = await fetch(url, options);
  const text = await response.text();
  try {
    return JSON.parse(text);
  } catch (error) {
    return { status: "error", message: text || "invalid json" };
  }
}

function setPre(id, payload) {
  document.getElementById(id).textContent = JSON.stringify(payload, null, 2);
}

function card(label, value) {
  const div = document.createElement("div");
  div.className = "stat-card";
  div.innerHTML = `<div class="label">${label}</div><div class="value">${value}</div>`;
  return div;
}

function layerCard(item) {
  const div = document.createElement("div");
  div.className = `layer-card ${item.healthy ? "good" : "bad"}`;
  div.innerHTML = `
    <div class="label">${item.layer}</div>
    <div class="value" style="font-size:20px;">${item.project}</div>
    <p style="margin:8px 0 0;color:var(--muted);">${item.status} · ${item.health_mode}</p>
  `;
  return div;
}

function sectionCard(title, body) {
  const div = document.createElement("div");
  div.className = "section-card";
  div.innerHTML = `<div class="label">${title}</div><div style="margin-top:8px;">${body}</div>`;
  return div;
}

async function renderDashboard() {
  const [overview, layers] = await Promise.all([
    getJson("/ops/overview"),
    getJson("/ops/layers"),
  ]);

  const stats = document.getElementById("stats");
  stats.innerHTML = "";
  const services = (overview.metrics && overview.metrics.services) || [];
  const auditSummary = overview.audit_summary || {};
  stats.appendChild(card("Tracked Services", services.length));
  stats.appendChild(card("Audit Total", auditSummary.total || 0));
  stats.appendChild(card("Allowed", auditSummary.allow || 0));
  stats.appendChild(card("Denied", auditSummary.deny || 0));

  const layersRoot = document.getElementById("layers");
  layersRoot.innerHTML = "";
  ((layers.items) || []).forEach((item) => layersRoot.appendChild(layerCard(item)));
}

async function renderScenarios() {
  const payload = await getJson("/ops/l2/scenarios");
  const root = document.getElementById("scenarios");
  root.innerHTML = "";
  const items = payload.items || [];
  if (!items.length) {
    root.appendChild(sectionCard("Status", payload.message || "No scenarios available"));
    return;
  }
  items.forEach((item) => {
    root.appendChild(
      sectionCard(
        item.scenario_code,
        `${item.name} · ${item.status} · ${item.orchestrator_type}<br/>Dependencies: ${(item.dependencies || []).join(", ")}`
      )
    );
  });
}

async function renderRuntime() {
  const payload = await getJson("/ops/l4/runtime");
  const root = document.getElementById("runtime");
  root.innerHTML = "";
  if (payload.message) {
    root.appendChild(sectionCard("Status", payload.message));
    return;
  }
  root.appendChild(sectionCard("Runtime", `${payload.runtime || "priority-scheduler"} / inflight ${payload.inflight}`));
  root.appendChild(sectionCard("Retry Policy", `max_attempts ${payload.retry_policy?.max_attempts || 0}, timeout_ms ${payload.retry_policy?.timeout_ms || 0}`));
  root.appendChild(sectionCard("Routes", ((payload.routes || []).map((item) => `${item.task_type} -> ${item.model_route}`)).join("<br/>")));
}

async function sendDebugRequest() {
  const service = document.getElementById("service").value;
  const apiKey = document.getElementById("api-key").value;
  const tenantId = document.getElementById("tenant-id").value;
  const operatorId = document.getElementById("operator-id").value;
  const rawBody = document.getElementById("request-body").value;
  const payload = await getJson(`/debug/request?service=${encodeURIComponent(service)}`, {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
      "x-api-key": apiKey,
      "x-tenant-id": tenantId,
      "x-operator-id": operatorId,
    },
    body: rawBody,
  });
  setPre("transformed-request", payload.transformed_request || payload);
  setPre("gateway-response", payload.gateway_response || payload);
  renderDashboard();
}

document.getElementById("refresh-dashboard").addEventListener("click", renderDashboard);
document.getElementById("refresh-scenarios").addEventListener("click", renderScenarios);
document.getElementById("refresh-runtime").addEventListener("click", renderRuntime);
document.getElementById("send-debug").addEventListener("click", sendDebugRequest);

renderDashboard();
renderScenarios();
renderRuntime();
