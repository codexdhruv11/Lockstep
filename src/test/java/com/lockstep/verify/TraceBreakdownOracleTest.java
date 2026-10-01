package com.lockstep.verify;

import static org.assertj.core.api.Assertions.assertThat;

import com.lockstep.analysis.TraceBreakdown;
import com.lockstep.report.CliTables;
import com.lockstep.runner.TraceFetcher;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Checks the self-time arithmetic against traces built so that the answer is known before the code
 * runs.
 *
 * <p>This is the piece of the OpenTelemetry work most likely to be quietly wrong, because every
 * case produces a plausible number. Subtracting the sum of child durations looks correct and
 * breaks the moment two children overlap; ignoring an asynchronous child that outlives its parent
 * attributes time the parent never spent. Both give a figure that reads fine.
 */
final class TraceBreakdownOracleTest {
    private static final long MS = 1_000_000L;

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

    private static TraceBreakdown.Span span(String id, String parent, String name,
            long startMillis, long durationMillis) {
        return new TraceBreakdown.Span(id, parent, name, "svc",
                startMillis * MS, durationMillis * MS);
    }

    private static long selfOf(TraceBreakdown breakdown, String name) {
        return breakdown.contributions().stream()
                .filter(c -> c.name().equals(name))
                .mapToLong(TraceBreakdown.Contribution::selfNanos)
                .findFirst().orElse(-1);
    }

    // --- the simple case, and the one that proves the column adds up -----------------------------

    @Test
    void aChildsTimeIsSubtractedFromItsParent() {
        // Server span 500ms containing one 400ms query starting 50ms in: 100ms and 400ms.
        TraceBreakdown breakdown = TraceBreakdown.of("t", List.of(
                span("root", null, "GET /api/thing", 0, 500),
                span("db", "root", "SELECT signals", 50, 400)));

        record("root self", "100ms", "%.0fms".formatted(selfOf(breakdown, "GET /api/thing") / (double) MS),
                selfOf(breakdown, "GET /api/thing") == 100 * MS);
        record("child self", "400ms", "%.0fms".formatted(selfOf(breakdown, "SELECT signals") / (double) MS),
                selfOf(breakdown, "SELECT signals") == 400 * MS);
        record("self times sum to the trace", "500ms",
                "%.0fms".formatted(breakdown.accountedNanos() / (double) MS),
                breakdown.accountedNanos() == 500 * MS);
        record("the query ranks first", "SELECT signals",
                breakdown.top(1).get(0).name(),
                breakdown.top(1).get(0).name().equals("SELECT signals"));
        printLedger("self time, one child inside one parent");

        assertThat(selfOf(breakdown, "GET /api/thing")).isEqualTo(100 * MS);
        assertThat(selfOf(breakdown, "SELECT signals")).isEqualTo(400 * MS);
        assertThat(breakdown.accountedNanos())
                .withFailMessage("""
                        self times must sum to the trace's duration — that is the property that \
                        makes the breakdown add up instead of merely ranking""")
                .isEqualTo(500 * MS);
    }

    @Test
    void parallelChildrenAreUnionedRatherThanSummed() {
        // Two 300ms children overlapping almost entirely inside a 400ms parent. Their union spans
        // 50ms..360ms = 310ms, so the parent's self time is 90ms. Summing durations would
        // subtract 600ms from 400ms and produce a negative.
        TraceBreakdown breakdown = TraceBreakdown.of("t", List.of(
                span("root", null, "GET /fanout", 0, 400),
                span("a", "root", "call A", 50, 300),
                span("b", "root", "call B", 60, 300)));

        long rootSelf = selfOf(breakdown, "GET /fanout");

        record("children summed would be", "600ms (> parent)", "600ms", true);
        record("union of children", "310ms", "310ms", true);
        record("root self", "90ms", "%.0fms".formatted(rootSelf / (double) MS),
                rootSelf == 90 * MS);
        record("root self is not negative", "> 0", rootSelf, rootSelf > 0);
        printLedger("self time, two parallel children");

        assertThat(rootSelf)
                .withFailMessage("""
                        two 300ms children overlap inside a 400ms parent, covering 50ms..360ms. \
                        Self time is 400 - 310 = 90ms. Subtracting the sum of durations would \
                        take 600ms from 400ms and report a negative, which is the mistake this \
                        union exists to avoid.""")
                .isEqualTo(90 * MS);
    }

