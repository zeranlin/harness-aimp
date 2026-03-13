import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
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
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class Main {
    private static final int PORT = 8080;

    public static void main(String[] args) throws Exception {
        GatewayApp app = GatewayApp.defaultApp();
        if (args.length > 0 && "--self-test".equals(args[0])) {
            runSelfTest();
            System.out.println("self-test ok");
            return;
        }

        HttpServer server = HttpServer.create(new InetSocketAddress(PORT), 0);
        server.createContext("/health", new JsonHandler(exchange ->
                GatewayResponse.ok(jsonObject(mapOf(
                        "status", "UP",
                        "service", "agent-gateway-basic"
                )))
        ));
        server.createContext("/gateway/v1/invoke", new InvokeHandler(app));
        server.createContext("/ops/metrics/overview", new JsonHandler(exchange ->
                GatewayResponse.ok(app.metricsJson())
        ));
        server.createContext("/ops/audits", new JsonHandler(exchange ->
                GatewayResponse.ok(app.auditJson())
        ));
        server.setExecutor(null);
        System.out.println("agent-gateway-basic listening on " + PORT);
        server.start();
    }

    private static void runSelfTest() {
        GatewayApp app = GatewayApp.withInvoker(defaultProfiles(), fakeInvoker());

        GatewayResponse missingKey = app.invoke(new GatewayRequest("GET", "/gateway/v1/invoke",
                stringMapOf("service", "qa"), Collections.<String, String>emptyMap(), ""));
        assertEquals(401, missingKey.statusCode, "missing api key should fail");

        GatewayResponse badKey = app.invoke(new GatewayRequest("GET", "/gateway/v1/invoke",
                stringMapOf("service", "qa"), stringMapOf("x-api-key", "unknown"), ""));
        assertEquals(403, badKey.statusCode, "unknown key should fail");

        GatewayResponse blockedService = app.invoke(new GatewayRequest("GET", "/gateway/v1/invoke",
                stringMapOf("service", "restricted"), stringMapOf("x-api-key", "demo-key-partner"), ""));
        assertEquals(403, blockedService.statusCode, "policy should block restricted service");

        GatewayResponse ok = app.invoke(new GatewayRequest("POST", "/gateway/v1/invoke",
                stringMapOf("service", "qa"), stringMapOf("x-api-key", "demo-key-ops"), "{\"prompt\":\"test\"}"));
        assertEquals(200, ok.statusCode, "authorized request should pass");
        assertContains(ok.body, "\"provider\":\"agent-business-solution\"", "response should come from upstream");

        GatewayApp limitApp = GatewayApp.withInvoker(Collections.singletonList(new ClientProfile(
                "limited",
                "demo-key-limited",
                1,
                setOf("qa")
        )), fakeInvoker());
        GatewayResponse first = limitApp.invoke(new GatewayRequest("GET", "/gateway/v1/invoke",
                stringMapOf("service", "qa"), stringMapOf("x-api-key", "demo-key-limited"), ""));
        GatewayResponse second = limitApp.invoke(new GatewayRequest("GET", "/gateway/v1/invoke",
                stringMapOf("service", "qa"), stringMapOf("x-api-key", "demo-key-limited"), ""));
        assertEquals(200, first.statusCode, "first limited request should pass");
        assertEquals(429, second.statusCode, "second limited request should be throttled");

        String metrics = app.metricsJson();
        assertContains(metrics, "\"authorized_requests\":1", "metrics should count successful requests");
        String audits = app.auditJson();
        assertContains(audits, "\"decision\":\"ALLOW\"", "audit log should include allow");
        assertContains(audits, "\"decision\":\"DENY\"", "audit log should include deny");
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
        };
    }

    private static List<ClientProfile> defaultProfiles() {
        return Arrays.asList(
                new ClientProfile("ops", "demo-key-ops", 60, setOf("qa", "compliance", "pricing")),
                new ClientProfile("business", "demo-key-business", 30, setOf("qa", "compliance")),
                new ClientProfile("partner", "demo-key-partner", 10, setOf("qa"))
        );
    }

    private static void assertEquals(int expected, int actual, String message) {
        if (expected != actual) {
            throw new IllegalStateException(message + ": expected " + expected + " but got " + actual);
        }
    }

    private static void assertContains(String value, String expectedFragment, String message) {
        if (!value.contains(expectedFragment)) {
            throw new IllegalStateException(message + ": missing fragment " + expectedFragment);
        }
    }

    private static final class InvokeHandler implements HttpHandler {
        private final GatewayApp app;

        private InvokeHandler(GatewayApp app) {
            this.app = app;
        }

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            GatewayRequest request = GatewayRequest.fromExchange(exchange);
            GatewayResponse response = app.invoke(request);
            writeResponse(exchange, response);
        }
    }

    private static final class JsonHandler implements HttpHandler {
        private final ExchangeProcessor processor;

        private JsonHandler(ExchangeProcessor processor) {
            this.processor = processor;
        }

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            GatewayResponse response = processor.handle(exchange);
            writeResponse(exchange, response);
        }
    }

    @FunctionalInterface
    private interface ExchangeProcessor {
        GatewayResponse handle(HttpExchange exchange) throws IOException;
    }

    @FunctionalInterface
    private interface UpstreamInvoker {
        GatewayResponse invoke(RouteConfig route, GatewayRequest request);
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

    private static final class GatewayApp {
        private final Map<String, ClientProfile> profilesByApiKey;
        private final Map<String, RouteConfig> routesByService;
        private final Map<String, RateWindow> windowsByClient = new ConcurrentHashMap<String, RateWindow>();
        private final Map<String, ServiceMetrics> metricsByService = new ConcurrentHashMap<String, ServiceMetrics>();
        private final Deque<AuditEvent> auditEvents = new ArrayDeque<AuditEvent>();
        private final UpstreamInvoker upstreamInvoker;

        private GatewayApp(List<ClientProfile> profiles, UpstreamInvoker upstreamInvoker) {
            Map<String, ClientProfile> profilesMap = new LinkedHashMap<String, ClientProfile>();
            for (ClientProfile profile : profiles) {
                profilesMap.put(profile.apiKey, profile);
            }
            this.profilesByApiKey = profilesMap;
            this.routesByService = defaultRoutes();
            this.upstreamInvoker = upstreamInvoker;
        }

        static GatewayApp defaultApp() {
            return new GatewayApp(defaultProfiles(), new HttpUpstreamInvoker());
        }

        static GatewayApp withInvoker(List<ClientProfile> profiles, UpstreamInvoker upstreamInvoker) {
            return new GatewayApp(profiles, upstreamInvoker);
        }

        GatewayResponse invoke(GatewayRequest request) {
            Instant startedAt = Instant.now();
            String service = request.queryParams.containsKey("service") ? request.queryParams.get("service") : "qa";
            String apiKey = request.header("x-api-key");
            String clientName = "anonymous";

            if (apiKey == null || apiKey.trim().isEmpty()) {
                recordAudit(clientName, service, "DENY", "missing_api_key", startedAt, 401);
                return GatewayResponse.json(401, jsonObject(mapOf(
                        "status", "error",
                        "message", "missing x-api-key"
                )));
            }

            ClientProfile profile = profilesByApiKey.get(apiKey);
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

            RouteConfig route = routesByService.get(service);
            if (route == null) {
                recordAudit(clientName, service, "DENY", "route_not_found", startedAt, 404);
                incrementMetric(service, false, startedAt);
                return GatewayResponse.json(404, jsonObject(mapOf(
                        "status", "error",
                        "message", "unknown service",
                        "service", service
                )));
            }

            RateWindow window = windowsByClient.containsKey(clientName)
                    ? windowsByClient.get(clientName)
                    : new RateWindow();
            windowsByClient.put(clientName, window);
            if (!window.allow(profile.requestsPerMinute)) {
                recordAudit(clientName, service, "DENY", "rate_limited", startedAt, 429);
                incrementMetric(service, false, startedAt);
                return GatewayResponse.json(429, jsonObject(mapOf(
                        "status", "error",
                        "message", "quota exceeded",
                        "quota_per_minute", profile.requestsPerMinute
                )));
            }

            GatewayResponse upstreamResponse = upstreamInvoker.invoke(route, request);
            int statusCode = upstreamResponse.statusCode;
            String decision = statusCode >= 200 && statusCode < 300 ? "ALLOW" : "DENY";
            String reason = statusCode >= 200 && statusCode < 300 ? "forwarded" : "upstream_error";
            recordAudit(clientName, service, decision, reason, startedAt, statusCode);
            incrementMetric(service, statusCode >= 200 && statusCode < 300, startedAt);
            return GatewayResponse.json(statusCode, jsonObject(mapOf(
                    "status", statusCode >= 200 && statusCode < 300 ? "ok" : "error",
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
            List<String> items = new ArrayList<String>();
            synchronized (auditEvents) {
                for (AuditEvent event : auditEvents) {
                    items.add(jsonObject(mapOf(
                            "timestamp", event.timestamp.toString(),
                            "client", event.client,
                            "service", event.service,
                            "decision", event.decision,
                            "reason", event.reason,
                            "status_code", event.statusCode
                    )));
                }
            }
            return jsonObject(mapOf("items", "[" + String.join(",", items) + "]"));
        }

        private void incrementMetric(String service, boolean authorized, Instant startedAt) {
            ServiceMetrics metrics = metricsByService.containsKey(service)
                    ? metricsByService.get(service)
                    : new ServiceMetrics();
            metricsByService.put(service, metrics);
            long durationMs = Duration.between(startedAt, Instant.now()).toMillis();
            metrics.record(authorized, durationMs);
        }

        private void recordAudit(String client, String service, String decision, String reason, Instant timestamp, int statusCode) {
            synchronized (auditEvents) {
                auditEvents.addFirst(new AuditEvent(timestamp, client, service, decision, reason, statusCode));
                while (auditEvents.size() > 100) {
                    auditEvents.removeLast();
                }
            }
        }

        private Map<String, RouteConfig> defaultRoutes() {
            Map<String, RouteConfig> routes = new LinkedHashMap<String, RouteConfig>();
            routes.put("qa", new RouteConfig("agent-business-solution", "http://127.0.0.1:8002/invoke"));
            routes.put("compliance", new RouteConfig("atomic-ai-service", "http://127.0.0.1:8003/invoke"));
            routes.put("pricing", new RouteConfig("agent-model-runtime", "http://127.0.0.1:8081/invoke"));
            return routes;
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
            String body = request.body == null ? "" : request.body;
            if (body.trim().isEmpty()) {
                return jsonObject(mapOf("prompt", "", "document", ""));
            }
            return body;
        }
    }

    private static final class RouteConfig {
        private final String upstreamName;
        private final String invokeUrl;

        private RouteConfig(String upstreamName, String invokeUrl) {
            this.upstreamName = upstreamName;
            this.invokeUrl = invokeUrl;
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

    private static final class ClientProfile {
        private final String name;
        private final String apiKey;
        private final int requestsPerMinute;
        private final Set<String> allowedServices;

        private ClientProfile(String name, String apiKey, int requestsPerMinute, Set<String> allowedServices) {
            this.name = name;
            this.apiKey = apiKey;
            this.requestsPerMinute = requestsPerMinute;
            this.allowedServices = allowedServices;
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
    }

    private static Map<String, String> parseQueryParams(URI uri) {
        String rawQuery = uri.getRawQuery();
        if (rawQuery == null || rawQuery.trim().isEmpty()) {
            return Collections.emptyMap();
        }
        Map<String, String> params = new LinkedHashMap<String, String>();
        for (String pair : rawQuery.split("&")) {
            String[] parts = pair.split("=", 2);
            String key = parts[0];
            String value = parts.length > 1 ? parts[1] : "";
            params.put(key, value);
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

    private static Set<String> setOf(String... values) {
        return new LinkedHashSet<String>(Arrays.asList(values));
    }

    private static Map<String, String> stringMapOf(String... values) {
        if (values.length % 2 != 0) {
            throw new IllegalArgumentException("stringMapOf requires even number of arguments");
        }
        Map<String, String> map = new LinkedHashMap<String, String>();
        for (int i = 0; i < values.length; i += 2) {
            map.put(values[i], values[i + 1]);
        }
        return map;
    }

    private static Map<String, Object> mapOf(Object... values) {
        if (values.length % 2 != 0) {
            throw new IllegalArgumentException("mapOf requires even number of arguments");
        }
        Map<String, Object> map = new LinkedHashMap<String, Object>();
        for (int i = 0; i < values.length; i += 2) {
            map.put(Objects.toString(values[i]), values[i + 1]);
        }
        return map;
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
        return value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n");
    }
}
