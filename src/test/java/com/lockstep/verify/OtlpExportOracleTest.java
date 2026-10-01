package com.lockstep.verify;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lockstep.config.HttpConfig;
import com.lockstep.core.RunContext;
import com.lockstep.core.RunCoordinator;
import com.lockstep.runner.OtlpExporter;
import com.sun.net.httpserver.HttpServer;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * Checks what the exporter actually puts on the wire, by standing in for a collector and keeping
 * the body.
 *
 * <p>A broken export is the quietest failure in this project: the POST succeeds, the collector
 * accepts a payload it cannot interpret, and nothing appears in a dashboard anyone looks at until
 * long afterwards. So the body is parsed back and checked against the OTLP structure and against
 * figures the test already knows.
 */
final class OtlpExportOracleTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final long MS = 1_000_000L;
    private static final long SECOND = 1_000_000_000L;

    private static final List<String> LEDGER = new ArrayList<>();

    private static void record(String claim, Object expected, Object reported, boolean ok) {
        LEDGER.add("  %-40s expected %-14s reported %-14s %s".formatted(
                claim, expected, reported, ok ? "MATCH" : "MISMATCH"));
    }

    private static void printLedger(String fixture) {
        System.out.println("\n=== oracle verification · " + fixture + " ===");
        LEDGER.forEach(System.out::println);
        LEDGER.clear();
    }

    private record Collector(HttpServer server, int port, AtomicReference<String> body,
            AtomicInteger posts, AtomicReference<String> contentType) implements AutoCloseable {
        @Override
        public void close() {
            server.stop(0);
        }
    }

    private static Collector startCollector(int status) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        AtomicReference<String> body = new AtomicReference<>();
        AtomicReference<String> contentType = new AtomicReference<>();
        AtomicInteger posts = new AtomicInteger();
        server.createContext("/v1/metrics", exchange -> {
            posts.incrementAndGet();
            contentType.set(exchange.getRequestHeaders().getFirst("content-type"));
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] response = "{}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, response.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(response);
            }
        });
        server.start();
        return new Collector(server, server.getAddress().getPort(), body, posts, contentType);
    }

    /** A real finished run against a target that does nothing, so the shape is realistic. */
    private static RunCoordinator.RunResult aRun(int port) {
        HttpConfig http = new HttpConfig(new HttpConfig.Target("GET",
                "http://localhost:" + port + "/nothing", null, Map.of(), 1), 20);
        var config = new com.lockstep.config.Config(2 * SECOND, SECOND, 0, 8,
                http, null, null, List.of());
        return RunCoordinator.execute(config, null, 0, 0);
    }

    @Test
    void theExportedBodyIsValidOtlpWithTheRunsRealFigures() throws Exception {
        try (Collector collector = startCollector(200)) {
            // A target that 404s is fine: the point is the numbers, and errors must be exported
            // as faithfully as successes.
            RunCoordinator.RunResult result = aRun(collector.port());
            var loop = result.byRunner().get("http");
            var summary = loop.series().summarize("http", result.context().durationNanos());

            OtlpExporter exporter = OtlpExporter.open(
                    "http://localhost:" + collector.port() + "/v1/metrics", "my-service");
            String problem = exporter.export(result);

            assertThat(problem).withFailMessage("export failed: %s", problem).isNull();
            assertThat(collector.posts().get()).isEqualTo(1);

            JsonNode root = MAPPER.readTree(collector.body().get());
            JsonNode scope = root.path("resourceMetrics").get(0).path("scopeMetrics").get(0);
            JsonNode metrics = scope.path("metrics");

            // Index by name for checking.
            Map<String, JsonNode> byName = new java.util.LinkedHashMap<>();
            metrics.forEach(metric -> byName.put(metric.path("name").asText(), metric));

            long exportedRequests = byName.get("lockstep.requests")
                    .path("sum").path("dataPoints").get(0).path("asInt").asLong();
            long exportedP99 = byName.get("lockstep.latency.p99")
                    .path("gauge").path("dataPoints").get(0).path("asInt").asLong();
            String serviceName = root.path("resourceMetrics").get(0).path("resource")
                    .path("attributes").get(0).path("value").path("stringValue").asText();
            String runnerAttr = byName.get("lockstep.requests").path("sum").path("dataPoints")
                    .get(0).path("attributes").get(0).path("value").path("stringValue").asText();
            boolean monotonic = byName.get("lockstep.requests").path("sum")
                    .path("isMonotonic").asBoolean();
            int temporality = byName.get("lockstep.requests").path("sum")
                    .path("aggregationTemporality").asInt();

            record("content-type", "application/json", collector.contentType().get(),
                    "application/json".equals(collector.contentType().get()));
            record("metrics exported", OtlpExporter.metricsPerRunner(), metrics.size(),
                    metrics.size() == OtlpExporter.metricsPerRunner());
            record("service.name", "my-service", serviceName, "my-service".equals(serviceName));
            record("runner attribute", "http", runnerAttr, "http".equals(runnerAttr));
            record("requests match the run", summary.count(), exportedRequests,
                    exportedRequests == summary.count());
            record("p99 matches the run", summary.p99Nanos(), exportedP99,
                    exportedP99 == summary.p99Nanos());
            record("counters are monotonic sums", true, monotonic, monotonic);
            record("temporality is cumulative", 2, temporality, temporality == 2);
            printLedger("OTLP export against a stand-in collector");

            assertThat(metrics.size()).isEqualTo(OtlpExporter.metricsPerRunner());
            assertThat(exportedRequests)
                    .withFailMessage("""
                            the exported count must be the run's count. Exporting a figure that \
                            disagrees with the terminal output would make the dashboard and the \
                            report tell different stories about the same run.""")
                    .isEqualTo(summary.count());
            assertThat(exportedP99).isEqualTo(summary.p99Nanos());
            assertThat(serviceName).isEqualTo("my-service");
            assertThat(runnerAttr)
                    .withFailMessage("without a runner attribute an http and a db run collapse "
                            + "into one series")
                    .isEqualTo("http");
            assertThat(monotonic)
                    .withFailMessage("a request count only goes up; declaring it otherwise makes "
                            + "a collector compute rates wrongly")
                    .isTrue();
            assertThat(temporality)
                    .withFailMessage("a finished run's counters are cumulative, which is OTLP's 2")
                    .isEqualTo(2);
        }
    }

    @Test
    void everyExpectedMetricIsPresentAndWellFormed() throws Exception {
        try (Collector collector = startCollector(200)) {
            RunCoordinator.RunResult result = aRun(collector.port());
            OtlpExporter.open("http://localhost:" + collector.port() + "/v1/metrics", null)
                    .export(result);

            JsonNode metrics = MAPPER.readTree(collector.body().get())
                    .path("resourceMetrics").get(0).path("scopeMetrics").get(0).path("metrics");
            List<String> names = new ArrayList<>();
            metrics.forEach(metric -> names.add(metric.path("name").asText()));

            assertThat(names).contains(
                    "lockstep.requests", "lockstep.errors", "lockstep.shed", "lockstep.scheduled",
                    "lockstep.latency.p50", "lockstep.latency.p95", "lockstep.latency.p99",
                    "lockstep.latency.max", "lockstep.service_time.p99", "lockstep.rate");

            // Every metric must be exactly one of sum or gauge, and carry a unit and a timestamp.
            for (JsonNode metric : metrics) {
                String name = metric.path("name").asText();
                boolean isSum = metric.has("sum");
                boolean isGauge = metric.has("gauge");
                assertThat(isSum ^ isGauge)
                        .withFailMessage("%s must be exactly one of sum or gauge", name)
                        .isTrue();
                assertThat(metric.path("unit").asText())
                        .withFailMessage("%s has no unit, so a dashboard cannot label it", name)
                        .isNotBlank();
                JsonNode point = (isSum ? metric.path("sum") : metric.path("gauge"))
                        .path("dataPoints").get(0);
                assertThat(point.path("timeUnixNano").asLong())
                        .withFailMessage("%s has no timestamp", name)
                        .isGreaterThan(0);
            }

            // The rate is fractional and must not be truncated into an integer field.
            JsonNode rate = metrics.findValues("name").stream()
                    .filter(n -> n.asText().equals("lockstep.rate")).findFirst().orElseThrow();
            assertThat(rate).isNotNull();
            boolean rateIsDouble = false;
            for (JsonNode metric : metrics) {
                if (metric.path("name").asText().equals("lockstep.rate")) {
                    rateIsDouble = metric.path("gauge").path("dataPoints").get(0).has("asDouble");
                }
            }
            assertThat(rateIsDouble)
                    .withFailMessage("a rate of 19.6/s exported as an integer becomes 19, and the "
                            + "dashboard disagrees with the report")
                    .isTrue();
        }
    }

    @Test
    void aCollectorThatRejectsThePayloadIsReportedRatherThanSwallowed() throws Exception {
        try (Collector collector = startCollector(400)) {
            RunCoordinator.RunResult result = aRun(collector.port());
            String problem = OtlpExporter.open(
                    "http://localhost:" + collector.port() + "/v1/metrics", "svc").export(result);

            assertThat(problem)
                    .withFailMessage("""
                            a rejected export is the quietest failure available: the run looks \
                            fine and nothing arrives in the dashboard. It has to be said out \
                            loud.""")
                    .isNotNull()
                    .contains("400");
        }
    }

    @Test
    void anUnreachableCollectorIsReportedWithoutFailingTheRun() {
        var config = new com.lockstep.config.Config(SECOND, 500 * MS, 0, 2,
                new HttpConfig(new HttpConfig.Target("GET", "http://localhost:1/x", null,
                        Map.of(), 1), 5), null, null, List.of());
        RunCoordinator.RunResult result = RunCoordinator.execute(config, null, 0, 0);

        String problem = OtlpExporter.open("http://localhost:1/v1/metrics", "svc").export(result);

        assertThat(problem)
                .withFailMessage("an unreachable collector must be reported, not thrown — the run "
                        + "already happened and its results are still valid")
                .isNotNull();
    }

    @Test
    void anInvalidEndpointIsRefusedUpFront() {
        assertThat(org.assertj.core.api.Assertions.catchThrowable(
                () -> OtlpExporter.open("not-a-url", "svc")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(org.assertj.core.api.Assertions.catchThrowable(
                () -> OtlpExporter.open("/v1/metrics", "svc")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aServiceNameWithQuotesDoesNotBreakTheJson() throws Exception {
        try (Collector collector = startCollector(200)) {
            RunCoordinator.RunResult result = aRun(collector.port());
            OtlpExporter.open("http://localhost:" + collector.port() + "/v1/metrics",
                    "say \"hello\"").export(result);

            // Would throw if the body were malformed.
            JsonNode root = MAPPER.readTree(collector.body().get());
            assertThat(root.path("resourceMetrics").get(0).path("resource").path("attributes")
                    .get(0).path("value").path("stringValue").asText())
                    .isEqualTo("say \"hello\"");
        }
    }
}
