package com.lockstep.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.lockstep.analysis.SpikeCorrelator;
import com.lockstep.core.PacedLoop;
import com.lockstep.core.RunContext;
import com.lockstep.core.RunCoordinator;
import com.lockstep.stats.Bucket;
import com.lockstep.stats.HistogramRecorder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class HtmlReportRenderingTest {
    private static final long MS = 1_000_000L;
    private static final long SECOND = 1_000_000_000L;

    @TempDir
    Path tempDir;

    private record Rendered(String headline, String runners, String spikes, String shortfall,
            String footer, String queries, String plans, String slowlog, String failures,
            List<String> chartDatasets) {}

    private Rendered render(RunReport report) throws Exception {
        assumeTrue(nodeAvailable(), "node is not available — page rendering test skipped");

        Path template = tempDir.resolve("page.html");
        Path data = tempDir.resolve("data.json");
        Files.writeString(template, HtmlReport.render(report));
        Files.writeString(data, JsonExport.toJson(report));

        Path stub = copyResource("/dom-stub.js", tempDir.resolve("dom-stub.js"));
        Path runner = copyResource("/render-report.js", tempDir.resolve("render-report.js"));

        Process process = new ProcessBuilder("node", runner.toString(), stub.toString(),
                template.toString(), data.toString())
                .redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes());
        assertThat(process.waitFor())
                .withFailMessage("the page's own script failed to run:%n%s", output)
                .isZero();

        var json = new com.fasterxml.jackson.databind.ObjectMapper().readTree(output.strip());
        List<String> datasets = new java.util.ArrayList<>();
        json.get("chartDatasets").forEach(node -> datasets.add(node.asText()));
        return new Rendered(json.get("headline").asText(), json.get("runners").asText(),
                json.get("spikes").asText(), json.get("shortfall").asText(),
                json.get("footer").asText(), json.get("queries").asText(),
                json.get("plans").asText(), json.get("slowlog").asText(),
                json.get("failures").asText(), datasets);
    }

    private Path copyResource(String resource, Path target) throws Exception {
        try (var in = HtmlReportRenderingTest.class.getResourceAsStream(resource)) {
            Files.write(target, in.readAllBytes());
        }
        return target;
    }

    private static boolean nodeAvailable() {
        try {
            return new ProcessBuilder("node", "--version").start().waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private static Bucket bucket(int index, long p99Nanos, long count) {
        return new Bucket(index, index * SECOND, (index + 1) * SECOND, count, 0,
                p99Nanos / 2, p99Nanos / 2, p99Nanos - MS, p99Nanos, p99Nanos, p99Nanos);
    }

    private static RunCoordinator.RunResult run(boolean withHttp, boolean withDb, long dbP99) {
        Map<String, PacedLoop.LoopResult> runners = new LinkedHashMap<>();
        if (withHttp) {
            HistogramRecorder http = new HistogramRecorder(SECOND, 4);
            for (int b = 0; b < 4; b++) {
                for (int i = 0; i < 40; i++) {
                    http.record(b * SECOND, 15 * MS, 15 * MS, true, 200);
                }
            }
            runners.put("http", new PacedLoop.LoopResult(http.snapshot(), 160, 0, 0, 0, 0, 160, true));
        }
        if (withDb) {
            HistogramRecorder db = new HistogramRecorder(SECOND, 4);
            for (int b = 0; b < 4; b++) {
                for (int i = 0; i < 40; i++) {
                    db.record(b * SECOND, dbP99, dbP99, true, null);
                }
            }
            runners.put("db", new PacedLoop.LoopResult(db.snapshot(), 160, 0, 0, 0, 0, 160, true));
        }
        return new RunCoordinator.RunResult(
                new RunContext(0, Instant.parse("2026-09-24T10:00:00Z"), 4 * SECOND, SECOND, 0, 8, 0),
                runners, null);
    }

    private static RunReport reportFor(RunCoordinator.RunResult result, List<Bucket> appTimeline,
            Map<String, List<Bucket>> storage) {
        var correlation = SpikeCorrelator.correlate(appTimeline, storage,
                SpikeCorrelator.Thresholds.defaults());
        return RunReport.from(result, "0.1.0-TEST", correlation, null);
    }

    @Test
    void thePageRanksTheDatabaseQueriesByShareOfTime() throws Exception {
        var result = run(false, true, 5 * MS);
        RunReport base = reportFor(result, List.of(), Map.of());
        RunReport withQueries = new RunReport(base.tool(), base.schemaVersion(), base.toolVersion(),
                base.startedAt(), base.durationNanos(), base.bucketWidthNanos(), base.rampNanos(),
                base.concurrency(), base.percentilePrecision(), base.runners(), base.spikes(),
                base.capacity(), base.steps(),
                List.of(new RunReport.QueryReport("db", "q2 SELECT count(*) FROM orders",
                                10_000, 0, 0.98, 5 * MS, 5 * MS, 6 * MS, 7 * MS, 9 * MS, 2 * SECOND,
                                new RunReport.QueryPlanReport("EXPLAIN (ANALYZE, BUFFERS)", true,
                                        "Aggregate (actual time=2.9..2.9 rows=1)\n"
                                        + "  -> Seq Scan on orders (actual rows=69720)", null)),
                        new RunReport.QueryReport("db", "q1 SELECT * FROM audit_log",
                                10, 0, 0.02, 100 * MS, 100 * MS, 110 * MS, 120 * MS, 130 * MS, 2 * SECOND, null)),
                base.slowlog(), base.correlation());

        Rendered page = render(withQueries);

        assertThat(page.queries()).contains("q2 SELECT count(*) FROM orders", "98.0%", "10,000");
        assertThat(page.queries()).contains("q1 SELECT * FROM audit_log", "2.0%");
        assertThat(page.queries().indexOf("q2 SELECT"))
                .withFailMessage("the query owning the most database time must come first")
                .isLessThan(page.queries().indexOf("q1 SELECT"));

        assertThat(page.plans()).contains("Seq Scan on orders", "actual rows=69720");
        assertThat(page.plans()).contains("EXPLAIN (ANALYZE, BUFFERS)");
        assertThat(page.plans()).doesNotContain("estimates, not measurements");

        assertThat(page.plans()).doesNotContain("q1 SELECT * FROM audit_log");
    }

    @Test
    void thePageShowsTheRedisSlowlogAsTheServersOwnTiming() throws Exception {
        var result = run(false, true, 5 * MS);
        RunReport base = reportFor(result, List.of(), Map.of());
        RunReport withSlowlog = new RunReport(base.tool(), base.schemaVersion(), base.toolVersion(),
                base.startedAt(), base.durationNanos(), base.bucketWidthNanos(), base.rampNanos(),
                base.concurrency(), base.percentilePrecision(), base.runners(), base.spikes(),
                base.capacity(), base.steps(), base.queries(),
                new RunReport.SlowlogReport(List.of(
                        new RunReport.SlowlogEntryReport(41, 45_000, "KEYS sess:*", "172.17.0.1:5120")),
                        null),
                base.correlation());

        Rendered page = render(withSlowlog);

        assertThat(page.slowlog()).contains("KEYS sess:*", "45ms");
        assertThat(page.slowlog()).contains("server-wide");
    }

    @Test
    void anEmptySlowlogSaysWhyInsteadOfVanishing() throws Exception {
        var result = run(false, true, 5 * MS);
        RunReport base = reportFor(result, List.of(), Map.of());
        RunReport withNote = new RunReport(base.tool(), base.schemaVersion(), base.toolVersion(),
                base.startedAt(), base.durationNanos(), base.bucketWidthNanos(), base.rampNanos(),
                base.concurrency(), base.percentilePrecision(), base.runners(), base.spikes(),
                base.capacity(), base.steps(), base.queries(),
                new RunReport.SlowlogReport(List.of(),
                        "the server logged nothing slower than its own "
                        + "slowlog-log-slower-than (10000\u00b5s)"),
                base.correlation());

        assertThat(render(withNote).slowlog()).contains("slowlog-log-slower-than", "10000");
    }

    @Test
    void thePageNamesWhatFailedNotJustHowMuchDid() throws Exception {
        var result = run(true, false, 0);
        RunReport base = reportFor(result, List.of(), Map.of());
        var runner = base.runners().get(0);
        RunReport.RunnerReport withErrors = new RunReport.RunnerReport(
                runner.name(), runner.count(), runner.successCount(), 12, runner.achievedRatePerSecond(),
                runner.scheduledCount(), runner.expectedHits(), runner.shedCount(),
                runner.abandonedCount(), runner.lateFireCount(), runner.maxLatenessNanos(),
                runner.drainedCleanly(), runner.clampedEarlyCount(), runner.clampedLateCount(),
                runner.meanNanos(), runner.minNanos(), runner.p50Nanos(), runner.p90Nanos(),
                runner.p95Nanos(), runner.p99Nanos(), runner.maxNanos(), runner.serviceP99Nanos(),
                runner.statusCounts(),
                new java.util.LinkedHashMap<>(Map.of("ConnectException: Connection refused", 12L)),
                runner.buckets());
        RunReport report = new RunReport(base.tool(), base.schemaVersion(), base.toolVersion(),
                base.startedAt(), base.durationNanos(), base.bucketWidthNanos(), base.rampNanos(),
                base.concurrency(), base.percentilePrecision(), List.of(withErrors), base.spikes(),
                base.capacity(), base.steps(), base.queries(), base.slowlog(), base.correlation());

        Rendered page = render(report);

        assertThat(page.failures()).contains("ConnectException: Connection refused", "12");

        assertThat(page.runners()).contains("Min", "p90");
    }

    @Test
    void thePageRendersTheRunnersAndTheirFigures() throws Exception {
        var result = run(true, true, 5 * MS);
        Rendered page = render(reportFor(result, result.byRunner().get("http").series().buckets(),
                Map.of("db", result.byRunner().get("db").series().buckets())));

        assertThat(page.runners()).contains("http").contains("db").contains("ms");
        assertThat(page.chartDatasets()).contains("http p99", "db p99", "active users (estimated)");
        assertThat(page.footer()).contains("1.0%");
    }

    @Test
    void aMaskedSpikeIsRenderedAsSuchAndNotAsSilence() throws Exception {
        var result = run(true, true, 600 * MS);
        Rendered page = render(reportFor(result, result.byRunner().get("http").series().buckets(),
                Map.of("db", result.byRunner().get("db").series().buckets())));

        assertThat(page.headline()).containsIgnoringCase("masked");
        assertThat(page.spikes()).contains("db").contains("db-only");
    }

    @Test
    void aRunWithNoApplicationTimelineDoesNotClaimThereWereNoSpikes() throws Exception {
        var result = run(false, true, 900 * MS);
        Rendered page = render(reportFor(result, List.of(),
                Map.of("db", result.byRunner().get("db").series().buckets())));

        assertThat(page.headline()).doesNotContain("No storage spikes");
        assertThat(page.headline()).containsIgnoringCase("could not be correlated");
        assertThat(page.headline()).containsIgnoringCase("unknown");
    }

    @Test
    void aRunWithNoStorageRunnerSaysSoRatherThanReportingItClean() throws Exception {
        var result = run(true, false, 0);
        Rendered page = render(reportFor(result, result.byRunner().get("http").series().buckets(),
                Map.of()));

        assertThat(page.headline()).containsIgnoringCase("no storage layer was measured");
    }

    @Test
    void aShortfallIsShownOnThePageNotOnlyInTheTerminal() throws Exception {
        Map<String, PacedLoop.LoopResult> runners = new LinkedHashMap<>();
        HistogramRecorder http = new HistogramRecorder(SECOND, 4);
        for (int i = 0; i < 40; i++) {
            http.record(0, 15 * MS, 15 * MS, true, 200);
        }
        runners.put("http", new PacedLoop.LoopResult(http.snapshot(), 100, 60, 0, 5, 20 * MS, 100, true));
        var result = new RunCoordinator.RunResult(
                new RunContext(0, Instant.EPOCH, 4 * SECOND, SECOND, 0, 8, 0), runners, null);

        Rendered page = render(RunReport.from(result, "t"));

        assertThat(page.shortfall()).contains("60 shed").contains("did not deliver");
    }
}
