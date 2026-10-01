package com.lockstep.verify;

import static org.assertj.core.api.Assertions.assertThat;

import com.lockstep.analysis.CapacityFinder;
import com.lockstep.analysis.CapacitySearch;
import com.lockstep.config.HttpConfig;
import com.lockstep.core.PacedLoop;
import com.lockstep.core.RunContext;
import com.lockstep.runner.http.HttpRunner;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

/**
 * Checks the capacity estimate against a target whose true capacity is arithmetic.
 *
 * <p>A server with a hard limit of {@code N} concurrent workers, each taking {@code D} seconds per
 * request, can complete exactly {@code N / D} requests a second and no more. That is not a
 * measurement or a guess: it is the definition of the fixture. So the estimate has something to be
 * right or wrong about, which is what every other test of this code lacked — the capacity logic
 * had 29 unit tests over hand-built buckets and had never been pointed at a target whose answer
 * was known.
 */
final class CapacityOracleTest {
    private static final long MS = 1_000_000L;
    private static final long SECOND = 1_000_000_000L;

    private static final List<String> LEDGER = new ArrayList<>();

    private static void record(String claim, Object expected, Object reported, boolean ok) {
        LEDGER.add("  %-40s expected %-16s reported %-16s %s".formatted(
                claim, expected, reported, ok ? "MATCH" : "MISMATCH"));
    }

    private static void printLedger(String fixture) {
        System.out.println("\n=== oracle verification · " + fixture + " ===");
        LEDGER.forEach(System.out::println);
        LEDGER.clear();
    }

