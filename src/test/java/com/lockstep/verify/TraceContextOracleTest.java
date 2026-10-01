package com.lockstep.verify;

import static org.assertj.core.api.Assertions.assertThat;

import com.lockstep.config.HttpConfig;
import com.lockstep.core.PacedLoop;
import com.lockstep.core.RunContext;
import com.lockstep.core.TraceContext;
import com.lockstep.runner.http.HttpRunner;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/**
 * Verifies what Lockstep actually puts on the wire, by having the target record every header it
 * receives.
 *
 * <p>The truth here is not a measurement, it is a specification: W3C Trace Context says exactly
 * what a {@code traceparent} must look like, and a receiver must reject anything else. So the
 * oracle is the spec plus the server's own record of what arrived — not Lockstep's account of what
 * it believes it sent.
 */
final class TraceContextOracleTest {
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

    /** A server that remembers every traceparent it was sent, and how many requests had none. */
    private record Target(HttpServer server, int port, Set<String> received, AtomicLong missing)
            implements AutoCloseable {
        @Override
        public void close() {
            server.stop(0);
        }
    }

    private static Target start(long delayMillis) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        Set<String> received = ConcurrentHashMap.newKeySet();
        AtomicLong missing = new AtomicLong();
        server.createContext("/echo", exchange -> {
            List<String> values = exchange.getRequestHeaders().get(TraceContext.HEADER);
            if (values == null || values.isEmpty()) {
                missing.incrementAndGet();
            } else {
                // Record every value, so a request carrying two headers is detectable.
                received.add(String.join(",", values));
            }
            if (delayMillis > 0) {
                try {
                    Thread.sleep(delayMillis);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
        return new Target(server, server.getAddress().getPort(), received, missing);
    }

    private static PacedLoop.LoopResult drive(int port, boolean trace, int rate, long duration) {
        HttpConfig config = new HttpConfig(new HttpConfig.Target("GET",
                "http://localhost:" + port + "/echo", null, Map.of(), 1), rate);
        try (HttpRunner runner = HttpRunner.create(config, 20, trace)) {
            return runner.run(RunContext.startingNow(duration, 500 * MS, 0, 20));
        }
    }

    // --- the header generator against the specification -----------------------------------------

    @Test
    void generatedHeadersSatisfyTheSpecification() {
        List<String> headers = new ArrayList<>();
        for (int i = 0; i < 2_000; i++) {
            headers.add(TraceContext.newHeader());
        }

        boolean allValid = headers.stream().allMatch(TraceContext::isValid);
        long distinct = headers.stream().distinct().count();
        boolean shapeOk = headers.stream().allMatch(h -> {
            String[] p = h.split("-");
            return p.length == 4 && p[0].equals("00") && p[1].length() == 32
                    && p[2].length() == 16 && p[3].equals("01");
        });

        record("all valid per spec", 2_000, headers.stream().filter(TraceContext::isValid).count(),
                allValid);
        record("all distinct", 2_000, distinct, distinct == 2_000);
        record("version-32hex-16hex-01", "yes", shapeOk ? "yes" : "no", shapeOk);
        printLedger("traceparent generation, 2,000 headers");

        assertThat(allValid).isTrue();
        assertThat(distinct)
                .withFailMessage("a repeated trace id would merge two requests into one trace")
                .isEqualTo(2_000);
        assertThat(shapeOk).isTrue();
    }

    @Test
    void theSpecsRejectionRulesAreEnforced() {
        // A receiver must reject these, so Lockstep must never emit them.
        assertThat(TraceContext.isValid("00-" + "0".repeat(32) + "-" + "0".repeat(16) + "-01"))
                .withFailMessage("an all-zero trace id is forbidden by the spec")
                .isFalse();
        assertThat(TraceContext.isValid("00-" + "a".repeat(32) + "-" + "0".repeat(16) + "-01"))
                .withFailMessage("an all-zero span id is forbidden by the spec")
                .isFalse();
        assertThat(TraceContext.isValid("00-tooshort-0000000000000001-01")).isFalse();
        assertThat(TraceContext.isValid("00-" + "A".repeat(32) + "-" + "1".repeat(16) + "-01"))
                .withFailMessage("hex must be lower case")
                .isFalse();
        assertThat(TraceContext.isValid(null)).isFalse();
        assertThat(TraceContext.isValid("not-a-header")).isFalse();

        assertThat(TraceContext.traceIdOf(TraceContext.newHeader())).hasSize(32);
        assertThat(TraceContext.traceIdOf("garbage")).isNull();
    }

    // --- what actually reaches the target --------------------------------------------------------

    @Test
    void everyRequestCarriesExactlyOneValidUniqueHeader() throws Exception {
        try (Target target = start(0)) {
            PacedLoop.LoopResult result = drive(target.port(), true, 40, 3 * SECOND);
            long executed = result.executedCount();

            boolean allValid = target.received().stream().allMatch(TraceContext::isValid);
            boolean noDuplicateHeaderPerRequest =
                    target.received().stream().noneMatch(value -> value.contains(","));

            record("requests executed", "> 50", executed, executed > 50);
            record("requests with no header", 0, target.missing().get(),
                    target.missing().get() == 0);
            record("distinct headers received", executed, target.received().size(),
                    target.received().size() == executed);
            record("all received are valid", "yes", allValid ? "yes" : "no", allValid);
            record("one header per request", "yes", noDuplicateHeaderPerRequest ? "yes" : "no",
                    noDuplicateHeaderPerRequest);
            printLedger("traceparent on the wire, " + executed + " requests");

            assertThat(target.missing().get())
                    .withFailMessage("tracing was on, so no request may arrive without a header")
                    .isZero();
            assertThat(target.received())
                    .withFailMessage("""
                            %d requests executed but the server recorded %d distinct headers. \
                            Fewer means ids were reused across requests, which would merge \
                            separate requests into one trace.""",
                            executed, target.received().size())
                    .hasSize((int) executed);
            assertThat(allValid)
                    .withFailMessage("a receiver rejects a malformed traceparent, so the feature "
                            + "would silently do nothing: %s", target.received())
                    .isTrue();
            assertThat(noDuplicateHeaderPerRequest)
                    .withFailMessage("""
                            a request carried two traceparent values, which means the shared \
                            request template was mutated instead of copied — the target would \
                            then attribute spans to whichever it parsed first""")
                    .isTrue();
        }
    }

    @Test
    void nothingIsSentWhenTracingIsOff() throws Exception {
        try (Target target = start(0)) {
            PacedLoop.LoopResult result = drive(target.port(), false, 40, 2 * SECOND);

            record("requests executed", "> 20", result.executedCount(),
                    result.executedCount() > 20);
            record("headers received", 0, target.received().size(),
                    target.received().isEmpty());
            record("requests with no header", result.executedCount(), target.missing().get(),
                    target.missing().get() == result.executedCount());
            printLedger("tracing off by default");

            assertThat(target.received())
                    .withFailMessage("""
                            the default must send nothing. A sampled header makes the target \
                            export a trace per request, and most backends bill per span — a load \
                            test must not quietly become a bill.""")
                    .isEmpty();
            assertThat(target.missing().get()).isEqualTo(result.executedCount());
        }
    }

    // --- the ids the report hands over must be the ids of the slow requests ----------------------

    @Test
    void theReportedSlowRequestIdsWereReallySentAndWereReallyTheSlowest() throws Exception {
        // Every tenth request is slow by construction, so the slowest list is knowable.
        AtomicLong served = new AtomicLong();
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        Set<String> slowHeaders = ConcurrentHashMap.newKeySet();
        Set<String> allHeaders = ConcurrentHashMap.newKeySet();
        server.createContext("/echo", exchange -> {
            String header = exchange.getRequestHeaders().getFirst(TraceContext.HEADER);
            if (header != null) {
                allHeaders.add(header);
            }
            boolean slow = served.incrementAndGet() % 10 == 0;
            if (slow && header != null) {
                slowHeaders.add(header);
            }
            try {
                Thread.sleep(slow ? 400 : 20);
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

        try {
            PacedLoop.LoopResult result = drive(server.getAddress().getPort(), true, 20,
                    5 * SECOND);
            var slowest = result.slowestRequests();

            Set<String> reportedIds = new java.util.HashSet<>();
            for (var entry : slowest) {
                if (entry.traceId() != null) {
                    reportedIds.add(entry.traceId());
                }
            }
            Set<String> sentIds = new java.util.HashSet<>();
            allHeaders.forEach(h -> sentIds.add(TraceContext.traceIdOf(h)));
            Set<String> slowIds = new java.util.HashSet<>();
            slowHeaders.forEach(h -> slowIds.add(TraceContext.traceIdOf(h)));

            boolean allWereSent = sentIds.containsAll(reportedIds);
            long fromSlowSet = reportedIds.stream().filter(slowIds::contains).count();

            record("slowest entries recorded", "> 5", slowest.size(), slowest.size() > 5);
            record("all have a trace id", slowest.size(), reportedIds.size(),
                    reportedIds.size() == slowest.size());
            record("every reported id was sent", "yes", allWereSent ? "yes" : "no", allWereSent);
            record("reported ids from the slow 10%", reportedIds.size() + " of " + reportedIds.size(),
                    fromSlowSet + " of " + reportedIds.size(), fromSlowSet == reportedIds.size());
            record("ordered slowest first", "yes",
                    isDescending(slowest) ? "yes" : "no", isDescending(slowest));
            printLedger("slowest-request trace ids");

            assertThat(reportedIds)
                    .withFailMessage("every slow entry must carry the id that was sent with it")
                    .hasSize(slowest.size());
            assertThat(allWereSent)
                    .withFailMessage("""
                            the report handed over an id the server never received. An id that \
                            resolves to nothing is worse than no id: it sends the reader looking \
                            for a trace that does not exist.""")
                    .isTrue();
            assertThat(fromSlowSet)
                    .withFailMessage("""
                            every tenth request sleeps 400ms and the rest sleep 20ms, so the \
                            slowest requests must all come from that tenth. %d of %d reported ids \
                            did.""", fromSlowSet, reportedIds.size())
                    .isEqualTo(reportedIds.size());
            assertThat(isDescending(slowest))
                    .withFailMessage("the slowest must be listed first: %s", slowest)
                    .isTrue();
        } finally {
            server.stop(0);
        }
    }

    private static boolean isDescending(List<com.lockstep.stats.SlowestRequests.Entry> entries) {
        for (int i = 1; i < entries.size(); i++) {
            if (entries.get(i - 1).latencyNanos() < entries.get(i).latencyNanos()) {
                return false;
            }
        }
        return true;
    }
}
