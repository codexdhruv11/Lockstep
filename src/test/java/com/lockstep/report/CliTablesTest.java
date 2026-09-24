package com.lockstep.report;

import static org.assertj.core.api.Assertions.assertThat;

import com.lockstep.core.PacedLoop;
import com.lockstep.core.RunContext;
import com.lockstep.core.RunCoordinator;
import com.lockstep.stats.BucketSeries;
import com.lockstep.stats.HistogramRecorder;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class CliTablesTest {
    private static final long MS = 1_000_000L;
    private static final long SECOND = 1_000_000_000L;

    private static RunCoordinator.RunResult resultWith(Map<String, PacedLoop.LoopResult> runners) {
        RunContext context = new RunContext(0, Instant.EPOCH, 10 * SECOND, SECOND, 3 * SECOND, 10, 0);
        return new RunCoordinator.RunResult(context, runners, null);
    }

    private static PacedLoop.LoopResult loop(long operations, long errors, long latencyNanos,
            long scheduled, long shed, long late) {
        HistogramRecorder recorder = new HistogramRecorder(SECOND, 10);
        for (long i = 0; i < operations; i++) {
            boolean success = i >= errors;
            recorder.record((i % 10) * SECOND, latencyNanos, latencyNanos, success, success ? 200 : 500);
        }
        BucketSeries series = recorder.snapshot();
        return new PacedLoop.LoopResult(series, scheduled, shed, 0, late, late > 0 ? 5 * MS : 0, scheduled, true);
    }

    @Test
    void summaryTableHasOneRowPerRunnerAndAlignedColumns() {
        Map<String, PacedLoop.LoopResult> runners = new LinkedHashMap<>();
        runners.put("http", loop(135, 0, 927_000, 135, 0, 0));
        runners.put("db", loop(67, 0, 12_900_000, 67, 0, 0));

        String table = CliTables.summaryTable(resultWith(runners));
        String[] lines = table.stripTrailing().split("\n");

        assertThat(lines).hasSize(3);
        assertThat(lines[0]).contains("RUNNER", "REQUESTS", "SUCCESS", "P99", "STATUS");

        assertThat(lines[1]).startsWith("http").contains("135", "100.0%", "200×135");
        assertThat(lines[2]).startsWith("db").contains("67");

        int headerP99 = lines[0].indexOf("P99");
        assertThat(headerP99).isGreaterThan(0);
        assertThat(lines[1].length()).isGreaterThan(headerP99);
    }

    @Test
    void latenciesAreRenderedInReadableUnitsNotRawNanoseconds() {
        Map<String, PacedLoop.LoopResult> runners = new LinkedHashMap<>();
        runners.put("redis", loop(10, 0, 797_000, 10, 0, 0));
        String table = CliTables.summaryTable(resultWith(runners));

        assertThat(table).contains("µs");
        assertThat(table).doesNotContain("797000");
    }

    @Test
    void errorsShowInTheSuccessRateAndStatusBreakdown() {
        Map<String, PacedLoop.LoopResult> runners = new LinkedHashMap<>();
        runners.put("http", loop(100, 40, MS, 100, 0, 0));

        String table = CliTables.summaryTable(resultWith(runners));
        assertThat(table).contains("60.0%").contains("500×40").contains("200×60");
    }

    @Test
    void shortfallIsReportedWhenWorkWasShedOrFiredLate() {
        Map<String, PacedLoop.LoopResult> runners = new LinkedHashMap<>();
        runners.put("db", loop(60, 0, MS, 100, 40, 3));

        String notes = CliTables.shortfallNotes(resultWith(runners));

        assertThat(notes).contains("db").contains("60 of 100 expected")
                .contains("40 shed").contains("3 fired late");
    }

    @Test
    void noShortfallNoteWhenTheRunDeliveredWhatItPromised() {
        Map<String, PacedLoop.LoopResult> runners = new LinkedHashMap<>();
        runners.put("db", loop(100, 0, MS, 100, 0, 0));
        assertThat(CliTables.shortfallNotes(resultWith(runners))).isEmpty();
    }

    @Test
    void bucketTableListsOnlyBucketsThatSawTraffic() {
        String table = CliTables.bucketTable("http", loop(30, 0, MS, 30, 0, 0));
        String[] lines = table.stripTrailing().split("\n");

        assertThat(lines[0]).isEqualTo("http");
        assertThat(lines[1]).contains("BUCKET", "COUNT", "QUEUE_P99");

        assertThat(lines).hasSize(12);
        assertThat(lines[2]).startsWith("00:00");
    }

    @Test
    void outputCarriesNoAnsiCodesWhenStdoutIsNotATerminal() {
        Map<String, PacedLoop.LoopResult> runners = new LinkedHashMap<>();
        runners.put("http", loop(10, 5, MS, 20, 10, 1));
        var result = resultWith(runners);

        assertThat(CliTables.summaryTable(result)).doesNotContain("\u001B[");
        assertThat(CliTables.shortfallNotes(result)).doesNotContain("\u001B[");
        assertThat(CliTables.precisionNote()).doesNotContain("\u001B[");
        assertThat(CliTables.bucketTable("http", runners.get("http"))).doesNotContain("\u001B[");
    }

    @Test
    void headerDescribesTheRunParameters() {
        String header = CliTables.runHeader(resultWith(Map.of("http", loop(1, 0, MS, 1, 0, 0))));
        assertThat(header).isEqualTo("duration 10s · bucket 1s · concurrency 10 · ramp 3s");
    }

    @Test
    void precisionIsStatedRatherThanImplied() {
        assertThat(CliTables.precisionNote()).contains("1.0%").contains("histogram precision");
    }
}
