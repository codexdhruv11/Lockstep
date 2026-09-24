package com.lockstep.scenario;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.LongAdder;

final class DemoServer implements AutoCloseable {
    private final HttpServer server;
    private final LongAdder logins = new LongAdder();
    private final List<String> authHeaders = Collections.synchronizedList(new java.util.ArrayList<>());
    private final List<String> paths = Collections.synchronizedList(new java.util.ArrayList<>());
    volatile boolean loginFails = false;

    DemoServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/api/login", this::login);
        server.createContext("/api/me", this::me);
        server.createContext("/api/products", this::products);
        server.createContext("/api/checkout", this::checkout);
        server.createContext("/api/slow-step", exchange -> {
            try {
                Thread.sleep(120);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            drain(exchange);
            respond(exchange, 200, "{\"ok\":true}");
        });
        server.start();
    }

    int port() {
        return server.getAddress().getPort();
    }

    long logins() {
        return logins.sum();
    }

    List<String> authHeaders() {
        return List.copyOf(authHeaders);
    }

    List<String> paths() {
        return List.copyOf(paths);
    }

    private void login(HttpExchange exchange) throws IOException {
        logins.increment();
        drain(exchange);
        if (loginFails) {
            respond(exchange, 500, "{\"error\":\"nope\"}");
            return;
        }
        respond(exchange, 200, "{\"token\":\"tok-123\",\"user\":{\"id\":42}}");
    }

    private void me(HttpExchange exchange) throws IOException {
        drain(exchange);
        String auth = exchange.getRequestHeaders().getFirst("Authorization");
        authHeaders.add(auth == null ? "<none>" : auth);
        if (!"Bearer tok-123".equals(auth)) {
            respond(exchange, 401, "{\"error\":\"bad token\"}");
            return;
        }
        respond(exchange, 200, "{\"id\":42,\"name\":\"alice\"}");
    }

    private void products(HttpExchange exchange) throws IOException {
        drain(exchange);
        respond(exchange, 200, "{\"items\":[{\"sku\":\"A-1\"},{\"sku\":\"B-2\"}]}");
    }

    private void checkout(HttpExchange exchange) throws IOException {
        drain(exchange);
        String query = exchange.getRequestURI().getQuery();
        if (query == null || !query.contains("token=tok-123")) {
            respond(exchange, 401, "{\"error\":\"missing token\"}");
            return;
        }
        respond(exchange, 200, "{\"ok\":true}");
    }

    private void drain(HttpExchange exchange) throws IOException {
        paths.add(exchange.getRequestURI().getPath());
        try (InputStream in = exchange.getRequestBody()) {
            in.readAllBytes();
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
