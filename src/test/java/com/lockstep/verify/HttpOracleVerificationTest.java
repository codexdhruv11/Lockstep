package com.lockstep.verify;

import static org.assertj.core.api.Assertions.assertThat;

import com.lockstep.config.HttpConfig;
import com.lockstep.core.PacedLoop;
import com.lockstep.core.RunContext;
import com.lockstep.runner.http.HttpRunner;
import com.lockstep.stats.RunnerSummary;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/**
 * Checks the HTTP runner's reported latencies and percentiles against a target whose response
 * times are dictated, not measured.
 *
 * <p>Everywhere else the target's latency is whatever the target happens to do, so a test can only
 * ask whether the number looks sensible. Here the server sleeps for an exact duration chosen by
 * the test, which makes the true percentiles arithmetic rather than observation: if one request in
 * five sleeps 500ms and the rest sleep 100ms, the median is 100ms and the 90th percentile is
 * 500ms, and any other answer is a defect in the recorder.
 *
 * <p>Durations are deliberately large — 100ms and 500ms — so that loopback and scheduling
 * overhead, a few milliseconds at most, cannot account for a discrepancy.
 */
final class HttpOracleVerificationTest {
    private static final long MS = 1_000_000L;
    private static final long SECOND = 1_000_000_000L;

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

    private record Target(HttpServer server, int port) implements AutoCloseable {
        @Override
        public void close() {
            server.stop(0);
        }
    }

    /**
     * A server whose delay is chosen by a counter rather than a clock or a random source, so the
     * proportion of slow responses is exact: every {@code slowEvery}-th request sleeps
     * {@code slowMillis}, the rest sleep {@code fastMillis}.
     */
    private static Target serverWithFixedDelays(long fastMillis, long slowMillis, int slowEvery)
            throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        AtomicLong served = new AtomicLong();
        server.createContext("/fixed", exchange -> {
            long n = served.incrementAndGet();
            long delay = slowEvery > 0 && n % slowEvery == 0 ? slowMillis : fastMillis;
            try {
                Thread.sleep(delay);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            respond(exchange);
        });
        server.start();
        return new Target(server, server.getAddress().getPort());
    }

