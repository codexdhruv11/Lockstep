package com.lockstep.scenario;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;

final class SlowStepServer implements AutoCloseable {
    private final HttpServer server;

    SlowStepServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/fast", exchange -> respond(exchange, 200, "{\"ok\":true}"));
        server.createContext("/slow", exchange -> {
            sleep(120);
            respond(exchange, 200, "{\"ok\":true}");
        });
        server.createContext("/boom", exchange -> respond(exchange, 500, "{\"error\":\"boom\"}"));
        server.start();
    }

    int port() {
        return server.getAddress().getPort();
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        exchange.getRequestBody().readAllBytes();
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
