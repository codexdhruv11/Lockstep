package com.lockstep.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.lockstep.core.PacedLoop;
import com.lockstep.core.RunContext;
import com.lockstep.core.RunCoordinator;
import com.lockstep.stats.HistogramRecorder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class JsonExportTest {
    private static final long MS = 1_000_000L;
    private static final long SECOND = 1_000_000_000L;

    @TempDir
    Path tempDir;

    private static RunCoordinator.RunResult sampleRun() {
        HistogramRecorder http = new HistogramRecorder(SECOND, 5);
        for (int i = 0; i < 200; i++) {
            boolean ok = i % 10 != 0;
            http.record((i % 5) * SECOND, (10 + i % 40) * MS, (8 + i % 40) * MS, ok, ok ? 200 : 503);
        }
        HistogramRecorder db = new HistogramRecorder(SECOND, 5);
        for (int i = 0; i < 90; i++) {
            db.record((i % 5) * SECOND, (5 + i % 20) * MS, (5 + i % 20) * MS, true, null);
        }

        Map<String, PacedLoop.LoopResult> runners = new LinkedHashMap<>();
        runners.put("http", new PacedLoop.LoopResult(http.snapshot(), 220, 20, 3, 7 * MS, 250, true));
        runners.put("db", new PacedLoop.LoopResult(db.snapshot(), 90, 0, 0, 0, 90, true));

        RunContext context = new RunContext(0, Instant.parse("2026-09-24T10:15:30Z"),
                5 * SECOND, SECOND, SECOND, 8);
        return new RunCoordinator.RunResult(context, runners);
    }

    @Test
    void roundTripsThroughAFileWithoutChangingASingleFigure() {
        RunReport original = RunReport.from(sampleRun(), "0.1.0-TEST");
        Path file = tempDir.resolve("results.json");

        JsonExport.write(original, file);
        RunReport reloaded = JsonExport.read(file);

        assertThat(reloaded).isEqualTo(original);

        RunReport.RunnerReport http = reloaded.runner("http");
        assertThat(http.count()).isEqualTo(original.runner("http").count());
        assertThat(http.p99Nanos()).isEqualTo(original.runner("http").p99Nanos());
        assertThat(http.serviceP99Nanos()).isEqualTo(original.runner("http").serviceP99Nanos());
        assertThat(http.statusCounts()).isEqualTo(original.runner("http").statusCounts());
        assertThat(http.buckets()).isEqualTo(original.runner("http").buckets());
    }

    @Test
    void theSchemaKeepsTheNamesComparePromisesToRead() {
        String json = JsonExport.toJson(RunReport.from(sampleRun(), "0.1.0-TEST"));

        assertThat(json).contains("\"tool\" : \"lockstep\"", "\"schemaVersion\" : 1");
        assertThat(json).contains("\"durationNanos\"", "\"bucketWidthNanos\"", "\"rampNanos\"",
                "\"concurrency\"", "\"percentilePrecision\"", "\"startedAt\"");
        assertThat(json).contains("\"name\" : \"http\"", "\"count\"", "\"successCount\"",
                "\"errorCount\"", "\"achievedRatePerSecond\"", "\"p50Nanos\"", "\"p95Nanos\"",
                "\"p99Nanos\"", "\"maxNanos\"", "\"serviceP99Nanos\"", "\"statusCounts\"");
        assertThat(json).contains("\"scheduledCount\"", "\"expectedHits\"", "\"shedCount\"",
                "\"lateFireCount\"", "\"maxLatenessNanos\"", "\"drainedCleanly\"");
        assertThat(json).contains("\"buckets\"", "\"index\"", "\"startOffsetNanos\"");
    }

    @Test
    void theReportCarriesWhatTheRunFailedToDeliverNotOnlyItsLatencies() {
        RunReport report = RunReport.from(sampleRun(), "0.1.0-TEST");
        RunReport.RunnerReport http = report.runner("http");

        assertThat(http.scheduledCount()).isEqualTo(220);
        assertThat(http.count()).isEqualTo(200);
        assertThat(http.shedCount()).isEqualTo(20);
        assertThat(http.lateFireCount()).isEqualTo(3);
        assertThat(http.expectedHits()).isEqualTo(250);
        assertThat(http.drainedCleanly()).isTrue();
    }

    @Test
    void runnerOrderSurvivesSerialisationSoTwoReportsDiffCleanly() {
        RunReport report = JsonExport.parse(
                JsonExport.toJson(RunReport.from(sampleRun(), "0.1.0-TEST")), "memory");
        assertThat(report.runners()).extracting(RunReport.RunnerReport::name)
                .containsExactly("http", "db");
    }

    @Test
    void unknownFieldsAreToleratedSoANewerFileStillCompares() {
        String json = """
            {"tool":"lockstep","schemaVersion":1,"toolVersion":"9.9.9","startedAt":"2026-01-01T00:00:00Z",
             "durationNanos":1000000000,"bucketWidthNanos":1000000000,"rampNanos":0,"concurrency":4,
             "percentilePrecision":0.01,"somethingAddedLater":{"nested":true},
             "runners":[{"name":"http","count":10,"p99Nanos":5,"futureField":42}]}
            """;
        RunReport report = JsonExport.parse(json, "future.json");

        assertThat(report.runner("http").count()).isEqualTo(10);
        assertThat(report.runner("http").p99Nanos()).isEqualTo(5);
    }

    @Test
    void aFutureSchemaVersionIsRefusedRatherThanMisread() {
        String json = """
            {"tool":"lockstep","schemaVersion":99,"durationNanos":1,"runners":[]}
            """;
        assertThatThrownBy(() -> JsonExport.parse(json, "future.json"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("newer lockstep")
                .hasMessageContaining("schema 99");
    }

    @Test
    void garbageInputFailsWithTheFileNamed() {
        assertThatThrownBy(() -> JsonExport.parse("not json at all", "broken.json"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("broken.json");
    }

    @Test
    void writingIsAtomicSoAnExistingBaselineIsNeverLeftHalfWritten() throws Exception {
        Path file = tempDir.resolve("baseline.json");
        JsonExport.write(RunReport.from(sampleRun(), "first"), file);
        String first = Files.readString(file);

        JsonExport.write(RunReport.from(sampleRun(), "second"), file);
        String second = Files.readString(file);

        assertThat(first).contains("\"toolVersion\" : \"first\"");
        assertThat(second).contains("\"toolVersion\" : \"second\"");

        try (var entries = Files.list(tempDir)) {
            assertThat(entries.map(Path::getFileName).map(Path::toString))
                    .containsExactly("baseline.json");
        }
    }

    @Test
    void nanosecondFiguresSurviveAsExactIntegers() {
        HistogramRecorder recorder = new HistogramRecorder(SECOND, 1);
        recorder.record(0, 1_234_567_891L, 1_234_567_891L, true, null);
        Map<String, PacedLoop.LoopResult> runners = new LinkedHashMap<>();
        runners.put("db", new PacedLoop.LoopResult(recorder.snapshot(), 1, 0, 0, 0, 1, true));
        var result = new RunCoordinator.RunResult(
                new RunContext(0, Instant.EPOCH, SECOND, SECOND, 0, 1), runners);

        RunReport reloaded = JsonExport.parse(JsonExport.toJson(RunReport.from(result, "t")), "memory");
        long p99 = reloaded.runner("db").p99Nanos();

        assertThat(p99).isGreaterThan(1_200_000_000L).isLessThan(1_260_000_000L);
        assertThat(JsonExport.toJson(RunReport.from(result, "t"))).doesNotContain("E9");
    }
}
