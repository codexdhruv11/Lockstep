package com.lockstep.verify;

import static org.assertj.core.api.Assertions.assertThat;

import com.lockstep.analysis.PrometheusScrape;
import com.lockstep.analysis.TargetMetrics;
import com.lockstep.report.CliTables;
import com.lockstep.runner.MetricsObserver;
import com.sun.net.httpserver.HttpServer;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * Checks the metrics reading against payloads written by hand, so every expected figure is
 * arithmetic rather than observation.
 *
 * <p>This is the easiest oracle in the project and worth having anyway: the parser has to cope
 * with escaped label values, {@code +Inf} in histogram buckets, and the difference between a
 * metric that reads zero and one that is not exported at all. Each of those is a quiet wrong
 * answer rather than a crash, which is the kind this project keeps finding.
 */
final class TargetMetricsOracleTest {
    private static final long MS = 1_000_000L;

    private static final List<String> LEDGER = new ArrayList<>();

    private static void record(String claim, Object expected, Object reported, boolean ok) {
        LEDGER.add("  %-38s expected %-16s reported %-16s %s".formatted(
                claim, expected, reported, ok ? "MATCH" : "MISMATCH"));
    }

    private static void printLedger(String fixture) {
        System.out.println("\n=== oracle verification · " + fixture + " ===");
        LEDGER.forEach(System.out::println);
        LEDGER.clear();
    }

    // --- the parser ------------------------------------------------------------------------------

    @Test
    void theTextFormatIsParsedIncludingItsAwkwardCases() {
        String body = """
                # HELP http_server_requests_seconds Duration
                # TYPE http_server_requests_seconds summary
                http_server_requests_seconds_count{uri="/a",status="200"} 120.0
                http_server_requests_seconds_count{uri="/b",status="200"} 80.0
                http_server_requests_seconds_sum{uri="/a",status="200"} 6.0
                http_server_requests_seconds_sum{uri="/b",status="200"} 4.0
                http_server_requests_seconds_bucket{le="+Inf"} 200.0
                hikaricp_connections_active{pool="main"} 7.0
                hikaricp_connections_max{pool="main"} 20.0
                process_cpu_usage 0.42
                a_metric_with_escapes{label="say \\"hi\\"",other="x"} 1.0
                with_timestamp 5.0 1696118400000
                """;

        PrometheusScrape scrape = PrometheusScrape.parse(body);

        record("count summed across labels", 200.0,
                scrape.sum("http_server_requests_seconds_count"),
                scrape.sum("http_server_requests_seconds_count") == 200.0);
        record("seconds summed across labels", 10.0,
                scrape.sum("http_server_requests_seconds_sum"),
                scrape.sum("http_server_requests_seconds_sum") == 10.0);
        record("gauge read", 7.0, scrape.sum("hikaricp_connections_active"),
                scrape.sum("hikaricp_connections_active") == 7.0);
        record("value with a trailing timestamp", 5.0, scrape.sum("with_timestamp"),
                scrape.sum("with_timestamp") == 5.0);
        record("escaped label value", "say \"hi\"",
                scrape.named("a_metric_with_escapes").get(0).label("label"),
                "say \"hi\"".equals(scrape.named("a_metric_with_escapes").get(0).label("label")));
        record("absent metric", -1.0, scrape.sum("not_exported"),
                scrape.sum("not_exported") == -1.0);
        printLedger("prometheus text format");

        assertThat(scrape.sum("http_server_requests_seconds_count")).isEqualTo(200.0);
        assertThat(scrape.sum("http_server_requests_seconds_sum")).isEqualTo(10.0);
        assertThat(scrape.named("a_metric_with_escapes").get(0).label("label"))
                .withFailMessage("an escaped quote in a label must not truncate the value")
                .isEqualTo("say \"hi\"");
        assertThat(scrape.sum("with_timestamp"))
                .withFailMessage("a sample may carry a timestamp after the value")
                .isEqualTo(5.0);
        assertThat(scrape.sum("not_exported"))
                .withFailMessage("""
                        an absent metric must be distinguishable from one that reads zero. A pool \
                        with no connections in use and a pool whose metric is not exported are \
                        different facts.""")
                .isEqualTo(-1.0);
        assertThat(scrape.named("http_server_requests_seconds_bucket"))
                .withFailMessage("+Inf is a legal value and appears in every histogram")
                .hasSize(1);
    }

    @Test
    void namingDriftIsHandledByTryingSeveralNames() {
        PrometheusScrape micrometer = PrometheusScrape.parse(
                "http_server_requests_seconds_count 10.0\n");
        PrometheusScrape otel = PrometheusScrape.parse(
                "http_server_request_duration_seconds_count 10.0\n");

        assertThat(micrometer.firstPresent(
                TargetMetrics.REQUEST_COUNT_NAMES.toArray(String[]::new)))
                .isEqualTo("http_server_requests_seconds_count");
        assertThat(otel.firstPresent(TargetMetrics.REQUEST_COUNT_NAMES.toArray(String[]::new)))
                .isEqualTo("http_server_request_duration_seconds_count");
        assertThat(PrometheusScrape.parse("something_else 1.0\n")
                .firstPresent(TargetMetrics.REQUEST_COUNT_NAMES.toArray(String[]::new)))
                .withFailMessage("an unknown name must report absence, not pick a default")
                .isNull();
    }