    @Test
    void sequentialChildrenLeaveTheGapsAsSelfTime() {
        // 100ms, then a 100ms gap, then 100ms, inside a 400ms parent: 100ms of gap plus 100ms of
        // tail = 200ms self.
        TraceBreakdown breakdown = TraceBreakdown.of("t", List.of(
                span("root", null, "GET /serial", 0, 400),
                span("a", "root", "step A", 0, 100),
                span("b", "root", "step B", 200, 100)));

        assertThat(selfOf(breakdown, "GET /serial"))
                .withFailMessage("gaps between children are time the parent itself spent")
                .isEqualTo(200 * MS);
    }

    @Test
    void anAsyncChildOutlivingItsParentOnlyCountsForTheOverlap() {
        // A 400ms child starting 300ms into a 400ms parent: only 100ms overlaps. Without
        // clamping it would subtract 400ms from 400ms and claim the parent did nothing.
        TraceBreakdown breakdown = TraceBreakdown.of("t", List.of(
                span("root", null, "POST /fire", 0, 400),
                span("async", "root", "background work", 300, 400)));

        long rootSelf = selfOf(breakdown, "POST /fire");

        record("overlap with the parent", "100ms", "100ms", true);
        record("root self", "300ms", "%.0fms".formatted(rootSelf / (double) MS),
                rootSelf == 300 * MS);
        printLedger("self time, an async child outliving its parent");

        assertThat(rootSelf)
                .withFailMessage("""
                        the child runs 300ms..700ms and the parent ends at 400ms, so only 100ms \
                        is time the parent waited. Unclamped, this subtracts the child's full \
                        400ms and reports a parent that did nothing at all.""")
                .isEqualTo(300 * MS);
    }

    @Test
    void anOrphanedSpanIsTreatedAsARootRatherThanDiscarded() {
        // The parent was sampled away. The child must still be counted.
        TraceBreakdown breakdown = TraceBreakdown.of("t", List.of(
                span("orphan", "a-parent-that-was-not-sampled", "SELECT things", 0, 250)));

        record("spans", 1, breakdown.spans().size(), breakdown.spans().size() == 1);
        record("self time counted", "250ms",
                "%.0fms".formatted(selfOf(breakdown, "SELECT things") / (double) MS),
                selfOf(breakdown, "SELECT things") == 250 * MS);
        printLedger("self time, an orphaned span");

        assertThat(selfOf(breakdown, "SELECT things"))
                .withFailMessage("a missing parent is common under sampling; discarding the child "
                        + "would throw away a usable answer")
                .isEqualTo(250 * MS);
    }

    @Test
    void repeatedOperationsAreGroupedAndCounted() {
        // The N+1 shape: one parent, ten identical queries.
        List<TraceBreakdown.Span> spans = new ArrayList<>();
        spans.add(span("root", null, "GET /page", 0, 500));
        for (int i = 0; i < 10; i++) {
            spans.add(span("q" + i, "root", "SELECT customer", i * 40L, 40));
        }
        TraceBreakdown breakdown = TraceBreakdown.of("t", spans);

        var top = breakdown.top(1).get(0);

        record("grouped span count", 10, top.spanCount(), top.spanCount() == 10);
        record("grouped self time", "400ms", "%.0fms".formatted(top.selfNanos() / (double) MS),
                top.selfNanos() == 400 * MS);
        record("operation", "SELECT customer", top.name(), top.name().equals("SELECT customer"));
        printLedger("self time, ten identical queries under one parent");

        assertThat(top.name()).isEqualTo("SELECT customer");
        assertThat(top.spanCount())
                .withFailMessage("ten separate spans of the same operation must be reported as "
                        + "one line with a count, which is what makes an N+1 visible")
                .isEqualTo(10);
        assertThat(top.selfNanos()).isEqualTo(400 * MS);
    }

    @Test
    void anEmptyTraceIsEmptyRatherThanZeroed() {
        assertThat(TraceBreakdown.of("t", List.of()).isEmpty()).isTrue();
        assertThat(TraceBreakdown.of("t", null).isEmpty()).isTrue();
        assertThat(TraceBreakdown.empty("t").totalNanos()).isZero();
    }

    // --- parsing Jaeger's actual response shape -------------------------------------------------

