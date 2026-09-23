package com.lockstep.demo;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.LongAdder;

public final class DemoServer implements AutoCloseable {
    private static final String TOKEN = "tok-123";

    private final HttpServer server;
    private final LongAdder requests = new LongAdder();

    private DemoServer(HttpServer server) {
        this.server = server;
    }

    public static DemoServer start(int port) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        DemoServer demo = new DemoServer(server);
        server.createContext("/api/login", demo::login);
        server.createContext("/api/me", demo::me);
        server.createContext("/api/products", demo::products);
        server.createContext("/api/orders", demo::orders);
        server.createContext("/api/checkout", demo::checkout);
        server.createContext("/api/slow", demo::slow);
        server.start();
        return demo;
    }

    public int port() {
        return server.getAddress().getPort();
    }

    public long requestCount() {
        return requests.sum();
    }

    private void login(HttpExchange exchange) throws IOException {
        drain(exchange, 3);
        respond(exchange, 200, "{\"token\":\"" + TOKEN + "\",\"user\":{\"id\":42}}");
    }

    private void me(HttpExchange exchange) throws IOException {
        drain(exchange, 2);
        if (!("Bearer " + TOKEN).equals(exchange.getRequestHeaders().getFirst("Authorization"))) {
            respond(exchange, 401, "{\"error\":\"missing or bad token\"}");
            return;
        }
        respond(exchange, 200, "{\"id\":42,\"name\":\"alice\"}");
    }

    private void products(HttpExchange exchange) throws IOException {
        drain(exchange, 4);
        respond(exchange, 200, "{\"items\":[{\"sku\":\"A-1\",\"price\":1200},{\"sku\":\"B-2\",\"price\":800}]}");
    }

    private void orders(HttpExchange exchange) throws IOException {
        drain(exchange, 5);
        respond(exchange, 200, "{\"id\":1001,\"status\":\"accepted\"}");
    }

    private void checkout(HttpExchange exchange) throws IOException {
        drain(exchange, 6);
        String query = exchange.getRequestURI().getQuery();
        if (query == null || !query.contains("token=" + TOKEN)) {
            respond(exchange, 401, "{\"error\":\"missing token\"}");
            return;
        }
        respond(exchange, 200, "{\"ok\":true,\"order\":1001}");
    }

    private void slow(HttpExchange exchange) throws IOException {
        drain(exchange, 250);
        respond(exchange, 200, "{\"ok\":true,\"slow\":true}");
    }

    private void drain(HttpExchange exchange, long millis) throws IOException {
        requests.increment();
        try (InputStream in = exchange.getRequestBody()) {
            in.readAllBytes();
        }
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] payload = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, payload.length);
        exchange.getResponseBody().write(payload);
        exchange.close();
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