    // --- the arithmetic -------------------------------------------------------------------------

    @Test
    void theQueueIsTheGapBetweenTheServersClockAndTheCallers() {
        // 200 requests took 10 seconds of server time: a 50ms mean. The caller saw 450ms.
        TargetMetrics metrics = new TargetMetrics(true, null, "http://t/metrics",
                200, 10.0, "http_server_requests_seconds_sum",
                -1, -1, -1, null, 0, 0, null, -1, -1, null, 40, java.util.Map.of());

        long serverMean = metrics.serverMeanNanos();
        long queueing = metrics.queueingNanos(450 * MS);

        record("server mean", "50ms", "%.1fms".formatted(serverMean / (double) MS),
                Math.abs(serverMean - 50 * MS) < MS);
        record("caller mean", "450ms", "450.0ms", true);
        record("queueing", "400ms", "%.1fms".formatted(queueing / (double) MS),
                Math.abs(queueing - 400 * MS) < MS);
        printLedger("queueing, 200 requests over 10s of server time");

        assertThat(serverMean).isEqualTo(50 * MS);
        assertThat(queueing)
                .withFailMessage("""
                        the server says it spent 50ms and the caller waited 450ms, so 400ms was \
                        spent queued. That is the figure the target's own clock cannot produce, \
                        because its clock starts when the work does.""")
                .isEqualTo(400 * MS);
    }

    @Test
    void millisecondsInTheMetricNameAreConvertedRatherThanMisread() {
        // The older OTel convention exposes milliseconds. 200 requests, 10,000ms total = 50ms.
        TargetMetrics metrics = new TargetMetrics(true, null, "http://t/metrics",
                200, 10_000.0, "http_server_duration_milliseconds_sum",
                -1, -1, -1, null, 0, 0, null, -1, -1, null, 40, java.util.Map.of());

        assertThat(metrics.serverMeanNanos())
                .withFailMessage("""
                        reading a milliseconds metric as seconds would report a 50 second mean \
                        instead of 50ms, and the queueing figure would go negative""")
                .isEqualTo(50 * MS);
    }

    @Test
    void absentFiguresAreNegativeRatherThanZero() {
        TargetMetrics none = new TargetMetrics(true, null, "http://t/metrics",
                0, 0, null, -1, -1, -1, null, 0, 0, null, -1, -1, null, 10, java.util.Map.of());

        assertThat(none.serverMeanNanos()).isNegative();
        assertThat(none.queueingNanos(100 * MS)).isNegative();
        assertThat(none.poolSaturation()).isNegative();
        assertThat(none.meanGcPauseNanos()).isNegative();
        assertThat(none.totalGcPauseNanos()).isNegative();
        assertThat(none.anythingFound()).isFalse();
    }

    @Test
    void poolSaturationAndGcShareAreComputedOverTheRun() {
        TargetMetrics metrics = new TargetMetrics(true, null, "http://t/metrics",
                100, 2.0, "http_server_requests_seconds_sum",
                18.4, 20, 20, "hikaricp_connections_active",
                12, 0.6, "jvm_gc_pause_seconds_count",
                0.74, 0.91, "process_cpu_usage", 40, java.util.Map.of());

        record("pool saturation", "100%", "%.0f%%".formatted(metrics.poolSaturation() * 100),
                metrics.poolSaturation() == 1.0);
        record("pool exhausted", true, metrics.poolExhausted(), metrics.poolExhausted());
        record("mean GC pause", "50ms",
                "%.0fms".formatted(metrics.meanGcPauseNanos() / (double) MS),
                metrics.meanGcPauseNanos() == 50 * MS);
        record("GC share of a 10s run", "6%",
                "%.0f%%".formatted(metrics.gcShareOfRun(10_000 * MS) * 100),
                Math.abs(metrics.gcShareOfRun(10_000 * MS) - 0.06) < 0.001);
        printLedger("pool and GC arithmetic");

        assertThat(metrics.poolSaturation()).isEqualTo(1.0);
        assertThat(metrics.poolExhausted()).isTrue();
        assertThat(metrics.meanGcPauseNanos()).isEqualTo(50 * MS);
        assertThat(metrics.gcShareOfRun(10_000 * MS))
                .isCloseTo(0.06, org.assertj.core.data.Offset.offset(0.001));
    }

    // --- end to end against a served payload ----------------------------------------------------