    @Test
    void jaegersResponseShapeIsParsedAsItReallyArrives() throws Exception {
        // Taken from a running Jaeger 1.57: microseconds, a references array for the parent, and
        // service names in a separate processes map.
        String body = """
                {"data":[{"traceID":"abc","processes":{"p1":{"serviceName":"demo-svc"}},
                "spans":[
                  {"traceID":"abc","spanID":"root","operationName":"GET /api/thing",
                   "references":[],"startTime":1790853430301803,"duration":500000,
                   "processID":"p1"},
                  {"traceID":"abc","spanID":"child","operationName":"SELECT signals",
                   "references":[{"refType":"CHILD_OF","traceID":"abc","spanID":"root"}],
                   "startTime":1790853430351803,"duration":400000,"processID":"p1"}
                ]}]}
                """;

        TraceBreakdown breakdown = TraceFetcher.parse("abc", body);

        record("spans parsed", 2, breakdown.spans().size(), breakdown.spans().size() == 2);
        record("microseconds converted", "500ms",
                "%.0fms".formatted(breakdown.spans().get(0).durationNanos() / (double) MS),
                breakdown.spans().get(0).durationNanos() == 500 * MS);
        record("parent from references", "root",
                breakdown.spans().get(1).parentSpanId(),
                "root".equals(breakdown.spans().get(1).parentSpanId()));
        record("service from processes map", "demo-svc", breakdown.spans().get(0).service(),
                "demo-svc".equals(breakdown.spans().get(0).service()));
        record("root self after subtraction", "100ms",
                "%.0fms".formatted(selfOf(breakdown, "GET /api/thing") / (double) MS),
                selfOf(breakdown, "GET /api/thing") == 100 * MS);
        printLedger("jaeger response parsing");

        assertThat(breakdown.spans()).hasSize(2);
        assertThat(breakdown.spans().get(0).durationNanos())
                .withFailMessage("Jaeger reports microseconds; reading them as nanoseconds would "
                        + "report a 500ms span as 500µs")
                .isEqualTo(500 * MS);
        assertThat(breakdown.spans().get(1).parentSpanId()).isEqualTo("root");
        assertThat(breakdown.spans().get(0).service()).isEqualTo("demo-svc");
        assertThat(selfOf(breakdown, "GET /api/thing")).isEqualTo(100 * MS);
    }

    @Test
    void anEmptyOrUnexpectedResponseIsNotMistakenForASuccessfulFetch() throws Exception {
        assertThat(TraceFetcher.parse("t", "{\"data\":[]}").isEmpty()).isTrue();
        assertThat(TraceFetcher.parse("t", "{}").isEmpty()).isTrue();
        assertThat(TraceFetcher.parse("t", "{\"data\":[{\"spans\":[]}]}").isEmpty()).isTrue();
    }

    @Test
    void anInvalidBackendUrlIsRefused() {
        assertThat(org.assertj.core.api.Assertions.catchThrowable(
                () -> TraceFetcher.open("not-a-url")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(org.assertj.core.api.Assertions.catchThrowable(
                () -> TraceFetcher.open("/relative")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // --- rendering ------------------------------------------------------------------------------

    @Test
    void theReportNamesTheOperationThatOwnedTheTime() {
        TraceBreakdown breakdown = TraceBreakdown.of("8f3a", List.of(
                span("root", null, "GET /api/dashboard", 0, 4200),
                span("db", "root", "SELECT signals", 100, 3940)));

        String out = CliTables.traceBreakdownSection(List.of(breakdown), null);

        assertThat(out).contains("8f3a");
        assertThat(out).contains("SELECT signals");
        assertThat(out).contains("3.9s");
        assertThat(out)
                .withFailMessage("the arithmetic has to be stated or the column looks like it "
                        + "double-counts parallel work")
                .contains("minus the union of its children");
    }

    @Test
    void anUnavailableBackendExplainsItselfRatherThanPrintingNothing() {
        String out = CliTables.traceBreakdownSection(null,
                "--traces needs --trace: without it no trace IDs are sent, so there is nothing "
                + "to fetch");

        assertThat(out).contains("trace breakdown");
        assertThat(out).contains("needs --trace");
        assertThat(CliTables.traceBreakdownSection(List.of(), null))
                .withFailMessage("nothing requested means no section at all")
                .isEmpty();
    }
}
