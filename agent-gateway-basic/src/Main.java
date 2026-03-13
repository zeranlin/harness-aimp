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
import java.util.concurrent.ConcurrentHashMap;

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
        String reload = app.reloadWithConfig(new GatewayDiskConfig(defaultRoutes(), defaultProfiles(), new File(projectRoot(), "data/audits.log")));
        assertContains(reload, "\"status\":\"ok\"", "reload should succeed");
    }

    private static UpstreamInvoker fakeInvoker() {
        return new UpstreamInvoker() {
            @Override
            public GatewayResponse invoke(RouteConfig route, GatewayRequest request) {
                return GatewayResponse.ok(jsonObject(mapOf(
                        "status", "ok",
                        "provider", route.upstreamName,
                        "upstream_url", route.invokeUrl,
                        "request_method", request.method
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
        routes.put("compliance", new RouteConfig("compliance", "atomic-ai-service", "http://127.0.0.1:8003/invoke", "http://127.0.0.1:8003/health"));
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
        private final Map<String, CircuitBreakerState> circuitStates = new ConcurrentHashMap<String, CircuitBreakerState>();
        private final Deque<AuditEvent> auditEvents = new ArrayDeque<AuditEvent>();
        private final File projectRoot;
        private final File auditLogFile;
        private final UpstreamInvoker upstreamInvoker;

        private GatewayApp(File projectRoot, GatewayConfigSnapshot configSnapshot, File auditLogFile, UpstreamInvoker upstreamInvoker) {
            this.projectRoot = projectRoot;
            this.configSnapshot = configSnapshot;
            this.auditLogFile = auditLogFile;
            this.upstreamInvoker = upstreamInvoker;
        }

        static GatewayApp fromDisk(UpstreamInvoker upstreamInvoker) throws IOException {
            File root = projectRoot();
            GatewayDiskConfig config = GatewayDiskConfig.load(root);
            return fromConfig(root, config.routesByService, config.clientProfiles, config.auditLogFile, upstreamInvoker);
        }

        static GatewayApp fromConfig(Map<String, RouteConfig> routesByService, List<ClientProfile> profiles, UpstreamInvoker upstreamInvoker) {
            File root = projectRoot();
            return fromConfig(root, routesByService, profiles, new File(root, "data/audits.log"), upstreamInvoker);
        }

        static GatewayApp fromConfig(Map<String, RouteConfig> routesByService, List<ClientProfile> profiles, File auditLogFile, UpstreamInvoker upstreamInvoker) {
            return fromConfig(projectRoot(), routesByService, profiles, auditLogFile, upstreamInvoker);
        }

        static GatewayApp fromConfig(File projectRoot, Map<String, RouteConfig> routesByService, List<ClientProfile> profiles, File auditLogFile, UpstreamInvoker upstreamInvoker) {
            return new GatewayApp(projectRoot, GatewayConfigSnapshot.from(routesByService, profiles), auditLogFile, upstreamInvoker);
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

            return GatewayResponse.json(statusCode, jsonObject(mapOf(
                    "status", authorized ? "ok" : "error",
                    "routed_service", service,
                    "client", clientName,
                    "upstream", route.upstreamName,
                    "upstream_url", route.invokeUrl,
                    "duration_ms", Duration.between(startedAt, Instant.now()).toMillis(),
                    "upstream_response", upstreamResponse.body
            )));
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
                    "upstreams", upstreamsJson(),
                    "config", configJson(),
                    "audit_summary", auditSummaryJson(recentAuditEntries),
                    "recent_audits", jsonObject(mapOf("items", "[" + String.join(",", recentAuditEntries) + "]"))
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
            metrics.record(authorized, Duration.between(startedAt, Instant.now()).toMillis());
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
    }

    private static final class GatewayDiskConfig {
        private final Map<String, RouteConfig> routesByService;
        private final List<ClientProfile> clientProfiles;
        private final File auditLogFile;

        private GatewayDiskConfig(Map<String, RouteConfig> routesByService, List<ClientProfile> clientProfiles, File auditLogFile) {
            this.routesByService = routesByService;
            this.clientProfiles = clientProfiles;
            this.auditLogFile = auditLogFile;
        }

        static GatewayDiskConfig load(File projectRoot) throws IOException {
            Properties routeProps = loadProperties(new File(projectRoot, "config/routes.properties"));
            Properties clientProps = loadProperties(new File(projectRoot, "config/clients.properties"));
            return new GatewayDiskConfig(parseRoutes(routeProps), parseClients(clientProps), new File(projectRoot, "data/audits.log"));
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
                byte[] payload = upstreamPayload(request).getBytes(StandardCharsets.UTF_8);
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

        private String upstreamPayload(GatewayRequest request) {
            if (request.body == null || request.body.trim().isEmpty()) {
                return jsonObject(mapOf("prompt", "", "document", ""));
            }
            return request.body;
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
