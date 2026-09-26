package com.lockstep.runner.http;

import static org.assertj.core.api.Assertions.assertThat;

import com.lockstep.config.HttpConfig;
import com.lockstep.core.PacedLoop;
import com.lockstep.core.RunContext;
import com.lockstep.runner.QueryLabels;
import com.lockstep.stats.BucketSeries;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.LongAdder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

final class MultiTargetHttpTest {
    private static final long MS = 1_000_000L;
    private static final long SECOND = 1_000_000_000L;

    private HttpServer server;
    private final Map<String, LongAdder> hitsByPath = new ConcurrentHashMap<>();

    private static final Map<String, Integer> DELAY_MILLIS =
            Map.of("/fast", 0, "/medium", 25, "/slow", 80, "/broken", 0);

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
        String path = exchange.getRequestURI().getPath();
        hitsByPath.computeIfAbsent(path, ignored -> new LongAdder()).increment();
        try (InputStream in = exchange.getRequestBody()) {
            in.readAllBytes();
        }
        try {
            Thread.sleep(DELAY_MILLIS.getOrDefault(path, 0));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        int status = "/broken".equals(path) ? 503 : 200;
        byte[] payload = "{}".getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, payload.length);
        exchange.getResponseBody().write(payload);
        exchange.close();
    }

    private String url(String path) {
        return "http://127.0.0.1:" + server.getAddress().getPort() + path;
    }

    private HttpConfig.Target target(String path, int weight) {
        return new HttpConfig.Target("GET", url(path), "", Map.of(), weight);
    }

    private long hits(String path) {
        LongAdder adder = hitsByPath.get(path);
        return adder == null ? 0 : adder.sum();
    }

    @Test
    void oneRunDrivesEveryTargetAndEveryRequestIsAttributedToOne() {
        HttpConfig config = new HttpConfig(null,
                List.of(target("/fast", 50), target("/medium", 30), target("/slow", 20)), 120);

        try (HttpRunner runner = HttpRunner.create(config, 16)) {
            PacedLoop.LoopResult result = runner.run(RunContext.startingNow(2 * SECOND, 200 * MS, 0, 16));

            Map<String, BucketSeries> breakdown = runner.targetBreakdown();
            assertThat(breakdown).hasSize(3);

            long attributed = breakdown.values().stream().mapToLong(BucketSeries::totalCount).sum();
            assertThat(attributed)
                    .withFailMessage("per-target counts must account for every recorded request: "
                            + "runner saw %d, targets account for %d",
                            result.executedCount(), attributed)
                    .isEqualTo(result.executedCount());

            assertThat(hits("/fast")).isGreaterThan(0);
            assertThat(hits("/medium")).isGreaterThan(0);
            assertThat(hits("/slow")).isGreaterThan(0);
        }
    }

    @Test
    void weightsDecideTheMixAndTheServerSeesIt() {
        HttpConfig config = new HttpConfig(null,
                List.of(target("/fast", 80), target("/medium", 20)), 200);

        try (HttpRunner runner = HttpRunner.create(config, 16)) {
            runner.run(RunContext.startingNow(2 * SECOND, 200 * MS, 0, 16));

            assertThat(hits("/fast"))
                    .withFailMessage("an 80/20 split must favour /fast; saw %d vs %d",
                            hits("/fast"), hits("/medium"))
                    .isGreaterThan(hits("/medium"));
        }
    }

    @Test
    void theSlowEndpointIsIdentifiableThoughTheRunnerRowIsOneNumber() {
        HttpConfig config = new HttpConfig(null,
                List.of(target("/fast", 50), target("/slow", 50)), 16);

        try (HttpRunner runner = HttpRunner.create(config, 16)) {
            runner.run(RunContext.startingNow(2 * SECOND, 200 * MS, 0, 16));

            Map<String, BucketSeries> breakdown = runner.targetBreakdown();
            BucketSeries fast = breakdown.get(QueryLabels.of("t", 0, "GET /fast"));
            BucketSeries slow = breakdown.get(QueryLabels.of("t", 1, "GET /slow"));
            assertThat(fast).isNotNull();
            assertThat(slow).isNotNull();

            long fastP50 = fast.mergedServiceTime().getValueAtPercentile(50);
            long slowP50 = slow.mergedServiceTime().getValueAtPercentile(50);
            assertThat(slowP50)
                    .withFailMessage("/slow sleeps 80ms and /fast does not; got %dns vs %dns",
                            slowP50, fastP50)
                    .isGreaterThan(fastP50 * 2);

            assertThat(slowP50).isBetween(75 * MS, 200 * MS);
            assertThat(fastP50).isLessThan(40 * MS);
        }
    }

    @Test
    void oneFailingEndpointIsNotAveragedIntoAHealthyMix() {
        HttpConfig config = new HttpConfig(null,
                List.of(target("/fast", 25), target("/medium", 25), target("/broken", 25)), 120);

        try (HttpRunner runner = HttpRunner.create(config, 16)) {
            PacedLoop.LoopResult result = runner.run(RunContext.startingNow(2 * SECOND, 200 * MS, 0, 16));

            Map<String, BucketSeries> breakdown = runner.targetBreakdown();
            BucketSeries broken = breakdown.get(QueryLabels.of("t", 2, "GET /broken"));
            BucketSeries fine = breakdown.get(QueryLabels.of("t", 0, "GET /fast"));

            assertThat(broken.errorCount()).isEqualTo(broken.totalCount());
            assertThat(fine.errorCount()).isZero();

            long attributedErrors = breakdown.values().stream()
                    .mapToLong(BucketSeries::errorCount).sum();
            assertThat(attributedErrors).isEqualTo(result.series().errorCount());

            assertThat(broken.statusCounts()).containsEntry(503, broken.totalCount());
        }
    }

    @Test
    void anUnweightedListIsAnEvenSplitRatherThanADeadRunner() {
        HttpConfig config = new HttpConfig(null,
                List.of(target("/fast", 0), target("/medium", 0)), 100);

        try (HttpRunner runner = HttpRunner.create(config, 8)) {
            PacedLoop.LoopResult result = runner.run(RunContext.startingNow(SECOND, 200 * MS, 0, 8));

            assertThat(result.executedCount()).isGreaterThan(0);
            assertThat(runner.targetBreakdown()).hasSize(2);
            assertThat(hits("/fast")).isGreaterThan(0);
            assertThat(hits("/medium")).isGreaterThan(0);
        }
    }

    @Test
    void theSingleTargetShapeStillWorksExactlyAsBefore() {
        HttpConfig config = new HttpConfig(
                new HttpConfig.Target("GET", url("/fast"), "", Map.of()), 100);

        try (HttpRunner runner = HttpRunner.create(config, 8)) {
            PacedLoop.LoopResult result = runner.run(RunContext.startingNow(SECOND, 200 * MS, 0, 8));

            assertThat(result.executedCount()).isGreaterThan(0);
            assertThat(result.series().errorCount()).isZero();

            assertThat(runner.targetBreakdown()).hasSize(1);
        }
    }

    @Test
    void labelsDropTheSharedHostButKeepItWhenTargetsSpanHosts() {
        assertThat(QueryLabels.forTargets(List.of("GET", "POST"),
                List.of("http://api.example.com/products", "http://api.example.com/orders")))
                .containsExactly("t1 GET /products", "t2 POST /orders");

        assertThat(QueryLabels.forTargets(List.of("GET", "GET"),
                List.of("http://a.example.com/health", "http://b.example.com/health")))
                .containsExactly("t1 GET a.example.com/health", "t2 GET b.example.com/health");
    }
}
