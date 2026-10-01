package com.lockstep.verify;

import static org.assertj.core.api.Assertions.assertThat;

import com.lockstep.analysis.CapacitySearch;
import com.lockstep.config.HttpConfig;
import com.lockstep.core.PacedLoop;
import com.lockstep.core.RunContext;
import com.lockstep.report.CliTables;
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
 * Checks that a step shorter than the latency it is measuring is refused rather than reported.
 *
 * <p>This is the gap that {@code CapacityOracleTest} could not see. That suite built fixtures with
 * a service time of 100ms and measured them over 10-second steps, so the latency was always two
 * orders of magnitude below the step and the distortion never arose. Every assertion passed and
 * the defect sat underneath them.
 *
 * <p>It surfaced on a real target: an endpoint whose sustainable rate was 6/s reported "sustains
 * 21/s" - three and a half times too high - because its p99 was over 30 seconds against the
 * default 10-second step. Requests issued inside a step did not finish inside it; they completed
 * during the drain and were counted as delivered, so the step reported full delivery for a rate it
 * had never sustained.
 *
 * <p>The lesson is about the method, not only the code: <b>an oracle only catches a pathology the
 * fixture contains.</b> These fixtures put the latency deliberately above the step.
 */
final class StepTooShortOracleTest {
    private static final long MS = 1_000_000L;
    private static final long SECOND = 1_000_000_000L;

    private static final List<String> LEDGER = new ArrayList<>();

