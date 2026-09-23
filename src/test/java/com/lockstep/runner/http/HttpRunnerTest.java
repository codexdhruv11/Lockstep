package com.lockstep.runner.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.lockstep.config.HttpConfig;
import com.lockstep.core.PacedLoop;
import com.lockstep.core.RunContext;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.LongAdder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

final class HttpRunnerTest {
    private static final long MS = 1_000_000L;
    private static final long SECOND = 1_000_000_000L;

    private HttpServer server;
    private final LongAdder requests = new LongAdder();
    private final List<String> seenMethods = Collections.synchronizedList(new java.util.ArrayList<>());
    private final List<String> seenBodies = Collections.synchronizedList(new java.util.ArrayList<>());
    private final List<String> seenAuthHeaders = Collections.synchronizedList(new java.util.ArrayList<>());
    private volatile int statusToReturn = 200;
    private volatile long delayMillis = 0;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/", this::handle);
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private void handle(HttpExchange exchange) throws IOException {
        requests.increment();
        seenMethods.add(exchange.getRequestMethod());
        String auth = exchange.getRequestHeaders().getFirst("Authorization");
        if (auth != null) {
            seenAuthHeaders.add(auth);
        }
        try (InputStream in = exchange.getRequestBody()) {
            String body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            if (!body.isEmpty()) {
                seenBodies.add(body);
            }
        }
        if (delayMillis > 0) {
            try {
                Thread.sleep(delayMillis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        byte[] payload = "ok".getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(statusToReturn, payload.length);
        exchange.getResponseBody().write(payload);
        exchange.close();
    }

    private String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/api/orders";
    }

    private HttpConfig config(int rate, String method, String body, Map<String, List<String>> headers) {
        return new HttpConfig(new HttpConfig.Target(method, url(), body, headers), rate);
    }

    @Test
    void firesRequestsAndRecordsStatusCodes() {
        try (HttpRunner runner = HttpRunner.create(config(100, "GET", null, Map.of()), 8)) {
            PacedLoop.LoopResult result = runner.run(RunContext.startingNow(SECOND, 200 * MS, 0, 8));

            assertThat(result.executedCount()).isGreaterThan(50);
            assertThat(result.series().errorCount())
                    .withFailMessage("expected no errors, last failure was: %s", runner.lastFailure())
                    .isZero();
            assertThat(result.series().statusCounts()).containsOnlyKeys(200);
            assertThat(requests.sum()).isEqualTo(result.executedCount());
            assertThat(result.series().summarize("http", SECOND).p99Nanos()).isGreaterThan(0);
        }
    }

    @Test
    void methodBodyAndHeadersReachTheServer() {
        HttpConfig config = config(30, "POST", "{\"customer\": 42}",
                Map.of("authorization", List.of("Bearer some-token"),
                       "content-type", List.of("application/json")));

        try (HttpRunner runner = HttpRunner.create(config, 4)) {
            runner.run(RunContext.startingNow(500 * MS, 100 * MS, 0, 4));
        }

        assertThat(seenMethods).isNotEmpty().allMatch("POST"::equals);
        assertThat(seenBodies).isNotEmpty().allMatch("{\"customer\": 42}"::equals);
        assertThat(seenAuthHeaders).isNotEmpty().allMatch("Bearer some-token"::equals);
    }

    @Test
    void errorStatusesAreRecordedWithTheirCodeNotHidden() {
        statusToReturn = 503;

        try (HttpRunner runner = HttpRunner.create(config(40, "GET", null, Map.of()), 4)) {
            PacedLoop.LoopResult result = runner.run(RunContext.startingNow(500 * MS, 100 * MS, 0, 4));

            assertThat(result.executedCount()).isGreaterThan(0);
            assertThat(result.series().errorCount()).isEqualTo(result.executedCount());
            assertThat(result.series().statusCounts()).containsOnlyKeys(503);
            assertThat(result.drainedCleanly()).isTrue();
        }
    }

    @Test
    void redirectsAreNotFollowedSoOneScheduledRequestIsOneRequest() {
        statusToReturn = 302;

        try (HttpRunner runner = HttpRunner.create(config(30, "GET", null, Map.of()), 4)) {
            PacedLoop.LoopResult result = runner.run(RunContext.startingNow(500 * MS, 100 * MS, 0, 4));

            assertThat(requests.sum()).isEqualTo(result.executedCount());
            assertThat(result.series().statusCounts()).containsOnlyKeys(302);

            assertThat(result.series().errorCount()).isZero();
        }
    }

    @Test
    void aSlowEndpointShowsUpAsLatencyNotAsLostRequests() {
        delayMillis = 100;

        try (HttpRunner runner = HttpRunner.create(config(40, "GET", null, Map.of()), 16)) {
            PacedLoop.LoopResult result = runner.run(RunContext.startingNow(SECOND, 200 * MS, 0, 16));

            assertThat(result.executedCount() + result.shedCount()).isEqualTo(result.scheduledCount());
            assertThat(result.series().summarize("http", SECOND).p50Nanos()).isGreaterThan(90 * MS);
            assertThat(result.series().errorCount()).isZero();
        }
    }

    @Test
    void aDeadServerIsRecordedAsFailedOperationsNotAnException() {
        String deadUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/gone";
        server.stop(0);

        HttpConfig config = new HttpConfig(new HttpConfig.Target("GET", deadUrl, null, Map.of()), 20);
        try (HttpRunner runner = HttpRunner.create(config, 4)) {
            PacedLoop.LoopResult result = runner.run(RunContext.startingNow(400 * MS, 100 * MS, 0, 4));

            assertThat(result.executedCount()).isGreaterThan(0);
            assertThat(result.series().errorCount()).isEqualTo(result.executedCount());
            assertThat(runner.lastFailure()).isNotNull();
        }
    }

    @Test
    void invalidUrlsFailAtCreationWithAUsefulMessage() {
        assertThatThrownBy(() -> HttpRunner.create(
                new HttpConfig(new HttpConfig.Target("GET", "/api/orders", null, Map.of()), 10), 2))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must be absolute");
    }

    @Test
    void headersTheJdkRefusesToSendFailAtCreationNamingTheHeader() {
        HttpConfig config = config(10, "GET", null, Map.of("connection", List.of("close")));

        assertThatThrownBy(() -> HttpRunner.create(config, 2))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("connection");
    }

    @Test
    void defaultMethodIsGetWhenTheConfigLeavesItBlank() {
        try (HttpRunner runner = HttpRunner.create(config(20, "", null, Map.of()), 2)) {
            runner.run(RunContext.startingNow(300 * MS, 100 * MS, 0, 2));
        }
        assertThat(seenMethods).isNotEmpty().allMatch("GET"::equals);
    }
}