    private static void respond(HttpExchange exchange) throws IOException {
        byte[] body = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("content-type", "application/json");
        exchange.sendResponseHeaders(200, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    private static PacedLoop.LoopResult drive(int port, int rate, int workers, long durationNanos) {
        HttpConfig config = new HttpConfig(new HttpConfig.Target("GET",
                "http://localhost:" + port + "/fixed", null, Map.of(), 1), rate);
        try (HttpRunner runner = HttpRunner.create(config, workers)) {
            return runner.run(RunContext.startingNow(durationNanos, 500 * MS, 0, workers));
        }
    }

    // --- a single known delay --------------------------------------------------------------------

    @Test
    void aFixedDelayIsReportedAsThatDelay() throws Exception {
        final long delayMillis = 200;
        try (Target target = serverWithFixedDelays(delayMillis, delayMillis, 0)) {
            // 10/s with 40 workers: 10 x 0.2s = 2 concurrent requests needed, so nothing queues
            // and latency is the server's delay plus loopback overhead.
            PacedLoop.LoopResult result = drive(target.port(), 10, 40, 4 * SECOND);
            RunnerSummary summary = result.series().summarize("http", 4 * SECOND);

            double p50 = summary.p50Nanos() / (double) MS;
            double p99 = summary.p99Nanos() / (double) MS;
            boolean p50Ok = p50 >= delayMillis * 0.95 && p50 <= delayMillis * 1.15;

            record("p50 (ms)", delayMillis, "%.1f".formatted(p50), p50Ok);
            record("p99 (ms)", "~" + delayMillis, "%.1f".formatted(p99),
                    p99 >= delayMillis * 0.95 && p99 <= delayMillis * 1.5);
            record("min >= server delay", ">= " + delayMillis,
                    "%.1f".formatted(summary.minNanos() / (double) MS),
                    summary.minNanos() >= delayMillis * MS * 0.95);
            record("success", "100.0%", "%.1f%%".formatted(summary.successRate() * 100),
                    summary.successRate() == 1.0);
            printLedger("http latency, every response delayed " + delayMillis + "ms");

            assertThat(p50)
                    .withFailMessage("""
                            the server sleeps %dms on every request, so the median latency is \
                            %dms plus a little loopback overhead; the recorder says %.2fms""",
                            delayMillis, delayMillis, p50)
                    .isBetween(delayMillis * 0.95, delayMillis * 1.15);
            assertThat(summary.minNanos())
                    .withFailMessage("""
                            no response can arrive faster than the server's own sleep of %dms; \
                            the recorder's minimum is %.2fms, which would mean it is timing \
                            something other than the request""",
                            delayMillis, summary.minNanos() / (double) MS)
                    .isGreaterThanOrEqualTo((long) (delayMillis * MS * 0.95));
            assertThat(summary.successRate()).isEqualTo(1.0);
        }
    }

    // --- a known distribution, so the percentiles are arithmetic --------------------------------

    @Test
    void percentilesMatchAKnownBimodalDistribution() throws Exception {
        final long fast = 100;
        final long slow = 500;
        final int slowEvery = 5;   // exactly one in five is slow

        try (Target target = serverWithFixedDelays(fast, slow, slowEvery)) {
            // Enough workers that nothing queues: 8/s x 0.5s worst case = 4 concurrent.
            PacedLoop.LoopResult result = drive(target.port(), 8, 60, 8 * SECOND);
            RunnerSummary summary = result.series().summarize("http", 8 * SECOND);

            double p50 = summary.p50Nanos() / (double) MS;
            double p90 = summary.p90Nanos() / (double) MS;
            double max = summary.maxNanos() / (double) MS;

            // 80% of responses take `fast`, so the median is `fast`. The 90th percentile lies in
            // the slow fifth, so it is `slow`.
            boolean p50Ok = p50 >= fast * 0.95 && p50 <= fast * 1.2;
            boolean p90Ok = p90 >= slow * 0.95 && p90 <= slow * 1.2;

            record("requests", "> 40", result.executedCount(), result.executedCount() > 40);
            record("p50 (80% are fast)", fast + "ms", "%.1fms".formatted(p50), p50Ok);
            record("p90 (slow fifth)", slow + "ms", "%.1fms".formatted(p90), p90Ok);
            record("max", "~" + slow + "ms", "%.1fms".formatted(max),
                    max >= slow * 0.95 && max <= slow * 1.4);
            printLedger("percentiles, 4-in-5 at " + fast + "ms and 1-in-5 at " + slow + "ms");

            assertThat(p50)
                    .withFailMessage("""
                            four responses in five take %dms, so the median must be %dms; the \
                            recorder says %.2fms""", fast, fast, p50)
                    .isBetween(fast * 0.95, fast * 1.2);
            assertThat(p90)
                    .withFailMessage("""
                            one response in five takes %dms, so the 90th percentile falls inside \
                            that fifth and must be %dms; the recorder says %.2fms""",
                            slow, slow, p90)
                    .isBetween(slow * 0.95, slow * 1.2);
            assertThat(max)
                    .withFailMessage("nothing is slower than the %dms sleep; got %.2fms", slow, max)
                    .isLessThan(slow * 1.4);
        }
    }

    // --- the ledger invariant, under real saturation ---------------------------------------------

    @Test
    void everyScheduledRequestIsAccountedForUnderRealSaturation() throws Exception {
        // 300/s at 250ms a response needs 75 concurrent workers; giving 8 guarantees the loop
        // sheds, which is the case the ledger exists to describe.
        try (Target target = serverWithFixedDelays(250, 250, 0)) {
            PacedLoop.LoopResult result = drive(target.port(), 300, 8, 4 * SECOND);

            long executed = result.executedCount();
            long shed = result.shedCount();
            long abandoned = result.abandonedCount();
            long scheduled = result.scheduledCount();
            long sum = executed + shed + abandoned;

            record("scheduled", scheduled, scheduled, true);
            record("executed + shed + abandoned", scheduled, sum, sum == scheduled);
            record("shed (must be > 0 here)", "> 0", shed, shed > 0);
            record("ledger balances", "yes",
                    result.accountsForEveryScheduledOperation() ? "yes" : "no",
                    result.accountsForEveryScheduledOperation());
            printLedger("delivery ledger under saturation");

            assertThat(shed)
                    .withFailMessage("""
                            8 workers cannot serve 300/s of 250ms responses, so load must have \
                            been shed; if nothing was shed this fixture is not testing what it \
                            claims to""")
                    .isPositive();
            assertThat(sum)
                    .withFailMessage("""
                            every scheduled operation must be executed, shed or abandoned — \
                            exactly once. scheduled=%d, executed=%d, shed=%d, abandoned=%d, \
                            sum=%d. A gap here means the report can silently lose requests.""",
                            scheduled, executed, shed, abandoned, sum)
                    .isEqualTo(scheduled);
            assertThat(result.accountsForEveryScheduledOperation()).isTrue();
        }
    }

    @Test
    void anUnsaturatedRunShedsNothingAndStillBalances() throws Exception {
        try (Target target = serverWithFixedDelays(20, 20, 0)) {
            PacedLoop.LoopResult result = drive(target.port(), 20, 40, 3 * SECOND);

            record("shed", 0, result.shedCount(), result.shedCount() == 0);
            record("abandoned", 0, result.abandonedCount(), result.abandonedCount() == 0);
            record("executed == scheduled", result.scheduledCount(), result.executedCount(),
                    result.executedCount() == result.scheduledCount());
            printLedger("delivery ledger below saturation");

            assertThat(result.shedCount())
                    .withFailMessage("40 workers serving 20/s of 20ms responses has ample "
                            + "headroom; shedding here would be a defect")
                    .isZero();
            assertThat(result.executedCount()).isEqualTo(result.scheduledCount());
            assertThat(result.accountsForEveryScheduledOperation()).isTrue();
        }
    }
}
