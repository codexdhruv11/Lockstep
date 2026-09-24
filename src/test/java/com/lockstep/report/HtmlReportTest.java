package com.lockstep.report;

import static org.assertj.core.api.Assertions.assertThat;

import com.lockstep.analysis.CapacityFinder;
import com.lockstep.analysis.SpikeCorrelator;
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

final class HtmlReportTest {
    private static final long MS = 1_000_000L;
    private static final long SECOND = 1_000_000_000L;

    @TempDir
    Path tempDir;

    private static RunCoordinator.RunResult runWithSpike() {
        HistogramRecorder http = new HistogramRecorder(SECOND, 4);
        HistogramRecorder db = new HistogramRecorder(SECOND, 4);
        for (int bucket = 0; bucket < 4; bucket++) {
            for (int i = 0; i < 50; i++) {
                long httpLatency = 15 * MS;
                long dbLatency = bucket == 2 ? 600 * MS : 5 * MS;
                http.record(bucket * SECOND, httpLatency, httpLatency, true, 200);
                db.record(bucket * SECOND, dbLatency, dbLatency, true, null);
            }
        }
        Map<String, PacedLoop.LoopResult> runners = new LinkedHashMap<>();
        runners.put("http", new PacedLoop.LoopResult(http.snapshot(), 220, 20, 0, 2, 5 * MS, 220, true));
        runners.put("db", new PacedLoop.LoopResult(db.snapshot(), 200, 0, 0, 0, 0, 200, true));
        return new RunCoordinator.RunResult(
                new RunContext(0, Instant.parse("2026-09-24T10:15:30Z"), 4 * SECOND, SECOND, SECOND, 8, 0),
                runners, null);
    }

    private static RunReport reportWithSpike() {
        var result = runWithSpike();
        var correlation = SpikeCorrelator.correlate(result, SpikeCorrelator.Thresholds.defaults());
        var capacity = CapacityFinder.find(result.byRunner().get("http").series().buckets(), 8, SECOND, SECOND);
        return RunReport.from(result, "0.1.0-TEST", correlation, capacity);
    }

    @Test
    void thePageEmbedsEveryFigureItDisplays() {
        String html = HtmlReport.render(reportWithSpike());

        assertThat(html).contains("id=\"run-data\"");
        assertThat(html).contains("\"name\" : \"http\"").contains("\"name\" : \"db\"");
        assertThat(html).contains("\"spikes\"").contains("\"masked\" : true");
        assertThat(html).contains("\"capacity\"");
        assertThat(html).contains("\"percentilePrecision\" : 0.01");
    }

    @Test
    void aResponseBodyContainingAScriptTagCannotBreakTheDocument() {
        String escaped = HtmlReport.escapeForScriptTag("{\"failure\":\"got </script><h1>oops\"}");

        assertThat(escaped).doesNotContain("</script>");
        assertThat(escaped).contains("<\\/script>");

        assertThat(escaped.replace("<\\/", "</")).isEqualTo("{\"failure\":\"got </script><h1>oops\"}");
    }

    @Test
    void lineSeparatorsThatBreakJavascriptParsersAreEscaped() {
        assertThat(HtmlReport.escapeForScriptTag("a b")).isEqualTo("a\\u2028b");
        assertThat(HtmlReport.escapeForScriptTag("a b")).isEqualTo("a\\u2029b");
    }

    @Test
    void theChartLibraryIsTheOnlyExternalResource() {
        String html = HtmlReport.render(reportWithSpike());

        long externalScripts = html.lines().filter(line -> line.contains("src=\"http")).count();
        assertThat(externalScripts).isEqualTo(1);
        assertThat(html).contains("cdn.jsdelivr.net/npm/chart.js");

        assertThat(html).doesNotContain("<link rel=\"stylesheet\" href=\"http");
        assertThat(html).doesNotContain("<img src=\"http");
    }

    @Test
    void theRunHeaderAndPrecisionAreCarriedIntoThePage() {
        String html = HtmlReport.render(reportWithSpike());
        assertThat(html).contains("2026-09-24T10:15:30Z");
        assertThat(html).contains("<title>lockstep");
    }

    @Test
    void aRunWithNoSpikesStillRendersAPage() {
        HistogramRecorder http = new HistogramRecorder(SECOND, 2);
        http.record(0, 10 * MS, 10 * MS, true, 200);
        Map<String, PacedLoop.LoopResult> runners = new LinkedHashMap<>();
        runners.put("http", new PacedLoop.LoopResult(http.snapshot(), 1, 0, 0, 0, 0, 1, true));
        var result = new RunCoordinator.RunResult(
                new RunContext(0, Instant.EPOCH, 2 * SECOND, SECOND, 0, 4, 0), runners, null);

        String html = HtmlReport.render(RunReport.from(result, "t"));

        assertThat(html).contains("id=\"run-data\"");
        assertThat(html).contains("\"spikes\" : [ ]");
    }

    @Test
    void writingIsAtomicAndLeavesNoTemporaryFiles() throws Exception {
        Path file = tempDir.resolve("report.html");

        HtmlReport.write(reportWithSpike(), file);
        HtmlReport.write(reportWithSpike(), file);

        assertThat(Files.readString(file)).contains("lockstep");
        try (var entries = Files.list(tempDir)) {
            assertThat(entries.map(Path::getFileName).map(Path::toString)).containsExactly("report.html");
        }
    }

    @Test
    void theTemplateShipsInsideTheArtifact() {
        assertThat(HtmlReport.class.getResourceAsStream("/report-template.html")).isNotNull();
    }
}
