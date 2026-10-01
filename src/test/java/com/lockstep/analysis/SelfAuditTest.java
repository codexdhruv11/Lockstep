package com.lockstep.analysis;

import static org.assertj.core.api.Assertions.assertThat;

import com.lockstep.report.CliTables;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class SelfAuditTest {

    private static final long SECOND = 1_000_000_000L;

    @Test
    void theRecorderActuallyCapturesRealJvmPauses() {
        SelfAudit audit = SelfAudit.start();
        if (audit == null) {
            return;
        }
        Instant start = Instant.now();

        List<byte[]> retained = new ArrayList<>();
        for (int round = 0; round < 6; round++) {
            for (int i = 0; i < 400; i++) {
                retained.add(new byte[64 * 1024]);
            }
            System.gc();
        }
        assertThat(retained).isNotEmpty();

        SelfAudit.Report report = audit.stop(start, SECOND, 600, 1);

        assertThat(report.available())
                .withFailMessage("flight recording should be available on a standard JDK: %s",
                        report.unavailableReason())
                .isTrue();
        assertThat(report.hasPauses())
                .withFailMessage("six forced full GCs over a live set produced no recorded pause - "
                        + "the JFR event wiring is not capturing anything")
                .isTrue();
        assertThat(report.totalPausedNanos()).isPositive();
        assertThat(report.longestPauses()).isNotEmpty();
    }

    @Test
    void eventsOutsideTheRunWindowAreNotAttributedToABucket() {
        SelfAudit audit = SelfAudit.start();
        if (audit == null) {
            return;
        }
        List<byte[]> retained = new ArrayList<>();
        for (int i = 0; i < 400; i++) {
            retained.add(new byte[64 * 1024]);
        }
        System.gc();
        assertThat(retained).isNotEmpty();

        // A run that "started" an hour after these events: nothing can belong to it.
        SelfAudit.Report report = audit.stop(Instant.now().plusSeconds(3600), SECOND, 10, 1);

        assertThat(report.available()).isTrue();
        assertThat(report.hasPauses())
                .withFailMessage("pauses before the run window must not be attributed to it")
                .isFalse();
    }

    @Test
    void pausesPastTheLastBucketAreDropped() {
        SelfAudit audit = SelfAudit.start();
        if (audit == null) {
            return;
        }
        Instant start = Instant.now().minusSeconds(3600);
        List<byte[]> retained = new ArrayList<>();
        for (int i = 0; i < 400; i++) {
            retained.add(new byte[64 * 1024]);
        }
        System.gc();
        assertThat(retained).isNotEmpty();

        // Ten one-second buckets starting an hour ago: today's events fall past the end.
        SelfAudit.Report report = audit.stop(start, SECOND, 10, 1);

        assertThat(report.hasPauses())
                .withFailMessage("events after the last bucket must be dropped, not clamped into it")
                .isFalse();
    }

    // --- rendering ------------------------------------------------------------------------------

    @Test
    void aCleanRunSaysSoRatherThanPrintingNothing() {
        SelfAudit.Report clean = new SelfAudit.Report(true, null, Map.of(), List.of(), 0, 0, 0, 0);

        String out = CliTables.selfAuditTable(clean, null, SECOND);

        assertThat(out).contains("not paused at all");
        assertThat(out).contains("the target's, not this process's");
    }

    @Test
    void anUnavailableRecordingSaysWhyRatherThanLookingClean() {
        SelfAudit.Report missing = SelfAudit.Report.unavailable("JFR is not enabled");

        String out = CliTables.selfAuditTable(missing, null, SECOND);

        assertThat(out).contains("unavailable").contains("JFR is not enabled");
        assertThat(out)
                .withFailMessage("an unreadable recording must not be presented as a clean run")
                .doesNotContain("not paused at all");
    }

    @Test
    void aPauseOverlappingAReportedSpikeIsFlaggedAsContamination() {
        SelfAudit.Report report = new SelfAudit.Report(true, null,
                Map.of(4, 340_000_000L),
                List.of(new SelfAudit.Pause(4, 340_000_000L, "jdk.GCPhasePause")),
                340_000_000L, 1, 340_000_000L, 1);

        var spike = new SpikeCorrelator.Spike(4, 4 * SECOND, "db",
                900_000_000L, 50_000_000L, true, SpikeCorrelator.Verdict.DB);
        var correlation = new SpikeCorrelator.CorrelationResult(List.of(spike), true);

        String out = CliTables.selfAuditTable(report, correlation, SECOND);

        assertThat(out).contains("340ms").contains("GCPhasePause");
        assertThat(out)
                .withFailMessage("a pause in the same bucket as a reported spike is the whole point "
                        + "of this feature and must be called out")
                .contains("overlaps a reported spike");
        assertThat(out).contains("not the target");
    }

    @Test
    void aPauseInAQuietBucketIsReportedWithoutBlamingIt() {
        SelfAudit.Report report = new SelfAudit.Report(true, null,
                Map.of(9, 120_000_000L),
                List.of(new SelfAudit.Pause(9, 120_000_000L, "jdk.GCPhasePause")),
                120_000_000L, 1, 120_000_000L, 1);

        var spike = new SpikeCorrelator.Spike(4, 4 * SECOND, "db",
                900_000_000L, 50_000_000L, true, SpikeCorrelator.Verdict.DB);
        var correlation = new SpikeCorrelator.CorrelationResult(List.of(spike), true);

        String out = CliTables.selfAuditTable(report, correlation, SECOND);

        assertThat(out).contains("120ms");
        assertThat(out)
                .withFailMessage("a pause that did not coincide with a spike must not be blamed for one")
                .doesNotContain("overlaps a reported spike");
    }

    @Test
    void noAuditAtAllRendersNothing() {
        assertThat(CliTables.selfAuditTable(null, null, SECOND)).isEmpty();
    }
}