    @Test
    void readsDeltasFromARealEndpointAcrossTheRun() throws Exception {
        AtomicReference<String> payload = new AtomicReference<>("""
                http_server_requests_seconds_count 1000.0
                http_server_requests_seconds_sum 50.0
                hikaricp_connections_active 2.0
                hikaricp_connections_max 20.0
                jvm_gc_pause_seconds_count 5.0
                jvm_gc_pause_seconds_sum 0.1
                process_cpu_usage 0.1
                """);

        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/metrics", exchange -> {
            byte[] body = payload.get().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();

        try {
            String url = "http://localhost:" + server.getAddress().getPort() + "/metrics";
            TargetMetrics metrics;
            try (MetricsObserver observer = MetricsObserver.open(url)) {
                observer.before();
                observer.start();
                Thread.sleep(400);

                // The target serves more traffic: 200 more requests taking 10 more seconds, the
                // pool fills, and it collects garbage 3 more times for 0.3s.
                payload.set("""
                        http_server_requests_seconds_count 1200.0
                        http_server_requests_seconds_sum 60.0
                        hikaricp_connections_active 20.0
                        hikaricp_connections_max 20.0
                        jvm_gc_pause_seconds_count 8.0
                        jvm_gc_pause_seconds_sum 0.4
                        process_cpu_usage 0.9
                        """);
                Thread.sleep(700);
                metrics = observer.after();
            }

            record("available", true, metrics.available(), metrics.available());
            record("request count delta", 200, metrics.requestCountDelta(),
                    metrics.requestCountDelta() == 200);
            record("server seconds delta", 10.0, metrics.requestSecondsDelta(),
                    Math.abs(metrics.requestSecondsDelta() - 10.0) < 0.01);
            record("server mean", "50ms",
                    "%.1fms".formatted(metrics.serverMeanNanos() / (double) MS),
                    Math.abs(metrics.serverMeanNanos() - 50 * MS) < MS);
            record("pool peak", 20.0, metrics.poolActiveMax(), metrics.poolActiveMax() == 20.0);
            record("GC count delta", 3, metrics.gcPauseCountDelta(),
                    metrics.gcPauseCountDelta() == 3);
            record("GC seconds delta", 0.3, "%.2f".formatted(metrics.gcPauseSecondsDelta()),
                    Math.abs(metrics.gcPauseSecondsDelta() - 0.3) < 0.01);
            record("CPU peak", 0.9, metrics.cpuMax(), Math.abs(metrics.cpuMax() - 0.9) < 0.01);
            record("samples taken", "> 2", metrics.samples(), metrics.samples() > 2);
            printLedger("metrics observer against a served payload");

            assertThat(metrics.requestCountDelta())
                    .withFailMessage("the counter went from 1000 to 1200")
                    .isEqualTo(200);
            assertThat(metrics.serverMeanNanos())
                    .withFailMessage("10 more seconds over 200 more requests is a 50ms mean")
                    .isEqualTo(50 * MS);
            assertThat(metrics.gcPauseCountDelta()).isEqualTo(3);
            assertThat(metrics.poolActiveMax())
                    .withFailMessage("""
                            the pool was sampled at 2 and later at 20, and a gauge's peak is only \
                            visible to something sampling through the run — a before-and-after \
                            pair would have missed it entirely""")
                    .isEqualTo(20.0);
            assertThat(metrics.cpuMax()).isCloseTo(0.9, org.assertj.core.data.Offset.offset(0.01));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void anUnreachableEndpointIsRefusedBeforeTheRunStarts() {
        assertThat(org.assertj.core.api.Assertions.catchThrowable(() -> {
            try (MetricsObserver observer =
                    MetricsObserver.open("http://localhost:1/metrics")) {
                observer.before();
            }
        }))
                .withFailMessage("failing fast beats running a load test and reporting at the end "
                        + "that the observation never worked")
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(org.assertj.core.api.Assertions.catchThrowable(
                () -> MetricsObserver.open("not-a-url")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // --- rendering ------------------------------------------------------------------------------

    @Test
    void theReportNamesTheQueueAndTheMissingMetrics() {
        TargetMetrics metrics = new TargetMetrics(true, null, "http://t/metrics",
                200, 10.0, "http_server_requests_seconds_sum",
                18.4, 20, 20, "hikaricp_connections_active",
                12, 0.6, "jvm_gc_pause_seconds_count",
                0.74, 0.91, "process_cpu_usage", 40,
                java.util.Map.of("process CPU", "process_cpu_usage"));

        String out = CliTables.targetMetricsTable(metrics, 450 * MS, 10_000 * MS);

        assertThat(out).contains("400ms was queueing");
        assertThat(out).contains("cannot see because it starts when the work does");
        assertThat(out).contains("exhausted");
        assertThat(out)
                .withFailMessage("6%% of a run spent collecting garbage is a finding, not a note")
                .contains("collecting garbage");
        assertThat(out).contains("not exposed");
        assertThat(out)
                .withFailMessage("a missing metric must be named as missing rather than as zero")
                .contains("rather than as zero");
    }

    @Test
    void anEndpointWithNothingRecognisableSaysSo() {
        TargetMetrics metrics = new TargetMetrics(true, null, "http://t/metrics",
                0, 0, null, -1, -1, -1, null, 0, 0, null, -1, -1, null, 10, java.util.Map.of());

        String out = CliTables.targetMetricsTable(metrics, 100 * MS, 1_000 * MS);

        assertThat(out).contains("exposed no metric this knows how to read");
    }
}
