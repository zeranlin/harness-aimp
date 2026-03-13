import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;

public class Main {
    public static void main(String[] args) throws Exception {
        if (args.length > 0 && args[0].equals("--self-test")) {
            System.out.println("self-test ok");
            return;
        }
        int port = 8080;
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/health", new HealthHandler());
        server.setExecutor(null);
        System.out.println("agent-gateway-basic listening on " + port);
        server.start();
    }

    static class HealthHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String response = "OK";
            exchange.sendResponseHeaders(200, response.length());
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(response.getBytes());
            }
        }
    }
}
