package com.lockstep.runner.redis;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.LongAdder;

final class StubRedisServer implements AutoCloseable {
    private final ServerSocket serverSocket;
    private final ExecutorService connections = Executors.newVirtualThreadPerTaskExecutor();
    private final LongAdder commandsReceived = new LongAdder();
    private final List<String> seenCommands = java.util.Collections.synchronizedList(new ArrayList<>());
    private volatile boolean running = true;

    StubRedisServer() throws IOException {
        this.serverSocket = new ServerSocket(0);
        connections.submit(this::acceptLoop);
    }

    int port() {
        return serverSocket.getLocalPort();
    }

    long commandsReceived() {
        return commandsReceived.sum();
    }

    List<String> seenCommands() {
        return List.copyOf(seenCommands);
    }

    private void acceptLoop() {
        while (running) {
            try {
                Socket socket = serverSocket.accept();
                connections.submit(() -> serve(socket));
            } catch (IOException e) {
                return;
            }
        }
    }

    private void serve(Socket socket) {
        try (socket;
                InputStream in = socket.getInputStream();
                OutputStream out = new BufferedOutputStream(socket.getOutputStream())) {
            while (running) {
                List<String> command = readCommand(in);
                if (command == null) {
                    return;
                }
                commandsReceived.increment();
                seenCommands.add(String.join(" ", command));
                out.write(replyFor(command));
                out.flush();
            }
        } catch (IOException e) {
        }
    }

    private static List<String> readCommand(InputStream in) throws IOException {
        String header = readLine(in);
        if (header == null) {
            return null;
        }
        if (!header.startsWith("*")) {
            return List.of(header.split(" "));
        }
        int argc = Integer.parseInt(header.substring(1));
        List<String> parts = new ArrayList<>(argc);
        for (int i = 0; i < argc; i++) {
            String lengthLine = readLine(in);
            if (lengthLine == null) {
                return null;
            }
            int length = Integer.parseInt(lengthLine.substring(1));
            byte[] payload = in.readNBytes(length);
            in.readNBytes(2);
            parts.add(new String(payload, StandardCharsets.UTF_8));
        }
        return parts;
    }

    private static String readLine(InputStream in) throws IOException {
        StringBuilder line = new StringBuilder();
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\r') {
                in.read();
                return line.toString();
            }
            line.append((char) c);
        }
        return line.isEmpty() ? null : line.toString();
    }

    private static byte[] replyFor(List<String> command) {
        String name = command.get(0).toUpperCase();
        String reply = switch (name) {
            case "HELLO" -> "-ERR unknown command 'HELLO'\r\n";
            case "CLIENT", "AUTH", "SELECT" -> "+OK\r\n";
            case "PING" -> "+PONG\r\n";
            case "SET", "MSET" -> "+OK\r\n";
            case "GET" -> "$5\r\nvalue\r\n";
            case "INCR", "DEL", "EXISTS" -> ":1\r\n";
            case "HGETALL" -> "*2\r\n$1\r\na\r\n$1\r\nb\r\n";
            case "HGET" -> "$-1\r\n";
            case "BROKEN" -> "-ERR unknown command 'BROKEN'\r\n";
            default -> "+OK\r\n";
        };
        return reply.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public void close() throws IOException {
        running = false;
        serverSocket.close();
        connections.shutdownNow();
    }
}
