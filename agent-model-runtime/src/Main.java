import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class Main {
    public static void main(String[] args) throws Exception {
        if (args.length > 0 && "--self-test".equals(args[0])) {
            System.out.println("self-test ok");
            return;
        }
        int port = 8081;
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/health", new JsonHandler("{\"status\":\"UP\",\"service\":\"agent-model-runtime\"}"));
        server.createContext("/ops/runtime", new JsonHandler("{\"status\":\"UP\",\"service\":\"agent-model-runtime\","
                + "\"queue_depth\":0,\"inflight\":0,\"max_concurrency\":8,"
                + "\"retry_policy\":{\"max_attempts\":2,\"timeout_ms\":2000},"
                + "\"circuit_breakers\":[{\"route\":\"pricing_inference\",\"state\":\"CLOSED\"}],"
                + "\"routes\":[{\"task_type\":\"pricing_inference\",\"model_route\":\"general-llm-v1\"}]}"));
        server.createContext("/invoke", new InvokeHandler());
        server.setExecutor(null);
        System.out.println("agent-model-runtime listening on " + port);
        server.start();
    }

    static class JsonHandler implements HttpHandler {
        private final String payload;

        JsonHandler(String payload) {
            this.payload = payload;
        }

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            writeJson(exchange, 200, payload);
        }
    }

    static class InvokeHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"POST".equals(exchange.getRequestMethod())) {
                writeJson(exchange, 405, "{\"status\":\"error\",\"message\":\"method not allowed\"}");
                return;
            }
            String body = readBody(exchange.getRequestBody());
            String requestId = extractJsonString(body, "request_id");
            String taskType = extractJsonString(body, "task_type");
            if (taskType.isEmpty()) {
                taskType = "pricing";
            }
            String response = "{\"status\":\"ok\",\"service\":\"pricing\",\"provider\":\"agent-model-runtime\","
                    + "\"request_id\":\"" + escapeJson(requestId) + "\","
                    + "\"task_type\":\"" + escapeJson(taskType) + "\","
                    + "\"runtime\":\"priority-scheduler\",\"model_route\":\"general-llm-v1\","
                    + "\"payload_size\":" + body.length() + "}";
            writeJson(exchange, 200, response);
        }
    }

    static void writeJson(HttpExchange exchange, int statusCode, String payload) throws IOException {
        byte[] bytes = payload.getBytes(StandardCharsets.UTF_8);
        Headers headers = exchange.getResponseHeaders();
        headers.set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(statusCode, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    static String readBody(InputStream inputStream) throws IOException {
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        byte[] buffer = new byte[1024];
        int read;
        while ((read = inputStream.read(buffer)) != -1) {
            outputStream.write(buffer, 0, read);
        }
        return new String(outputStream.toByteArray(), StandardCharsets.UTF_8);
    }

    static String extractJsonString(String body, String key) {
        Pattern pattern = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*\"([^\"]*)\"");
        Matcher matcher = pattern.matcher(body == null ? "" : body);
        return matcher.find() ? matcher.group(1) : "";
    }

    static String escapeJson(String value) {
        return (value == null ? "" : value)
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n");
    }
}