    private record Target(HttpServer server, int port, int workers, long delayMillis)
            implements AutoCloseable {
        /** The most requests a second this server can finish, by construction. */
        double trueCapacity() {
            return workers / (delayMillis / 1000.0);
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }

    /**
     * A server with a hard concurrency limit. A fixed thread pool with a bounded queue and a
     * caller-runs policy would let the acceptor thread do work and exceed the limit, so the queue
     * is generous and the pool size is the only cap.
     */
    private static Target start(int workers, long delayMillis) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.setExecutor(new ThreadPoolExecutor(workers, workers, 0, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(10_000), Executors.defaultThreadFactory(),
                new ThreadPoolExecutor.AbortPolicy()));
        server.createContext("/work", exchange -> {
            try {
                Thread.sleep(delayMillis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
        return new Target(server, server.getAddress().getPort(), workers, delayMillis);
    }

    private static PacedLoop.LoopResult drive(int port, int rate, long durationNanos) {
        HttpConfig config = new HttpConfig(new HttpConfig.Target("GET",
                "http://localhost:" + port + "/work", null, Map.of(), 1), rate);
        try (HttpRunner runner = HttpRunner.create(config, 200)) {
            return runner.run(RunContext.startingNow(durationNanos, SECOND, 0, 200));
        }
    }

    // --- the throughput ceiling is real and is where arithmetic says ----------------------------

    @Test
    void theTargetsThroughputCeilingMatchesWorkersOverServiceTime() throws Exception {
        // 5 workers at 100ms each: exactly 50 requests a second, and no amount of offered load
        // can produce more.
        try (Target target = start(5, 100)) {
            double expected = target.trueCapacity();

            // Offer well past the ceiling. Goodput — completions per second of WALL CLOCK — is
            // what the target achieved. Dividing by the configured duration instead gives back
            // the offered rate, because with ample workers nothing is shed: the work queues and
            // finishes during the drain. That distinction is the whole point of this fixture.
            PacedLoop.LoopResult result = drive(target.port(), 200, 6 * SECOND);
            double goodput = result.goodputPerSecond();
            double windowRate = result.series()
                    .summarize("http", 6 * SECOND).achievedRatePerSecond();

            record("workers / service time", "%.0f/s".formatted(expected),
                    "%.1f/s".formatted(goodput),
                    goodput >= expected * 0.8 && goodput <= expected * 1.2);
            record("rate over the configured window", "200/s (the offered rate)",
                    "%.0f/s".formatted(windowRate), windowRate > expected * 2);
            record("wall clock", "~24s",
                    "%.1fs".formatted(result.elapsedNanos() / 1e9),
                    result.elapsedNanos() > 6 * SECOND);
            record("drain dominated the run", "yes",
                    result.drainDominated(6 * SECOND) ? "yes" : "no",
                    result.drainDominated(6 * SECOND));
            printLedger("throughput ceiling, 5 workers at 100ms");

            assertThat(goodput)
                    .withFailMessage("""
                            5 workers each taking 100ms can finish 50 requests a second by \
                            construction. %d completed over %.1fs of wall clock, which is \
                            %.1f/s.""",
                            result.executedCount(), result.elapsedNanos() / 1e9, goodput)
                    .isBetween(expected * 0.8, expected * 1.2);
            assertThat(result.drainDominated(6 * SECOND))
                    .withFailMessage("""
                            the run must have overrun its window, or this fixture is not \
                            exercising the case where the summary's rate overstates the target""")
                    .isTrue();
        }
    }

    // --- find-capacity brackets the true answer --------------------------------------------------

    @Test
    void findCapacityBracketsTheArithmeticAnswer() throws Exception {
        // 4 workers at 80ms: 50/s exactly.
        try (Target target = start(4, 80)) {
            double expected = target.trueCapacity();

            // The search as find-capacity runs it: a probe per multiple of a base rate.
            int baseRate = 20;
            CapacitySearch.Result search = CapacitySearch.search(multiplier -> {
                int rate = Math.max(1, (int) Math.round(baseRate * multiplier));
                PacedLoop.LoopResult loop = drive(target.port(), rate, 4 * SECOND);
                var summary = loop.series().summarize("http", 4 * SECOND);
                return new CapacitySearch.Measurement(rate,
                        loop.executedCount() / 4.0, summary.p99Nanos(),
                        loop.scheduledCount(), loop.executedCount(), "http");
            }, 7, 3);

            Double sustained = search.sustained() == null ? null
                    : search.sustained().measurement().requestedRatePerSecond();
            Double strained = search.strained() == null ? null
                    : search.strained().measurement().requestedRatePerSecond();

            record("true capacity", "%.0f/s".formatted(expected), "-", true);
            record("bracketed", "yes", search.bracketed() ? "yes" : "no", search.bracketed());
            record("sustained at", "<= %.0f/s".formatted(expected * 1.3),
                    sustained == null ? "none" : "%.0f/s".formatted(sustained),
                    sustained != null && sustained <= expected * 1.3);
            record("strained at", ">= %.0f/s".formatted(expected * 0.7),
                    strained == null ? "none" : "%.0f/s".formatted(strained),
                    strained != null && strained >= expected * 0.7);
            record("sustained below strained", "yes",
                    sustained != null && strained != null && sustained < strained ? "yes" : "no",
                    sustained != null && strained != null && sustained < strained);
            printLedger("find-capacity, 4 workers at 80ms (true capacity 50/s)");

            assertThat(search.bracketed())
                    .withFailMessage("""
                            the search must find a rate that holds and a rate that does not. \
                            Steps taken: %s""",
                            search.steps().stream()
                                    .map(o -> "%.0f/s %s".formatted(
                                            o.measurement().requestedRatePerSecond(), o.strain()))
                                    .toList())
                    .isTrue();
            assertThat(sustained)
                    .withFailMessage("""
                            a rate the target sustained must not be far above its arithmetic \
                            ceiling of %.0f/s, or the search is calling saturation healthy""",
                            expected)
                    .isLessThanOrEqualTo(expected * 1.3);
            assertThat(strained)
                    .withFailMessage("""
                            the rate at which it strained must not be far below %.0f/s, or the \
                            search is calling a healthy rate saturated and would send someone \
                            provisioning capacity they already have""", expected)
                    .isGreaterThanOrEqualTo(expected * 0.7);
            assertThat(sustained).isLessThan(strained);
        }
    }

    @Test
    void aTargetWithRoomToSpareIsNotReportedAsStrained() throws Exception {
        // 40 workers at 20ms: 2,000/s. Driving 30/s uses 1.5% of it.
        try (Target target = start(40, 20)) {
            PacedLoop.LoopResult result = drive(target.port(), 30, 12 * SECOND);
            var summary = result.series().summarize("http", 12 * SECOND);

            CapacityFinder.Capacity capacity = CapacityFinder.find(
                    result.series().buckets(), 200, 0, SECOND, 0,
                    (double) result.executedCount() / result.scheduledCount());

            record("delivered", "360", result.executedCount(),
                    result.executedCount() >= 350);
            record("shed", 0, result.shedCount(), result.shedCount() == 0);
            record("p50 (cold start aside)", "< 60ms",
                    "%.1fms".formatted(summary.p50Nanos() / (double) MS),
                    summary.p50Nanos() < 60 * MS);
            record("strain reported", "none", capacity.strained() ? "strain" : "none",
                    !capacity.strained());
            record("over capacity throughout", false, capacity.overCapacityThroughout(),
                    !capacity.overCapacityThroughout());
            printLedger("capacity, 40 workers at 20ms driven at 1.5% of the ceiling");

            assertThat(result.shedCount()).isZero();
            assertThat(capacity.strained())
                    .withFailMessage("""
                            the target has 2,000/s of capacity and was given 30/s. Reporting \
                            strain here would be a false alarm, and a tool that cries wolf at \
                            1.5%% utilisation gets ignored at 100%%.""")
                    .isFalse();
        }
    }

    @Test
    void aSaturatedRunIsNotReportedAsHealthy() throws Exception {
        // 2 workers at 100ms: 20/s. Driving 150/s is more than seven times the ceiling.
        try (Target target = start(2, 100)) {
            PacedLoop.LoopResult result = drive(target.port(), 150, 12 * SECOND);
            double delivery = (double) result.executedCount() / result.scheduledCount();
            double goodput = result.goodputPerSecond();
            var summary = result.series().summarize("http", 12 * SECOND);

            CapacityFinder.Capacity capacity = CapacityFinder.find(
                    result.series().buckets(), 200, 0, SECOND, 0, delivery);

            record("offered", "1,800", result.scheduledCount(),
                    result.scheduledCount() >= 1_700);
            record("goodput vs 20/s ceiling", "~20/s", "%.1f/s".formatted(goodput),
                    goodput < 40);
            record("delivery ratio", "(not the signal here)",
                    "%.0f%%".formatted(delivery * 100), true);
            record("p99 latency", "> 5s",
                    "%.1fs".formatted(summary.p99Nanos() / 1e9),
                    summary.p99Nanos() > 5 * SECOND);
            record("strain reported", "yes",
                    capacity.overCapacityThroughout() || capacity.strained() ? "yes" : "no",
                    capacity.overCapacityThroughout() || capacity.strained());
            printLedger("capacity, 2 workers at 100ms driven at 7x the ceiling");

            assertThat(goodput)
                    .withFailMessage("""
                            a 20/s target was given 150/s, so its goodput cannot be much above \
                            20/s however long the drain runs. Got %.1f/s over %.1fs.""",
                            goodput, result.elapsedNanos() / 1e9)
                    .isLessThan(40);
            // Delivery ratio is NOT the saturation signal when workers are plentiful: nothing is
            // shed, the queue simply drains afterwards. Latency is the signal, and it is enormous.
            assertThat(summary.p99Nanos())
                    .withFailMessage("""
                            with 200 workers nothing is shed — the shortfall appears as queueing \
                            delay instead. p99 was %.2fs against a 100ms service time, which is \
                            the honest signature of a saturated target.""",
                            summary.p99Nanos() / 1e9)
                    .isGreaterThan(5 * SECOND);
            assertThat(result.series().nonEmptyBuckets().size())
                    .withFailMessage("the fixture must populate fewer than the %d buckets the "
                            + "finder normally needs, or it is not exercising the defect",
                            CapacityFinder.MINIMUM_BUCKETS)
                    .isLessThan(CapacityFinder.MINIMUM_BUCKETS);
            assertThat(capacity.usable())
                    .withFailMessage("a run that shed most of its load has an answer even with "
                            + "only %d populated buckets", result.series().nonEmptyBuckets().size())
                    .isTrue();
            assertThat(capacity.overCapacityThroughout() || capacity.strained())
                    .withFailMessage("""
                            a 20/s target given 150/s, with p99 at %.1fs. Reporting no strain is \
                            the failure that matters — a tool telling someone a saturated system \
                            is healthy.""", summary.p99Nanos() / 1e9)
                    .isTrue();
        }
    }
}