    private static void record(String claim, Object expected, Object reported, boolean ok) {
        LEDGER.add("  %-42s expected %-18s reported %-18s %s".formatted(
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

    /** A server with a hard worker cap, so its capacity is {@code workers / delay}. */
    private static Target start(int workers, long delayMillis) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.setExecutor(new ThreadPoolExecutor(workers, workers, 0, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(10_000), Executors.defaultThreadFactory(),
                new ThreadPoolExecutor.AbortPolicy()));
        server.createContext("/slow", exchange -> {
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
        return new Target(server, server.getAddress().getPort());
    }

    private static CapacitySearch.Measurement measure(int port, int rate, long stepNanos) {
        HttpConfig config = new HttpConfig(new HttpConfig.Target("GET",
                "http://localhost:" + port + "/slow", null, Map.of(), 1), rate);
        try (HttpRunner runner = HttpRunner.create(config, 60)) {
            PacedLoop.LoopResult loop =
                    runner.run(RunContext.startingNow(stepNanos, SECOND, 0, 60));
            var summary = loop.series().summarize("http", stepNanos);
            double seconds = stepNanos / 1_000_000_000.0;
            return new CapacitySearch.Measurement(rate,
                    seconds > 0 ? loop.executedCount() / seconds : 0,
                    summary.p99Nanos(), loop.scheduledCount(), loop.executedCount(), "http",
                    stepNanos);
        }
    }

    // --- the case the earlier oracle could not see ----------------------------------------------

    /**
     * 2 workers at 1.5s each: a true capacity of 1.33/s. Driven at 20/s over a 3-second step, the
     * queue builds and the p99 goes well past the step, which is exactly the shape that made a
     * real target report three and a half times its capacity.
     */
    @Test
    void aStepShorterThanTheLatencyIsRefusedRatherThanReported() throws Exception {
        try (Target target = start(2, 1_500)) {
            long step = 3 * SECOND;
            CapacitySearch.Measurement m = measure(target.port(), 20, step);

            record("p99 against the 3s step", "> 1s (a third of it)",
                    "%.1fs".formatted(m.p99Nanos() / 1e9),
                    m.p99Nanos() > step / 3);
            record("step declared too short", true, m.stepTooShort(), m.stepTooShort());
            record("recommended step", ">= 10x the p99",
                    "%.0fs".formatted(m.recommendedStepNanos() / 1e9),
                    m.recommendedStepNanos() >= m.p99Nanos() * 10);
            printLedger("2 workers at 1.5s, driven at 20/s over a 3s step");

            assertThat(m.p99Nanos())
                    .withFailMessage("""
                            the fixture is meant to produce a p99 past a third of the step; if it \
                            does not, it proves nothing. p99 was %dms against a %dms step""",
                            m.p99Nanos() / MS, step / MS)
                    .isGreaterThan(step / 3);

            assertThat(m.stepTooShort())
                    .withFailMessage("""
                            a step of %dms measuring a p99 of %dms cannot have observed steady \
                            state: work issued at the start of the step did not finish inside it. \
                            Reporting a rate from it is how an endpoint whose real capacity was \
                            6/s came back as "sustains 21/s".""",
                            step / MS, m.p99Nanos() / MS)
                    .isTrue();

            assertThat(m.recommendedStepNanos()).isGreaterThanOrEqualTo(m.p99Nanos() * 10);
        }
    }

    /**
     * The same target measured over a step long enough for the work to finish inside it. Nothing
     * should be refused here - a correct answer must not be withheld, or the check is just noise.
     */
    @Test
    void aStepLongerThanTheLatencyIsReportedNormally() throws Exception {
        try (Target target = start(8, 100)) {
            // 8 workers at 100ms = 80/s. Driven at 40/s, comfortably inside capacity, so the
            // latency stays near the service time and far below a 6s step.
            long step = 6 * SECOND;
            CapacitySearch.Measurement m = measure(target.port(), 40, step);

            record("p99 against the 6s step", "< 2s (a third of it)",
                    "%dms".formatted(m.p99Nanos() / MS), m.p99Nanos() <= step / 3);
            record("step declared too short", false, m.stepTooShort(), !m.stepTooShort());
            record("delivery", "~100%", "%.0f%%".formatted(m.deliveryRatio() * 100),
                    m.deliveryRatio() > 0.9);
            printLedger("8 workers at 100ms, driven at 40/s over a 6s step");

            assertThat(m.stepTooShort())
                    .withFailMessage("""
                            a p99 of %dms inside a %dms step is exactly the case the step is for. \
                            Refusing to report it would make the check useless.""",
                            m.p99Nanos() / MS, step / MS)
                    .isFalse();
        }
    }

    // --- the report has to lead with it, not footnote it ---------------------------------------

    @Test
    void theWarningIsPrintedBeforeTheNumberNotAfterIt() {
        // Hand-built so the figures are exact: a 10s step with a 30s p99, which is the real case.
        CapacitySearch.Measurement bad = new CapacitySearch.Measurement(
                21, 21, 30 * SECOND, 210, 210, "http", 10 * SECOND);
        var held = new CapacitySearch.Observation(1.0, bad, CapacitySearch.Strain.NONE);
        var gave = new CapacitySearch.Observation(2.0,
                new CapacitySearch.Measurement(42, 20, 35 * SECOND, 420, 200, "http", 10 * SECOND),
                CapacitySearch.Strain.SHED);
        var result = new CapacitySearch.Result(List.of(held, gave), held, gave,
                CapacitySearch.Strain.SHED, false);

        String out = CliTables.capacitySearchVerdict(result, Map.of("http", 21), 60);

        assertThat(result.stepTooShort()).isTrue();
        assertThat(out)
                .withFailMessage("the reader has to be told the number is unusable: %s", out)
                .contains("NOT reportable");
        assertThat(out).contains("--step");

        int warning = out.indexOf("NOT reportable");
        int number = out.indexOf("sustains");
        assertThat(warning)
                .withFailMessage("""
                            the warning must come before the figure. A caveat printed after a \
                            confident-looking "sustains 21/s" is read second or not at all, and \
                            the number is what gets quoted. Output was:
                            %s""", out)
                .isLessThan(number);
    }

    @Test
    void aSoundResultCarriesNoWarning() {
        CapacitySearch.Measurement fine = new CapacitySearch.Measurement(
                50, 49, 80 * MS, 500, 500, "http", 10 * SECOND);
        var held = new CapacitySearch.Observation(1.0, fine, CapacitySearch.Strain.NONE);
        var gave = new CapacitySearch.Observation(2.0,
                new CapacitySearch.Measurement(100, 50, 4 * SECOND, 1000, 500, "http",
                        10 * SECOND),
                CapacitySearch.Strain.SHED);
        var result = new CapacitySearch.Result(List.of(held, gave), held, gave,
                CapacitySearch.Strain.SHED, false);

        assertThat(result.stepTooShort()).isFalse();
        assertThat(CliTables.capacitySearchVerdict(result, Map.of("http", 50), 60))
                .doesNotContain("NOT reportable")
                .contains("sustains");
    }

    @Test
    void withoutAStepDurationNothingIsClaimedEitherWay() {
        // The six-argument constructor leaves the step unknown. Silence is correct: a claim that
        // the step was fine would be as unfounded as a claim that it was not.
        CapacitySearch.Measurement unknown =
                new CapacitySearch.Measurement(10, 10, 30 * SECOND, 100, 100, "http");

        assertThat(unknown.stepDurationNanos()).isZero();
        assertThat(unknown.stepTooShort())
                .withFailMessage("with no step duration there is nothing to compare against")
                .isFalse();
    }
}
