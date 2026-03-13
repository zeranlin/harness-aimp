import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.BufferedReader;
import java.io.FileReader;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class Main {
    private static final int PORT = 8080;

    public static void main(String[] args) throws Exception {
        if (args.length > 0 && "--self-test".equals(args[0])) {
            runSelfTest();
            System.out.println("self-test ok");
            return;
        }

        GatewayApp app = GatewayApp.fromDisk(new HttpUpstreamInvoker());
        HttpServer server = HttpServer.create(new InetSocketAddress(PORT), 0);
        server.createContext("/health", new JsonHandler(new ExchangeProcessor() {
            @Override
            public GatewayResponse handle(HttpExchange exchange) {
                return GatewayResponse.ok(jsonObject(mapOf(
                        "status", "UP",
                        "service", "agent-gateway-basic"
                )));
            }
        }));
        server.createContext("/gateway/v1/invoke", new InvokeHandler(app));
        server.createContext("/ops/metrics/overview", new JsonHandler(new ExchangeProcessor() {
            @Override
            public GatewayResponse handle(HttpExchange exchange) {
                return GatewayResponse.ok(app.metricsJson());
            }
        }));
        server.createContext("/ops/metrics/history", new JsonHandler(new ExchangeProcessor() {
            @Override
            public GatewayResponse handle(HttpExchange exchange) {
                return GatewayResponse.ok(app.metricsHistoryJson());
            }
        }));
        server.createContext("/ops/audits", new JsonHandler(new ExchangeProcessor() {
            @Override
            public GatewayResponse handle(HttpExchange exchange) {
                return GatewayResponse.ok(app.auditJson());
            }
        }));
        server.createContext("/ops/config", new JsonHandler(new ExchangeProcessor() {
            @Override
            public GatewayResponse handle(HttpExchange exchange) {
                return GatewayResponse.ok(app.configJson());
            }
        }));
        server.createContext("/ops/upstreams", new JsonHandler(new ExchangeProcessor() {
            @Override
            public GatewayResponse handle(HttpExchange exchange) {
                return GatewayResponse.ok(app.upstreamsJson());
            }
        }));
        server.createContext("/ops/overview", new JsonHandler(new ExchangeProcessor() {
            @Override
            public GatewayResponse handle(HttpExchange exchange) {
                return GatewayResponse.ok(app.overviewJson());
            }
        }));
        server.createContext("/ops/layers", new JsonHandler(new ExchangeProcessor() {
            @Override
            public GatewayResponse handle(HttpExchange exchange) {
                return GatewayResponse.ok(app.layersJson());
            }
        }));
        server.createContext("/ops/l2/scenarios", new JsonHandler(new ExchangeProcessor() {
            @Override
            public GatewayResponse handle(HttpExchange exchange) {
                return GatewayResponse.ok(app.l2ScenariosJson());
            }
        }));
        server.createContext("/ops/l3/capabilities", new JsonHandler(new ExchangeProcessor() {
            @Override
            public GatewayResponse handle(HttpExchange exchange) {
                return GatewayResponse.ok(app.l3CapabilitiesJson());
            }
        }));
        server.createContext("/ops/l4/runtime", new JsonHandler(new ExchangeProcessor() {
            @Override
            public GatewayResponse handle(HttpExchange exchange) {
                return GatewayResponse.ok(app.l4RuntimeJson());
            }
        }));
        server.createContext("/ops/l5/knowledge", new JsonHandler(new ExchangeProcessor() {
            @Override
            public GatewayResponse handle(HttpExchange exchange) {
                return GatewayResponse.ok(app.l5KnowledgeJson());
            }
        }));
        server.createContext("/ops/l6/models", new JsonHandler(new ExchangeProcessor() {
            @Override
            public GatewayResponse handle(HttpExchange exchange) {
                return GatewayResponse.ok(app.l6ModelsJson());
            }
        }));
        server.createContext("/ops/l7/platform", new JsonHandler(new ExchangeProcessor() {
            @Override
            public GatewayResponse handle(HttpExchange exchange) {
                return GatewayResponse.ok(app.l7PlatformJson());
            }
        }));
        server.createContext("/ops/reload", new JsonHandler(new ExchangeProcessor() {
            @Override
            public GatewayResponse handle(HttpExchange exchange) throws IOException {
                if (!"POST".equals(exchange.getRequestMethod())) {
                    return GatewayResponse.json(405, jsonObject(mapOf(
                            "status", "error",
                            "message", "method not allowed"
                    )));
                }
                return GatewayResponse.ok(app.reloadJson());
            }
        }));
        server.createContext("/debug/request", new JsonHandler(new ExchangeProcessor() {
            @Override
            public GatewayResponse handle(HttpExchange exchange) throws IOException {
                if (!"POST".equals(exchange.getRequestMethod())) {
                    return GatewayResponse.json(405, jsonObject(mapOf(
                            "status", "error",
                            "message", "method not allowed"
                    )));
                }
                return GatewayResponse.ok(app.debugRequestJson(GatewayRequest.fromExchange(exchange)));
            }
        }));
        server.createContext("/debug/trace", new JsonHandler(new ExchangeProcessor() {
            @Override
            public GatewayResponse handle(HttpExchange exchange) {
                return GatewayResponse.ok(app.traceByPath(exchange.getRequestURI().getPath()));
            }
        }));
        server.createContext("/debug/replay", new JsonHandler(new ExchangeProcessor() {
            @Override
            public GatewayResponse handle(HttpExchange exchange) {
                if (!"POST".equals(exchange.getRequestMethod())) {
                    return GatewayResponse.json(405, jsonObject(mapOf(
                            "status", "error",
                            "message", "method not allowed"
                    )));
                }
                return GatewayResponse.ok(app.replayByPath(exchange.getRequestURI().getPath()));
            }
        }));
        server.createContext("/", new ConsoleIndexHandler(app.projectRoot));
        server.createContext("/console", new ConsoleIndexHandler(app.projectRoot));
        server.createContext("/assets/app.js", new StaticFileHandler(new File(app.projectRoot, "ui/app.js"), "application/javascript; charset=utf-8"));
        server.setExecutor(null);
        System.out.println("agent-gateway-basic listening on " + PORT);
        server.start();
    }

    private static void runSelfTest() {
        GatewayApp app = GatewayApp.fromConfig(defaultRoutes(), defaultProfiles(), fakeInvoker());

        GatewayResponse missingKey = app.invoke(new GatewayRequest("GET", "/gateway/v1/invoke",
                stringMapOf("service", "qa"), Collections.<String, String>emptyMap(), ""));
        assertEquals(401, missingKey.statusCode, "missing api key should fail");

        GatewayResponse badKey = app.invoke(new GatewayRequest("GET", "/gateway/v1/invoke",
                stringMapOf("service", "qa"), stringMapOf("x-api-key", "unknown"), ""));
        assertEquals(403, badKey.statusCode, "unknown key should fail");

        GatewayResponse blockedService = app.invoke(new GatewayRequest("GET", "/gateway/v1/invoke",
                stringMapOf("service", "pricing"), stringMapOf("x-api-key", "demo-key-partner"), ""));
        assertEquals(403, blockedService.statusCode, "policy should block restricted service");

        GatewayResponse ok = app.invoke(new GatewayRequest("POST", "/gateway/v1/invoke",
                stringMapOf("service", "qa"), stringMapOf("x-api-key", "demo-key-ops"), "{\"prompt\":\"test\"}"));
        assertEquals(200, ok.statusCode, "authorized request should pass");
        assertContains(ok.body, "\"provider\":\"agent-business-solution\"", "response should come from upstream");
        assertContains(ok.body, "\"scenario_code\":\"intelligent_qa\"", "qa should be mapped to L2 scenario contract");
        assertContains(ok.body, "\"question\":\"test\"", "qa prompt should be mapped to input.question");
        GatewayResponse compliance = app.invoke(new GatewayRequest("POST", "/gateway/v1/invoke",
                stringMapOf("service", "compliance"), stringMapOf("x-api-key", "demo-key-ops"), "{\"document\":\"rule text\"}"));
        assertEquals(200, compliance.statusCode, "compliance request should pass");
        assertContains(compliance.body, "\"capability_code\":\"structured_extraction\"", "compliance should be mapped to L3 capability contract");
        assertContains(compliance.body, "\"document\":\"rule text\"", "compliance should be mapped to input.document");
        GatewayResponse pricing = app.invoke(new GatewayRequest("POST", "/gateway/v1/invoke",
                stringMapOf("service", "pricing"), stringMapOf("x-api-key", "demo-key-ops"), "{\"prompt\":\"price summary\"}"));
        assertEquals(200, pricing.statusCode, "pricing request should pass");
        assertContains(pricing.body, "\"task_type\":\"pricing_inference\"", "pricing should be mapped to L4 runtime contract");
        assertContains(pricing.body, "\"payload\":\"price summary\"", "pricing prompt should be mapped to input.payload");

        GatewayApp limitApp = GatewayApp.fromConfig(
                defaultRoutes(),
                Collections.singletonList(new ClientProfile("limited", "demo-key-limited", 1, setOf("qa"), Collections.<String, Integer>emptyMap())),
                fakeInvoker()
        );
        GatewayResponse first = limitApp.invoke(new GatewayRequest("GET", "/gateway/v1/invoke",
                stringMapOf("service", "qa"), stringMapOf("x-api-key", "demo-key-limited"), ""));
        GatewayResponse second = limitApp.invoke(new GatewayRequest("GET", "/gateway/v1/invoke",
                stringMapOf("service", "qa"), stringMapOf("x-api-key", "demo-key-limited"), ""));
        assertEquals(200, first.statusCode, "first limited request should pass");
        assertEquals(429, second.statusCode, "second limited request should be throttled");

        Map<String, Integer> serviceQuotas = new LinkedHashMap<String, Integer>();
        serviceQuotas.put("qa", 1);
        GatewayApp splitQuotaApp = GatewayApp.fromConfig(
                defaultRoutes(),
                Collections.singletonList(new ClientProfile("split", "demo-key-split", 5, setOf("qa", "pricing"), serviceQuotas)),
                fakeInvoker()
        );
        GatewayResponse splitQa = splitQuotaApp.invoke(new GatewayRequest("GET", "/gateway/v1/invoke",
                stringMapOf("service", "qa"), stringMapOf("x-api-key", "demo-key-split"), ""));
        GatewayResponse splitQaDenied = splitQuotaApp.invoke(new GatewayRequest("GET", "/gateway/v1/invoke",
                stringMapOf("service", "qa"), stringMapOf("x-api-key", "demo-key-split"), ""));
        GatewayResponse splitPricing = splitQuotaApp.invoke(new GatewayRequest("GET", "/gateway/v1/invoke",
                stringMapOf("service", "pricing"), stringMapOf("x-api-key", "demo-key-split"), ""));
        assertEquals(200, splitQa.statusCode, "service-specific first qa request should pass");
        assertEquals(429, splitQaDenied.statusCode, "service-specific qa quota should be enforced");
        assertEquals(200, splitPricing.statusCode, "pricing should use its own quota bucket");

        String config = app.configJson();
        assertContains(config, "\"service\":\"qa\"", "config endpoint should expose routes");
        assertContains(config, "\"client\":\"ops\"", "config endpoint should expose clients");
        String audits = app.auditJson();
        assertContains(audits, "\"decision\":\"ALLOW\"", "audit endpoint should expose persisted entries");
        String upstreams = app.upstreamsJson();
        assertContains(upstreams, "\"service\":\"qa\"", "upstream endpoint should expose services");
        String overview = app.overviewJson();
        assertContains(overview, "\"metrics\":", "overview endpoint should embed metrics");
        assertContains(overview, "\"upstreams\":", "overview endpoint should embed upstreams");
        assertContains(overview, "\"contract_transformations\":", "overview endpoint should expose contract mapping stats");
        assertContains(overview, "\"contract_error_codes\":", "overview endpoint should expose contract error code stats");
        assertContains(app.layersJson(), "\"layer\":\"L2\"", "layers endpoint should expose downstream layers");
        assertContains(app.l2ScenariosJson(), "\"items\":", "l2 scenarios response should stay machine-readable");
        assertContains(app.l3CapabilitiesJson(), "\"stats\":", "l3 capabilities should expose usage stats");
        assertContains(app.l4RuntimeJson(), "\"status\":", "l4 runtime response should stay machine-readable");
        assertContains(app.l5KnowledgeJson(), "\"status\":", "l5 knowledge response should stay machine-readable");
        assertContains(app.l6ModelsJson(), "\"status\":", "l6 models response should stay machine-readable");
        assertContains(app.l7PlatformJson(), "\"service\":\"agent-platform-foundation\"", "l7 platform endpoint should be available");
        String debugRequest = app.debugRequestJson(new GatewayRequest(
                "POST",
                "/debug/request",
                stringMapOf("service", "qa"),
                stringMapOf("x-api-key", "demo-key-ops"),
                "{\"prompt\":\"debug me\"}"
        ));
        assertContains(debugRequest, "\"target_contract\":\"L2.intelligent_qa\"", "debug request should expose target contract");
        assertContains(debugRequest, "\"question\":\"debug me\"", "debug request should expose transformed payload");
        assertContains(debugRequest, "\"request_id\":\"", "debug request should include a request id in transformed payload");
        String reload = app.reloadWithConfig(new GatewayDiskConfig(
                defaultRoutes(),
                defaultProfiles(),
                new File(projectRoot(), "data/audits.log"),
                new File(projectRoot(), "data/metrics.log")
        ));
        assertContains(reload, "\"status\":\"ok\"", "reload should succeed");
        String metricsHistory = app.metricsHistoryJson();
        assertContains(metricsHistory, "\"items\":", "metrics history should be exposed");
    }

    private static UpstreamInvoker fakeInvoker() {
        return new UpstreamInvoker() {
            @Override
            public GatewayResponse invoke(RouteConfig route, GatewayRequest request) {
                return GatewayResponse.ok(jsonObject(mapOf(
                        "status", "ok",
                        "provider", route.upstreamName,
                        "upstream_url", route.invokeUrl,
                        "request_method", request.method,
                        "payload", buildUpstreamPayload(route, request)
                )));
            }

            @Override
            public boolean checkHealth(RouteConfig route) {
                return true;
            }
        };
    }

    private static List<ClientProfile> defaultProfiles() {
        return Arrays.asList(
                new ClientProfile("ops", "demo-key-ops", 60, setOf("qa", "compliance", "pricing"), Collections.<String, Integer>emptyMap()),
                new ClientProfile("business", "demo-key-business", 30, setOf("qa", "compliance"), Collections.<String, Integer>emptyMap()),
                new ClientProfile("partner", "demo-key-partner", 10, setOf("qa"), Collections.<String, Integer>emptyMap())
        );
    }

    private static Map<String, RouteConfig> defaultRoutes() {
        Map<String, RouteConfig> routes = new LinkedHashMap<String, RouteConfig>();
        routes.put("qa", new RouteConfig("qa", "agent-business-solution", "http://127.0.0.1:8002/invoke", "http://127.0.0.1:8002/health"));
        routes.put("compliance", new RouteConfig("compliance", "atomic-ai-engine", "http://127.0.0.1:8003/invoke", "http://127.0.0.1:8003/health"));
        routes.put("pricing", new RouteConfig("pricing", "agent-model-runtime", "http://127.0.0.1:8081/invoke", "http://127.0.0.1:8081/health"));
        return routes;
    }

    private static void assertEquals(int expected, int actual, String message) {
        if (expected != actual) {
            throw new IllegalStateException(message + ": expected " + expected + " but got " + actual);
        }
    }

    private static void assertContains(String value, String expectedFragment, String message) {
        if (value == null || value.indexOf(expectedFragment) < 0) {
            throw new IllegalStateException(message + ": missing fragment " + expectedFragment);
        }
    }

    private static final class GatewayApp {
        private volatile GatewayConfigSnapshot configSnapshot;
        private final Map<String, RateWindow> windowsByQuotaKey = new ConcurrentHashMap<String, RateWindow>();
        private final Map<String, ServiceMetrics> metricsByService = new ConcurrentHashMap<String, ServiceMetrics>();
        private final Map<String, Integer> contractTransformCounts = new ConcurrentHashMap<String, Integer>();
        private final Map<String, CircuitBreakerState> circuitStates = new ConcurrentHashMap<String, CircuitBreakerState>();
        private final Deque<AuditEvent> auditEvents = new ArrayDeque<AuditEvent>();
        private final File projectRoot;
        private final File auditLogFile;
        private final File metricsLogFile;
        private final File traceLogFile;
        private final UpstreamInvoker upstreamInvoker;

        private GatewayApp(File projectRoot, GatewayConfigSnapshot configSnapshot, File auditLogFile, File metricsLogFile, File traceLogFile, UpstreamInvoker upstreamInvoker) {
            this.projectRoot = projectRoot;
            this.configSnapshot = configSnapshot;
            this.auditLogFile = auditLogFile;
            this.metricsLogFile = metricsLogFile;
            this.traceLogFile = traceLogFile;
            this.upstreamInvoker = upstreamInvoker;
        }

        static GatewayApp fromDisk(UpstreamInvoker upstreamInvoker) throws IOException {
            File root = projectRoot();
            GatewayDiskConfig config = GatewayDiskConfig.load(root);
            return fromConfig(root, config.routesByService, config.clientProfiles, config.auditLogFile, config.metricsLogFile, new File(root, "data/traces.log"), upstreamInvoker);
        }

        static GatewayApp fromConfig(Map<String, RouteConfig> routesByService, List<ClientProfile> profiles, UpstreamInvoker upstreamInvoker) {
            File root = projectRoot();
            return fromConfig(root, routesByService, profiles, new File(root, "data/audits.log"), new File(root, "data/metrics.log"), new File(root, "data/traces.log"), upstreamInvoker);
        }

        static GatewayApp fromConfig(Map<String, RouteConfig> routesByService, List<ClientProfile> profiles, File auditLogFile, UpstreamInvoker upstreamInvoker) {
            return fromConfig(projectRoot(), routesByService, profiles, auditLogFile, new File(projectRoot(), "data/metrics.log"), new File(projectRoot(), "data/traces.log"), upstreamInvoker);
        }

        static GatewayApp fromConfig(File projectRoot, Map<String, RouteConfig> routesByService, List<ClientProfile> profiles, File auditLogFile, File metricsLogFile, File traceLogFile, UpstreamInvoker upstreamInvoker) {
            return new GatewayApp(projectRoot, GatewayConfigSnapshot.from(routesByService, profiles), auditLogFile, metricsLogFile, traceLogFile, upstreamInvoker);
        }

        GatewayResponse invoke(GatewayRequest request) {
            Instant startedAt = Instant.now();
            String service = request.queryParams.containsKey("service") ? request.queryParams.get("service") : "qa";
            String apiKey = request.header("x-api-key");
            String clientName = "anonymous";
            GatewayConfigSnapshot snapshot = configSnapshot;

            if (apiKey == null || apiKey.trim().isEmpty()) {
                recordAudit(clientName, service, "DENY", "missing_api_key", startedAt, 401);
                return GatewayResponse.json(401, jsonObject(mapOf(
                        "status", "error",
                        "message", "missing x-api-key"
                )));
            }

            ClientProfile profile = snapshot.profilesByApiKey.get(apiKey);
            if (profile == null) {
                recordAudit(clientName, service, "DENY", "unknown_api_key", startedAt, 403);
                return GatewayResponse.json(403, jsonObject(mapOf(
                        "status", "error",
                        "message", "invalid api key"
                )));
            }

            clientName = profile.name;
            if (!profile.allowedServices.contains(service)) {
                recordAudit(clientName, service, "DENY", "policy_block", startedAt, 403);
                incrementMetric(service, false, startedAt);
                return GatewayResponse.json(403, jsonObject(mapOf(
                        "status", "error",
                        "message", "service not allowed for client",
                        "service", service
                )));
            }

            RouteConfig route = snapshot.routesByService.get(service);
            if (route == null) {
                recordAudit(clientName, service, "DENY", "route_not_found", startedAt, 404);
                incrementMetric(service, false, startedAt);
                return GatewayResponse.json(404, jsonObject(mapOf(
                        "status", "error",
                        "message", "unknown service",
                        "service", service
                )));
            }

            String quotaKey = clientName + ":" + service;
            RateWindow window = windowsByQuotaKey.containsKey(quotaKey) ? windowsByQuotaKey.get(quotaKey) : new RateWindow();
            windowsByQuotaKey.put(quotaKey, window);
            int requestsPerMinute = profile.quotaFor(service);
            if (!window.allow(requestsPerMinute)) {
                recordAudit(clientName, service, "DENY", "rate_limited", startedAt, 429);
                incrementMetric(service, false, startedAt);
                return GatewayResponse.json(429, jsonObject(mapOf(
                        "status", "error",
                        "message", "quota exceeded",
                        "quota_per_minute", requestsPerMinute,
                        "quota_scope", quotaKey
                )));
            }

            CircuitBreakerState circuit = circuitStates.containsKey(service) ? circuitStates.get(service) : new CircuitBreakerState();
            circuitStates.put(service, circuit);
            if (circuit.isOpen()) {
                recordAudit(clientName, service, "DENY", "circuit_open", startedAt, 503);
                incrementMetric(service, false, startedAt);
                return GatewayResponse.json(503, jsonObject(mapOf(
                        "status", "error",
                        "message", "upstream circuit open",
                        "service", service,
                        "retry_after_ms", circuit.remainingOpenMillis()
                )));
            }

            recordContractTransform(service);
            String transformedPayload = buildUpstreamPayload(route, request);
            GatewayResponse upstreamResponse = upstreamInvoker.invoke(route, request);
            int statusCode = upstreamResponse.statusCode;
            boolean authorized = statusCode >= 200 && statusCode < 300;
            if (authorized) {
                circuit.recordSuccess();
            } else {
                circuit.recordFailure();
            }
            recordAudit(clientName, service, authorized ? "ALLOW" : "DENY",
                    authorized ? "forwarded" : "upstream_error", startedAt, statusCode);
            incrementMetric(service, authorized, startedAt);
            String responseBody = jsonObject(mapOf(
                    "status", authorized ? "ok" : "error",
                    "routed_service", service,
                    "client", clientName,
                    "upstream", route.upstreamName,
                    "upstream_url", route.invokeUrl,
                    "duration_ms", Duration.between(startedAt, Instant.now()).toMillis(),
                    "upstream_response", upstreamResponse.body
            ));
            appendTrace(new TraceEvent(
                    Instant.now(),
                    extractJsonString(transformedPayload, "request_id"),
                    service,
                    contractTarget(service),
                    request.headers,
                    request.body,
                    transformedPayload,
                    responseBody
            ));
            return GatewayResponse.json(statusCode, responseBody);
        }

        String metricsJson() {
            List<String> services = new ArrayList<String>();
            for (Map.Entry<String, ServiceMetrics> entry : metricsByService.entrySet()) {
                ServiceMetrics metrics = entry.getValue();
                services.add(jsonObject(mapOf(
                        "service", entry.getKey(),
                        "authorized_requests", metrics.authorizedRequests,
                        "rejected_requests", metrics.rejectedRequests,
                        "average_duration_ms", metrics.averageDurationMs()
                )));
            }
            return jsonObject(mapOf("services", "[" + String.join(",", services) + "]"));
        }

        String metricsHistoryJson() {
            List<String> items = readRecentMetricEntries();
            return jsonObject(mapOf(
                    "summary", metricsSummaryJson(items),
                    "items", "[" + String.join(",", items) + "]"
            ));
        }

        String auditJson() {
            return jsonObject(mapOf("items", "[" + String.join(",", readRecentAuditEntries()) + "]"));
        }

        String configJson() {
            List<String> routes = new ArrayList<String>();
            for (RouteConfig route : configSnapshot.routesByService.values()) {
                routes.add(jsonObject(mapOf(
                        "service", route.service,
                        "upstream", route.upstreamName,
                        "invoke_url", route.invokeUrl,
                        "health_url", route.healthUrl
                )));
            }

            List<String> clients = new ArrayList<String>();
            for (ClientProfile profile : configSnapshot.profilesByApiKey.values()) {
                clients.add(jsonObject(mapOf(
                        "client", profile.name,
                        "requests_per_minute", profile.requestsPerMinute,
                        "allowed_services", jsonArray(profile.allowedServices),
                        "service_quotas", jsonObject(integerMapToObjectMap(profile.serviceRequestsPerMinute))
                )));
            }
            return jsonObject(mapOf(
                    "routes", "[" + String.join(",", routes) + "]",
                    "clients", "[" + String.join(",", clients) + "]"
            ));
        }

        String upstreamsJson() {
            List<String> items = new ArrayList<String>();
            for (RouteConfig route : configSnapshot.routesByService.values()) {
                CircuitBreakerState state = circuitStates.containsKey(route.service)
                        ? circuitStates.get(route.service)
                        : new CircuitBreakerState();
                circuitStates.put(route.service, state);
                items.add(jsonObject(mapOf(
                        "service", route.service,
                        "upstream", route.upstreamName,
                        "health_url", route.healthUrl,
                        "healthy", upstreamInvoker.checkHealth(route),
                        "circuit_state", state.isOpen() ? "OPEN" : "CLOSED",
                        "consecutive_failures", state.consecutiveFailures,
                        "open_until", state.openUntil == null ? "" : state.openUntil.toString()
                )));
            }
            return jsonObject(mapOf("items", "[" + String.join(",", items) + "]"));
        }

        String overviewJson() {
            List<String> recentAuditEntries = readRecentAuditEntries();
            return jsonObject(mapOf(
                    "generated_at", Instant.now().toString(),
                    "metrics", metricsJson(),
                    "metrics_history", metricsHistoryJson(),
                    "upstreams", upstreamsJson(),
                    "config", configJson(),
                    "contract_transformations", contractTransformationsJson(),
                    "contract_error_codes", contractErrorCodesJson(recentAuditEntries),
                    "audit_summary", auditSummaryJson(recentAuditEntries),
                    "recent_audits", jsonObject(mapOf("items", "[" + String.join(",", recentAuditEntries) + "]"))
            ));
        }

        String layersJson() {
            List<String> items = new ArrayList<String>();
            items.add(layerItem("L1", "agent-gateway-basic", true, "embedded", "/ops/overview"));
            items.add(layerItem("L2", "agent-business-solution", httpOk("http://127.0.0.1:8002/health"), "http", "/ops/l2/scenarios"));
            items.add(layerItem("L3", "atomic-ai-engine", httpOk("http://127.0.0.1:8003/health"), "http", "/ops/l3/capabilities"));
            items.add(layerItem("L4", "agent-model-runtime", httpOk("http://127.0.0.1:8081/health"), "http", "/ops/l4/runtime"));
            items.add(layerItem("L5", "agent-knowledge-ops", httpOk("http://127.0.0.1:8004/health"), "http", "http://127.0.0.1:8004/health"));
            items.add(layerItem("L6", "agent-model-hub", httpOk("http://127.0.0.1:8005/health"), "http", "http://127.0.0.1:8005/health"));
            items.add(layerItem("L7", "agent-platform-foundation", scriptOk(new File(projectRoot, "../agent-platform-foundation/scripts/healthcheck.sh")), "script", "../agent-platform-foundation/scripts/healthcheck.sh"));
            return jsonObject(mapOf("items", "[" + String.join(",", items) + "]"));
        }

        String l2ScenariosJson() {
            return fetchJson("http://127.0.0.1:8002/scenarios", jsonObject(mapOf(
                    "status", "error",
                    "message", "l2 unavailable",
                    "items", "[]"
            )));
        }

        String l3CapabilitiesJson() {
            String capabilitiesJson = fetchJson("http://127.0.0.1:8003/capabilities", jsonObject(mapOf(
                    "status", "error",
                    "message", "l3 unavailable",
                    "items", "[]"
            )));
            List<String> recentAuditEntries = readRecentAuditEntries();
            int callCount = 0;
            int rejectCount = 0;
            Map<String, Integer> errorCodes = new LinkedHashMap<String, Integer>();
            for (String auditEntry : recentAuditEntries) {
                if (!"compliance".equals(extractJsonString(auditEntry, "service"))) {
                    continue;
                }
                if ("ALLOW".equals(extractJsonString(auditEntry, "decision"))) {
                    callCount += 1;
                } else {
                    rejectCount += 1;
                    String reason = extractJsonString(auditEntry, "reason");
                    if (!reason.isEmpty()) {
                        Integer count = errorCodes.containsKey(reason) ? errorCodes.get(reason) : 0;
                        errorCodes.put(reason, count + 1);
                    }
                }
            }
            String itemsJson = extractRawJson(capabilitiesJson, "items");
            return jsonObject(mapOf(
                    "items", itemsJson,
                    "stats", jsonObject(mapOf(
                            "call_count", callCount,
                            "rejected_count", rejectCount,
                            "error_codes", jsonObject(integerMapToObjectMap(errorCodes))
                    ))
            ));
        }

        String l4RuntimeJson() {
            return fetchJson("http://127.0.0.1:8081/ops/runtime", jsonObject(mapOf(
                    "status", "error",
                    "message", "l4 unavailable"
            )));
        }

        String l5KnowledgeJson() {
            return fetchJson("http://127.0.0.1:8004/ops/knowledge", jsonObject(mapOf(
                    "status", "error",
                    "message", "l5 unavailable"
            )));
        }

        String l6ModelsJson() {
            return fetchJson("http://127.0.0.1:8005/ops/models", jsonObject(mapOf(
                    "status", "error",
                    "message", "l6 unavailable"
            )));
        }

        String l7PlatformJson() {
            File root = new File(projectRoot, "../agent-platform-foundation");
            return jsonObject(mapOf(
                    "status", "UP",
                    "service", "agent-platform-foundation",
                    "healthcheck", scriptOk(new File(root, "scripts/healthcheck.sh")),
                    "build_ready", scriptOk(new File(root, "scripts/build.sh")),
                    "test_ready", scriptOk(new File(root, "scripts/test.sh")),
                    "run_ready", scriptOk(new File(root, "scripts/run.sh"))
            ));
        }

        String debugRequestJson(GatewayRequest request) {
            String service = request.queryParams.containsKey("service") ? request.queryParams.get("service") : "qa";
            RouteConfig route = configSnapshot.routesByService.get(service);
            if (route == null) {
                return jsonObject(mapOf(
                        "status", "error",
                        "message", "unknown service",
                        "service", service
                ));
            }
            GatewayRequest invokeRequest = new GatewayRequest(
                    "POST",
                    "/gateway/v1/invoke",
                    stringMapOf("service", service),
                    request.headers,
                    request.body
            );
            String transformedPayload = buildUpstreamPayload(route, invokeRequest);
            GatewayResponse gatewayResponse = invoke(invokeRequest);
            return jsonObject(mapOf(
                    "service", service,
                    "target_contract", contractTarget(service),
                    "transformed_request", transformedPayload,
                    "gateway_response", gatewayResponse.body
            ));
        }

        String traceByPath(String path) {
            String requestId = path.substring("/debug/trace/".length());
            if (requestId.trim().isEmpty()) {
                return jsonObject(mapOf("status", "error", "message", "missing request id"));
            }
            String trace = findTrace(requestId);
            if (trace == null) {
                return jsonObject(mapOf("status", "error", "message", "trace not found", "request_id", requestId));
            }
            return trace;
        }

        String replayByPath(String path) {
            String requestId = path.substring("/debug/replay/".length());
            if (requestId.trim().isEmpty()) {
                return jsonObject(mapOf("status", "error", "message", "missing request id"));
            }
            String trace = findTrace(requestId);
            if (trace == null) {
                return jsonObject(mapOf("status", "error", "message", "trace not found", "request_id", requestId));
            }
            String service = extractJsonString(trace, "service");
            String originalBody = extractRawJson(trace, "original_body");
            Map<String, String> headers = stringMapOf(
                    "x-api-key", "demo-key-ops",
                    "x-tenant-id", extractJsonString(trace, "tenant_id"),
                    "x-operator-id", extractJsonString(trace, "operator_id")
            );
            GatewayResponse replay = invoke(new GatewayRequest("POST", "/gateway/v1/invoke", stringMapOf("service", service), headers, originalBody));
            return jsonObject(mapOf(
                    "status", "ok",
                    "request_id", requestId,
                    "service", service,
                    "replay_response", replay.body
            ));
        }

        synchronized String reloadJson() throws IOException {
            return reloadWithConfig(GatewayDiskConfig.load(projectRoot));
        }

        synchronized String reloadWithConfig(GatewayDiskConfig diskConfig) {
            configSnapshot = GatewayConfigSnapshot.from(diskConfig.routesByService, diskConfig.clientProfiles);
            return jsonObject(mapOf(
                    "status", "ok",
                    "reloaded_at", Instant.now().toString(),
                    "route_count", configSnapshot.routesByService.size(),
                    "client_count", configSnapshot.profilesByApiKey.size()
            ));
        }

        private void incrementMetric(String service, boolean authorized, Instant startedAt) {
            ServiceMetrics metrics = metricsByService.containsKey(service) ? metricsByService.get(service) : new ServiceMetrics();
            metricsByService.put(service, metrics);
            long durationMs = Duration.between(startedAt, Instant.now()).toMillis();
            metrics.record(authorized, durationMs);
            appendMetric(new MetricEvent(Instant.now(), service, authorized, durationMs));
        }

        private void recordAudit(String client, String service, String decision, String reason, Instant timestamp, int statusCode) {
            AuditEvent event = new AuditEvent(timestamp, client, service, decision, reason, statusCode);
            synchronized (auditEvents) {
                auditEvents.addFirst(event);
                while (auditEvents.size() > 100) {
                    auditEvents.removeLast();
                }
            }
            appendAudit(event);
        }

        private void recordContractTransform(String service) {
            Integer count = contractTransformCounts.containsKey(service) ? contractTransformCounts.get(service) : 0;
            contractTransformCounts.put(service, count + 1);
        }

        private String layerItem(String layer, String project, boolean healthy, String healthMode, String target) {
            return jsonObject(mapOf(
                    "layer", layer,
                    "project", project,
                    "healthy", healthy,
                    "status", healthy ? "UP" : "DOWN",
                    "health_mode", healthMode,
                    "target", target
            ));
        }

        private boolean httpOk(String url) {
            HttpURLConnection connection = null;
            try {
                connection = (HttpURLConnection) new URL(url).openConnection();
                connection.setRequestMethod("GET");
                connection.setConnectTimeout(1000);
                connection.setReadTimeout(1000);
                int statusCode = connection.getResponseCode();
                return statusCode >= 200 && statusCode < 300;
            } catch (IOException exception) {
                return false;
            } finally {
                if (connection != null) {
                    connection.disconnect();
                }
            }
        }

        private boolean scriptOk(File scriptFile) {
            if (!scriptFile.exists()) {
                return false;
            }
            Process process = null;
            try {
                process = new ProcessBuilder(scriptFile.getAbsolutePath()).start();
                int exitCode = process.waitFor();
                return exitCode == 0;
            } catch (IOException exception) {
                return false;
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return false;
            } finally {
                if (process != null) {
                    process.destroy();
                }
            }
        }

        private String fetchJson(String url, String fallbackJson) {
            HttpURLConnection connection = null;
            try {
                connection = (HttpURLConnection) new URL(url).openConnection();
                connection.setRequestMethod("GET");
                connection.setConnectTimeout(1000);
                connection.setReadTimeout(1500);
                int statusCode = connection.getResponseCode();
                InputStream inputStream = statusCode >= 400 ? connection.getErrorStream() : connection.getInputStream();
                String responseBody = inputStream == null ? "" : readBody(inputStream);
                if (responseBody == null || responseBody.trim().isEmpty()) {
                    return fallbackJson;
                }
                return responseBody;
            } catch (IOException exception) {
                return fallbackJson;
            } finally {
                if (connection != null) {
                    connection.disconnect();
                }
            }
        }

        private void appendTrace(TraceEvent event) {
            File parent = traceLogFile.getParentFile();
            if (parent != null && !parent.exists()) {
                parent.mkdirs();
            }
            FileWriter writer = null;
            try {
                writer = new FileWriter(traceLogFile, true);
                writer.write(event.toJson());
                writer.write("\n");
            } catch (IOException ignored) {
            } finally {
                if (writer != null) {
                    try {
                        writer.close();
                    } catch (IOException ignored) {
                    }
                }
            }
        }

        private String findTrace(String requestId) {
            if (!traceLogFile.exists()) {
                return null;
            }
            BufferedReader reader = null;
            String match = null;
            try {
                reader = new BufferedReader(new FileReader(traceLogFile));
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.indexOf("\"request_id\":\"" + escapeJson(requestId) + "\"") >= 0) {
                        match = line;
                    }
                }
            } catch (IOException ignored) {
                return null;
            } finally {
                if (reader != null) {
                    try {
                        reader.close();
                    } catch (IOException ignored) {
                    }
                }
            }
            return match;
        }

        private String contractTransformationsJson() {
            List<String> items = new ArrayList<String>();
            for (RouteConfig route : configSnapshot.routesByService.values()) {
                items.add(jsonObject(mapOf(
                        "service", route.service,
                        "target", contractTarget(route.service),
                        "transform_count", contractTransformCounts.containsKey(route.service) ? contractTransformCounts.get(route.service) : 0
                )));
            }
            return jsonObject(mapOf("items", "[" + String.join(",", items) + "]"));
        }

        private String contractErrorCodesJson(List<String> recentAuditEntries) {
            List<String> items = new ArrayList<String>();
            for (RouteConfig route : configSnapshot.routesByService.values()) {
                Map<String, Integer> errorCodes = new LinkedHashMap<String, Integer>();
                for (String auditEntry : recentAuditEntries) {
                    if (!route.service.equals(extractJsonString(auditEntry, "service"))) {
                        continue;
                    }
                    if (!"DENY".equals(extractJsonString(auditEntry, "decision"))) {
                        continue;
                    }
                    String reason = extractJsonString(auditEntry, "reason");
                    if (reason.isEmpty()) {
                        continue;
                    }
                    Integer count = errorCodes.containsKey(reason) ? errorCodes.get(reason) : 0;
                    errorCodes.put(reason, count + 1);
                }
                items.add(jsonObject(mapOf(
                        "service", route.service,
                        "target", contractTarget(route.service),
                        "error_codes", jsonObject(integerMapToObjectMap(errorCodes))
                )));
            }
            return jsonObject(mapOf("items", "[" + String.join(",", items) + "]"));
        }

        private void appendAudit(AuditEvent event) {
            File parent = auditLogFile.getParentFile();
            if (parent != null && !parent.exists()) {
                parent.mkdirs();
            }
            FileWriter writer = null;
            try {
                writer = new FileWriter(auditLogFile, true);
                writer.write(event.toJson());
                writer.write("\n");
            } catch (IOException ignored) {
                // Keep request path available even if audit persistence fails.
            } finally {
                if (writer != null) {
                    try {
                        writer.close();
                    } catch (IOException ignored) {
                    }
                }
            }
        }

        private List<String> readRecentAuditEntries() {
            if (!auditLogFile.exists()) {
                return new ArrayList<String>();
            }
            Deque<String> lines = new ArrayDeque<String>();
            BufferedReader reader = null;
            try {
                reader = new BufferedReader(new FileReader(auditLogFile));
                String line;
                while ((line = reader.readLine()) != null) {
                    if (!line.trim().isEmpty()) {
                        lines.addFirst(line);
                        while (lines.size() > 100) {
                            lines.removeLast();
                        }
                    }
                }
            } catch (IOException ignored) {
                return new ArrayList<String>();
            } finally {
                if (reader != null) {
                    try {
                        reader.close();
                    } catch (IOException ignored) {
                    }
                }
            }
            return new ArrayList<String>(lines);
        }

        private void appendMetric(MetricEvent event) {
            File parent = metricsLogFile.getParentFile();
            if (parent != null && !parent.exists()) {
                parent.mkdirs();
            }
            FileWriter writer = null;
            try {
                writer = new FileWriter(metricsLogFile, true);
                writer.write(event.toJson());
                writer.write("\n");
            } catch (IOException ignored) {
            } finally {
                if (writer != null) {
                    try {
                        writer.close();
                    } catch (IOException ignored) {
                    }
                }
            }
        }

        private List<String> readRecentMetricEntries() {
            if (!metricsLogFile.exists()) {
                return new ArrayList<String>();
            }
            Deque<String> lines = new ArrayDeque<String>();
            BufferedReader reader = null;
            try {
                reader = new BufferedReader(new FileReader(metricsLogFile));
                String line;
                while ((line = reader.readLine()) != null) {
                    if (!line.trim().isEmpty()) {
                        lines.addFirst(line);
                        while (lines.size() > 100) {
                            lines.removeLast();
                        }
                    }
                }
            } catch (IOException ignored) {
                return new ArrayList<String>();
            } finally {
                if (reader != null) {
                    try {
                        reader.close();
                    } catch (IOException ignored) {
                    }
                }
            }
            return new ArrayList<String>(lines);
        }

        private String auditSummaryJson(List<String> auditEntries) {
            int allowCount = 0;
            int denyCount = 0;
            for (String entry : auditEntries) {
                if (entry.indexOf("\"decision\":\"ALLOW\"") >= 0) {
                    allowCount += 1;
                } else if (entry.indexOf("\"decision\":\"DENY\"") >= 0) {
                    denyCount += 1;
                }
            }
            return jsonObject(mapOf(
                    "total", auditEntries.size(),
                    "allow", allowCount,
                    "deny", denyCount
            ));
        }

        private String metricsSummaryJson(List<String> metricEntries) {
            int successCount = 0;
            int failureCount = 0;
            for (String entry : metricEntries) {
                if (entry.indexOf("\"authorized\":true") >= 0) {
                    successCount += 1;
                } else if (entry.indexOf("\"authorized\":false") >= 0) {
                    failureCount += 1;
                }
            }
            return jsonObject(mapOf(
                    "total", metricEntries.size(),
                    "authorized", successCount,
                    "rejected", failureCount
            ));
        }
    }

    private static final class GatewayDiskConfig {
        private final Map<String, RouteConfig> routesByService;
        private final List<ClientProfile> clientProfiles;
        private final File auditLogFile;
        private final File metricsLogFile;

        private GatewayDiskConfig(Map<String, RouteConfig> routesByService, List<ClientProfile> clientProfiles, File auditLogFile, File metricsLogFile) {
            this.routesByService = routesByService;
            this.clientProfiles = clientProfiles;
            this.auditLogFile = auditLogFile;
            this.metricsLogFile = metricsLogFile;
        }

        static GatewayDiskConfig load(File projectRoot) throws IOException {
            Properties routeProps = loadProperties(new File(projectRoot, "config/routes.properties"));
            Properties clientProps = loadProperties(new File(projectRoot, "config/clients.properties"));
            return new GatewayDiskConfig(parseRoutes(routeProps), parseClients(clientProps), new File(projectRoot, "data/audits.log"), new File(projectRoot, "data/metrics.log"));
        }

        private static Properties loadProperties(File file) throws IOException {
            Properties properties = new Properties();
            InputStream inputStream = new FileInputStream(file);
            try {
                properties.load(inputStream);
            } finally {
                inputStream.close();
            }
            return properties;
        }

        private static Map<String, RouteConfig> parseRoutes(Properties properties) {
            Map<String, RouteConfig> routes = new LinkedHashMap<String, RouteConfig>();
            Set<String> services = new LinkedHashSet<String>();
            for (String key : properties.stringPropertyNames()) {
                if (key.startsWith("service.")) {
                    String[] parts = key.split("\\.");
                    if (parts.length >= 3) {
                        services.add(parts[1]);
                    }
                }
            }
            for (String service : services) {
                routes.put(service, new RouteConfig(
                        service,
                        required(properties, "service." + service + ".upstream_name"),
                        required(properties, "service." + service + ".invoke_url"),
                        required(properties, "service." + service + ".health_url")
                ));
            }
            return routes;
        }

        private static List<ClientProfile> parseClients(Properties properties) {
            List<ClientProfile> profiles = new ArrayList<ClientProfile>();
            Set<String> clients = new LinkedHashSet<String>();
            for (String key : properties.stringPropertyNames()) {
                if (key.startsWith("client.")) {
                    String[] parts = key.split("\\.");
                    if (parts.length >= 3) {
                        clients.add(parts[1]);
                    }
                }
            }
            for (String client : clients) {
                profiles.add(new ClientProfile(
                        client,
                        required(properties, "client." + client + ".api_key"),
                        Integer.parseInt(required(properties, "client." + client + ".requests_per_minute")),
                        csvToSet(required(properties, "client." + client + ".allowed_services")),
                        parseServiceQuotas(properties, client)
                ));
            }
            return profiles;
        }

        private static Map<String, Integer> parseServiceQuotas(Properties properties, String client) {
            Map<String, Integer> quotas = new LinkedHashMap<String, Integer>();
            String prefix = "client." + client + ".service.";
            for (String key : properties.stringPropertyNames()) {
                if (key.startsWith(prefix) && key.endsWith(".requests_per_minute")) {
                    String service = key.substring(prefix.length(), key.length() - ".requests_per_minute".length());
                    quotas.put(service, Integer.parseInt(required(properties, key)));
                }
            }
            return quotas;
        }

        private static String required(Properties properties, String key) {
            String value = properties.getProperty(key);
            if (value == null || value.trim().isEmpty()) {
                throw new IllegalStateException("missing config key: " + key);
            }
            return value.trim();
        }
    }

    private static final class GatewayConfigSnapshot {
        private final Map<String, RouteConfig> routesByService;
        private final Map<String, ClientProfile> profilesByApiKey;

        private GatewayConfigSnapshot(Map<String, RouteConfig> routesByService, Map<String, ClientProfile> profilesByApiKey) {
            this.routesByService = routesByService;
            this.profilesByApiKey = profilesByApiKey;
        }

        private static GatewayConfigSnapshot from(Map<String, RouteConfig> routesByService, List<ClientProfile> profiles) {
            Map<String, ClientProfile> profilesMap = new LinkedHashMap<String, ClientProfile>();
            for (ClientProfile profile : profiles) {
                profilesMap.put(profile.apiKey, profile);
            }
            return new GatewayConfigSnapshot(routesByService, profilesMap);
        }
    }

    private static final class HttpUpstreamInvoker implements UpstreamInvoker {
        @Override
        public GatewayResponse invoke(RouteConfig route, GatewayRequest request) {
            HttpURLConnection connection = null;
            try {
                URL url = new URL(route.invokeUrl);
                connection = (HttpURLConnection) url.openConnection();
                connection.setRequestMethod("POST");
                connection.setConnectTimeout(2000);
                connection.setReadTimeout(3000);
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                byte[] payload = buildUpstreamPayload(route, request).getBytes(StandardCharsets.UTF_8);
                OutputStream outputStream = connection.getOutputStream();
                outputStream.write(payload);
                outputStream.flush();
                outputStream.close();

                int statusCode = connection.getResponseCode();
                InputStream inputStream = statusCode >= 400 ? connection.getErrorStream() : connection.getInputStream();
                String responseBody = inputStream == null ? "" : readBody(inputStream);
                return GatewayResponse.json(statusCode, responseBody);
            } catch (IOException exception) {
                return GatewayResponse.json(502, jsonObject(mapOf(
                        "status", "error",
                        "message", "upstream unavailable",
                        "detail", exception.getMessage(),
                        "upstream", route.upstreamName
                )));
            } finally {
                if (connection != null) {
                    connection.disconnect();
                }
            }
        }

        @Override
        public boolean checkHealth(RouteConfig route) {
            HttpURLConnection connection = null;
            try {
                URL url = new URL(route.healthUrl);
                connection = (HttpURLConnection) url.openConnection();
                connection.setRequestMethod("GET");
                connection.setConnectTimeout(1000);
                connection.setReadTimeout(1000);
                int statusCode = connection.getResponseCode();
                return statusCode >= 200 && statusCode < 300;
            } catch (IOException exception) {
                return false;
            } finally {
                if (connection != null) {
                    connection.disconnect();
                }
            }
        }
    }

    private static String buildUpstreamPayload(RouteConfig route, GatewayRequest request) {
        if ("qa".equals(route.service)) {
            return qaScenarioPayload(request);
        }
        if ("compliance".equals(route.service)) {
            return complianceCapabilityPayload(request);
        }
        if ("pricing".equals(route.service)) {
            return pricingRuntimePayload(request);
        }
        if (request.body == null || request.body.trim().isEmpty()) {
            return jsonObject(mapOf("prompt", "", "document", ""));
        }
        return request.body;
    }

    private static String qaScenarioPayload(GatewayRequest request) {
        String requestId = extractJsonString(request.body, "request_id");
        if (requestId.isEmpty()) {
            requestId = "gw-" + UUID.randomUUID().toString();
        }
        String tenantId = extractJsonString(request.body, "tenant_id");
        if (tenantId.isEmpty()) {
            tenantId = headerOrDefault(request, "x-tenant-id", "gateway");
        }
        String operatorId = extractJsonString(request.body, "operator_id");
        if (operatorId.isEmpty()) {
            operatorId = headerOrDefault(request, "x-operator-id", "gateway");
        }
        String question = extractJsonString(request.body, "question");
        if (question.isEmpty()) {
            question = extractJsonString(request.body, "prompt");
        }
        String debugRaw = extractJsonString(request.body, "debug");
        boolean debug = "true".equalsIgnoreCase(debugRaw);
        return jsonObject(mapOf(
                "request_id", requestId,
                "scenario_code", "intelligent_qa",
                "tenant_id", tenantId,
                "operator_id", operatorId,
                "input", jsonObject(mapOf(
                        "question", question
                )),
                "context", jsonObject(mapOf(
                        "channel", "gateway",
                        "source_system", "agent-gateway-basic",
                        "service", "qa"
                )),
                "options", jsonObject(mapOf(
                        "debug", debug
                ))
        ));
    }

    private static String complianceCapabilityPayload(GatewayRequest request) {
        String requestId = extractJsonString(request.body, "request_id");
        if (requestId.isEmpty()) {
            requestId = "gw-" + UUID.randomUUID().toString();
        }
        String tenantId = extractJsonString(request.body, "tenant_id");
        if (tenantId.isEmpty()) {
            tenantId = headerOrDefault(request, "x-tenant-id", "gateway");
        }
        String operatorId = extractJsonString(request.body, "operator_id");
        if (operatorId.isEmpty()) {
            operatorId = headerOrDefault(request, "x-operator-id", "gateway");
        }
        String document = extractJsonString(request.body, "document");
        if (document.isEmpty()) {
            document = extractJsonString(request.body, "prompt");
        }
        String debugRaw = extractJsonString(request.body, "debug");
        boolean debug = "true".equalsIgnoreCase(debugRaw);
        return jsonObject(mapOf(
                "request_id", requestId,
                "capability_code", "structured_extraction",
                "tenant_id", tenantId,
                "operator_id", operatorId,
                "input", jsonObject(mapOf(
                        "document", document
                )),
                "context", jsonObject(mapOf(
                        "channel", "gateway",
                        "source_system", "agent-gateway-basic",
                        "service", "compliance"
                )),
                "options", jsonObject(mapOf(
                        "debug", debug
                ))
        ));
    }

    private static String pricingRuntimePayload(GatewayRequest request) {
        String requestId = extractJsonString(request.body, "request_id");
        if (requestId.isEmpty()) {
            requestId = "gw-" + UUID.randomUUID().toString();
        }
        String tenantId = extractJsonString(request.body, "tenant_id");
        if (tenantId.isEmpty()) {
            tenantId = headerOrDefault(request, "x-tenant-id", "gateway");
        }
        String operatorId = extractJsonString(request.body, "operator_id");
        if (operatorId.isEmpty()) {
            operatorId = headerOrDefault(request, "x-operator-id", "gateway");
        }
        String payload = extractJsonString(request.body, "payload");
        if (payload.isEmpty()) {
            payload = extractJsonString(request.body, "prompt");
        }
        String debugRaw = extractJsonString(request.body, "debug");
        boolean debug = "true".equalsIgnoreCase(debugRaw);
        return jsonObject(mapOf(
                "request_id", requestId,
                "task_type", "pricing_inference",
                "tenant_id", tenantId,
                "operator_id", operatorId,
                "input", jsonObject(mapOf(
                        "payload", payload
                )),
                "context", jsonObject(mapOf(
                        "channel", "gateway",
                        "source_system", "agent-gateway-basic",
                        "service", "pricing"
                )),
                "options", jsonObject(mapOf(
                        "debug", debug
                ))
        ));
    }

    private static String contractTarget(String service) {
        if ("qa".equals(service)) {
            return "L2.intelligent_qa";
        }
        if ("compliance".equals(service)) {
            return "L3.structured_extraction";
        }
        if ("pricing".equals(service)) {
            return "L4.pricing_inference";
        }
        return "raw";
    }

    private static String headerOrDefault(GatewayRequest request, String headerName, String defaultValue) {
        String value = request.header(headerName);
        if (value == null || value.trim().isEmpty()) {
            return defaultValue;
        }
        return value;
    }

    private static String extractJsonString(String body, String key) {
        if (body == null || body.trim().isEmpty()) {
            return "";
        }
        Pattern pattern = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*\"([^\"]*)\"");
        Matcher matcher = pattern.matcher(body);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return "";
    }

    private static String extractRawJson(String body, String key) {
        if (body == null || body.trim().isEmpty()) {
            return "{}";
        }
        String anchor = "\"" + key + "\":";
        int start = body.indexOf(anchor);
        if (start < 0) {
            return "{}";
        }
        int valueStart = start + anchor.length();
        while (valueStart < body.length() && Character.isWhitespace(body.charAt(valueStart))) {
            valueStart += 1;
        }
        if (valueStart >= body.length()) {
            return "{}";
        }
        char first = body.charAt(valueStart);
        if (first == '"') {
            int end = body.indexOf("\"", valueStart + 1);
            while (end > 0 && body.charAt(end - 1) == '\\') {
                end = body.indexOf("\"", end + 1);
            }
            return end < 0 ? "{}" : body.substring(valueStart, end + 1);
        }
        if (first != '{' && first != '[') {
            int end = body.indexOf(",", valueStart);
            if (end < 0) {
                end = body.indexOf("}", valueStart);
            }
            return end < 0 ? "{}" : body.substring(valueStart, end).trim();
        }
        int depth = 0;
        for (int i = valueStart; i < body.length(); i++) {
            char c = body.charAt(i);
            if (c == '{' || c == '[') {
                depth += 1;
            } else if (c == '}' || c == ']') {
                depth -= 1;
                if (depth == 0) {
                    return body.substring(valueStart, i + 1);
                }
            }
        }
        return "{}";
    }

    private static final class InvokeHandler implements HttpHandler {
        private final GatewayApp app;

        private InvokeHandler(GatewayApp app) {
            this.app = app;
        }

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            writeResponse(exchange, app.invoke(GatewayRequest.fromExchange(exchange)));
        }
    }

    private static final class JsonHandler implements HttpHandler {
        private final ExchangeProcessor processor;

        private JsonHandler(ExchangeProcessor processor) {
            this.processor = processor;
        }

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            writeResponse(exchange, processor.handle(exchange));
        }
    }

    private static final class ConsoleIndexHandler implements HttpHandler {
        private final File indexFile;

        private ConsoleIndexHandler(File projectRoot) {
            this.indexFile = new File(projectRoot, "ui/index.html");
        }

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String path = exchange.getRequestURI().getPath();
            if (!"/".equals(path) && !path.startsWith("/console")) {
                writeResponse(exchange, GatewayResponse.json(404, jsonObject(mapOf(
                        "status", "error",
                        "message", "not found"
                ))));
                return;
            }
            writeFile(exchange, indexFile, "text/html; charset=utf-8");
        }
    }

    private static final class StaticFileHandler implements HttpHandler {
        private final File file;
        private final String contentType;

        private StaticFileHandler(File file, String contentType) {
            this.file = file;
            this.contentType = contentType;
        }

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            writeFile(exchange, file, contentType);
        }
    }

    @FunctionalInterface
    private interface ExchangeProcessor {
        GatewayResponse handle(HttpExchange exchange) throws IOException;
    }

    private interface UpstreamInvoker {
        GatewayResponse invoke(RouteConfig route, GatewayRequest request);

        boolean checkHealth(RouteConfig route);
    }

    private static final class RouteConfig {
        private final String service;
        private final String upstreamName;
        private final String invokeUrl;
        private final String healthUrl;

        private RouteConfig(String service, String upstreamName, String invokeUrl, String healthUrl) {
            this.service = service;
            this.upstreamName = upstreamName;
            this.invokeUrl = invokeUrl;
            this.healthUrl = healthUrl;
        }
    }

    private static final class CircuitBreakerState {
        private static final int FAILURE_THRESHOLD = 2;
        private static final long OPEN_MILLIS = 30_000L;
        private int consecutiveFailures;
        private Instant openUntil;

        synchronized boolean isOpen() {
            if (openUntil == null) {
                return false;
            }
            if (Instant.now().isAfter(openUntil)) {
                openUntil = null;
                consecutiveFailures = 0;
                return false;
            }
            return true;
        }

        synchronized void recordSuccess() {
            consecutiveFailures = 0;
            openUntil = null;
        }

        synchronized void recordFailure() {
            consecutiveFailures += 1;
            if (consecutiveFailures >= FAILURE_THRESHOLD) {
                openUntil = Instant.now().plusMillis(OPEN_MILLIS);
            }
        }

        synchronized long remainingOpenMillis() {
            if (openUntil == null) {
                return 0L;
            }
            long remaining = Duration.between(Instant.now(), openUntil).toMillis();
            return remaining > 0 ? remaining : 0L;
        }
    }

    private static final class ClientProfile {
        private final String name;
        private final String apiKey;
        private final int requestsPerMinute;
        private final Set<String> allowedServices;
        private final Map<String, Integer> serviceRequestsPerMinute;

        private ClientProfile(String name, String apiKey, int requestsPerMinute, Set<String> allowedServices, Map<String, Integer> serviceRequestsPerMinute) {
            this.name = name;
            this.apiKey = apiKey;
            this.requestsPerMinute = requestsPerMinute;
            this.allowedServices = allowedServices;
            this.serviceRequestsPerMinute = serviceRequestsPerMinute;
        }

        private int quotaFor(String service) {
            return serviceRequestsPerMinute.containsKey(service) ? serviceRequestsPerMinute.get(service) : requestsPerMinute;
        }
    }

    private static final class GatewayRequest {
        private final String method;
        private final String path;
        private final Map<String, String> queryParams;
        private final Map<String, String> headers;
        private final String body;

        private GatewayRequest(String method, String path, Map<String, String> queryParams, Map<String, String> headers, String body) {
            this.method = method;
            this.path = path;
            this.queryParams = queryParams;
            this.headers = headers;
            this.body = body;
        }

        static GatewayRequest fromExchange(HttpExchange exchange) throws IOException {
            return new GatewayRequest(
                    exchange.getRequestMethod(),
                    exchange.getRequestURI().getPath(),
                    parseQueryParams(exchange.getRequestURI()),
                    lowerCaseHeaders(exchange.getRequestHeaders()),
                    readBody(exchange.getRequestBody())
            );
        }

        String header(String name) {
            return headers.get(name.toLowerCase());
        }
    }

    private static final class GatewayResponse {
        private final int statusCode;
        private final String body;

        private GatewayResponse(int statusCode, String body) {
            this.statusCode = statusCode;
            this.body = body;
        }

        static GatewayResponse ok(String body) {
            return new GatewayResponse(200, body);
        }

        static GatewayResponse json(int statusCode, String body) {
            return new GatewayResponse(statusCode, body);
        }
    }

    private static final class RateWindow {
        private final Deque<Instant> requestTimes = new ArrayDeque<Instant>();

        synchronized boolean allow(int requestsPerMinute) {
            Instant cutoff = Instant.now().minusSeconds(60);
            while (!requestTimes.isEmpty() && requestTimes.peekFirst().isBefore(cutoff)) {
                requestTimes.removeFirst();
            }
            if (requestTimes.size() >= requestsPerMinute) {
                return false;
            }
            requestTimes.addLast(Instant.now());
            return true;
        }
    }

    private static final class ServiceMetrics {
        private int authorizedRequests;
        private int rejectedRequests;
        private long totalDurationMs;

        synchronized void record(boolean authorized, long durationMs) {
            if (authorized) {
                authorizedRequests += 1;
            } else {
                rejectedRequests += 1;
            }
            totalDurationMs += durationMs;
        }

        synchronized long averageDurationMs() {
            int totalRequests = authorizedRequests + rejectedRequests;
            return totalRequests == 0 ? 0 : totalDurationMs / totalRequests;
        }
    }

    private static final class AuditEvent {
        private final Instant timestamp;
        private final String client;
        private final String service;
        private final String decision;
        private final String reason;
        private final int statusCode;

        private AuditEvent(Instant timestamp, String client, String service, String decision, String reason, int statusCode) {
            this.timestamp = timestamp;
            this.client = client;
            this.service = service;
            this.decision = decision;
            this.reason = reason;
            this.statusCode = statusCode;
        }

        private String toJson() {
            return jsonObject(mapOf(
                    "timestamp", timestamp.toString(),
                    "client", client,
                    "service", service,
                    "decision", decision,
                    "reason", reason,
                    "status_code", statusCode
            ));
        }
    }

    private static final class MetricEvent {
        private final Instant timestamp;
        private final String service;
        private final boolean authorized;
        private final long durationMs;

        private MetricEvent(Instant timestamp, String service, boolean authorized, long durationMs) {
            this.timestamp = timestamp;
            this.service = service;
            this.authorized = authorized;
            this.durationMs = durationMs;
        }

        private String toJson() {
            return jsonObject(mapOf(
                    "timestamp", timestamp.toString(),
                    "service", service,
                    "authorized", authorized,
                    "duration_ms", durationMs
            ));
        }
    }

    private static final class TraceEvent {
        private final Instant timestamp;
        private final String requestId;
        private final String service;
        private final String targetContract;
        private final Map<String, String> headers;
        private final String originalBody;
        private final String transformedRequest;
        private final String gatewayResponse;

        private TraceEvent(Instant timestamp, String requestId, String service, String targetContract, Map<String, String> headers,
                           String originalBody, String transformedRequest, String gatewayResponse) {
            this.timestamp = timestamp;
            this.requestId = requestId;
            this.service = service;
            this.targetContract = targetContract;
            this.headers = headers;
            this.originalBody = originalBody;
            this.transformedRequest = transformedRequest;
            this.gatewayResponse = gatewayResponse;
        }

        private String toJson() {
            return jsonObject(mapOf(
                    "timestamp", timestamp.toString(),
                    "request_id", requestId,
                    "service", service,
                    "target_contract", targetContract,
                    "tenant_id", headers.containsKey("x-tenant-id") ? headers.get("x-tenant-id") : "",
                    "operator_id", headers.containsKey("x-operator-id") ? headers.get("x-operator-id") : "",
                    "original_body", originalBody,
                    "transformed_request", transformedRequest,
                    "gateway_response", gatewayResponse
            ));
        }
    }

    private static File projectRoot() {
        String configured = System.getProperty("gateway.root");
        if (configured != null && configured.trim().length() > 0) {
            return new File(configured);
        }
        return new File(".").getAbsoluteFile();
    }

    private static void writeResponse(HttpExchange exchange, GatewayResponse response) throws IOException {
        byte[] bytes = response.body.getBytes(StandardCharsets.UTF_8);
        Headers headers = exchange.getResponseHeaders();
        headers.set("Content-Type", "application/json; charset=utf-8");
        headers.set("X-Gateway", "agent-gateway-basic");
        exchange.sendResponseHeaders(response.statusCode, bytes.length);
        try (OutputStream outputStream = exchange.getResponseBody()) {
            outputStream.write(bytes);
        }
    }

    private static void writeFile(HttpExchange exchange, File file, String contentType) throws IOException {
        if (!file.exists()) {
            writeResponse(exchange, GatewayResponse.json(404, jsonObject(mapOf(
                    "status", "error",
                    "message", "file not found"
            ))));
            return;
        }
        FileInputStream inputStream = null;
        try {
            inputStream = new FileInputStream(file);
            byte[] bytes = readAllBytes(inputStream);
            Headers headers = exchange.getResponseHeaders();
            headers.set("Content-Type", contentType);
            headers.set("X-Gateway", "agent-gateway-basic");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream outputStream = exchange.getResponseBody()) {
                outputStream.write(bytes);
            }
        } finally {
            if (inputStream != null) {
                inputStream.close();
            }
        }
    }

    private static Map<String, String> parseQueryParams(URI uri) {
        String rawQuery = uri.getRawQuery();
        if (rawQuery == null || rawQuery.trim().isEmpty()) {
            return Collections.emptyMap();
        }
        Map<String, String> params = new LinkedHashMap<String, String>();
        for (String pair : rawQuery.split("&")) {
            String[] parts = pair.split("=", 2);
            params.put(parts[0], parts.length > 1 ? parts[1] : "");
        }
        return params;
    }

    private static Map<String, String> lowerCaseHeaders(Headers headers) {
        Map<String, String> values = new LinkedHashMap<String, String>();
        for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
            if (!entry.getValue().isEmpty()) {
                values.put(entry.getKey().toLowerCase(), entry.getValue().get(0));
            }
        }
        return values;
    }

    private static String readBody(InputStream inputStream) throws IOException {
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        byte[] buffer = new byte[1024];
        int read;
        while ((read = inputStream.read(buffer)) != -1) {
            outputStream.write(buffer, 0, read);
        }
        return new String(outputStream.toByteArray(), StandardCharsets.UTF_8);
    }

    private static byte[] readAllBytes(InputStream inputStream) throws IOException {
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        byte[] buffer = new byte[1024];
        int read;
        while ((read = inputStream.read(buffer)) != -1) {
            outputStream.write(buffer, 0, read);
        }
        return outputStream.toByteArray();
    }

    private static Set<String> csvToSet(String csv) {
        Set<String> values = new LinkedHashSet<String>();
        for (String item : csv.split(",")) {
            String trimmed = item.trim();
            if (!trimmed.isEmpty()) {
                values.add(trimmed);
            }
        }
        return values;
    }

    private static Set<String> setOf(String... values) {
        return new LinkedHashSet<String>(Arrays.asList(values));
    }

    private static Map<String, String> stringMapOf(String... values) {
        Map<String, String> map = new LinkedHashMap<String, String>();
        for (int i = 0; i < values.length; i += 2) {
            map.put(values[i], values[i + 1]);
        }
        return map;
    }

    private static Map<String, Object> mapOf(Object... values) {
        Map<String, Object> map = new LinkedHashMap<String, Object>();
        for (int i = 0; i < values.length; i += 2) {
            map.put(Objects.toString(values[i]), values[i + 1]);
        }
        return map;
    }

    private static Map<String, Object> integerMapToObjectMap(Map<String, Integer> values) {
        Map<String, Object> map = new LinkedHashMap<String, Object>();
        for (Map.Entry<String, Integer> entry : values.entrySet()) {
            map.put(entry.getKey(), entry.getValue());
        }
        return map;
    }

    private static String jsonArray(Set<String> values) {
        List<String> items = new ArrayList<String>();
        for (String value : values) {
            items.add("\"" + escapeJson(value) + "\"");
        }
        return "[" + String.join(",", items) + "]";
    }

    private static String jsonObject(Map<String, Object> values) {
        StringBuilder builder = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, Object> entry : values.entrySet()) {
            if (!first) {
                builder.append(",");
            }
            first = false;
            builder.append("\"").append(escapeJson(entry.getKey())).append("\":");
            builder.append(toJsonValue(entry.getValue()));
        }
        builder.append("}");
        return builder.toString();
    }

    private static String toJsonValue(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof Number || value instanceof Boolean) {
            return value.toString();
        }
        String stringValue = value.toString();
        if ((stringValue.startsWith("{") && stringValue.endsWith("}"))
                || (stringValue.startsWith("[") && stringValue.endsWith("]"))) {
            return stringValue;
        }
        return "\"" + escapeJson(stringValue) + "\"";
    }

    private static String escapeJson(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
    }
}
