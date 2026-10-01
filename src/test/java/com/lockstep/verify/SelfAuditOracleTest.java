package com.lockstep.verify;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.lockstep.analysis.SelfAudit;
import com.lockstep.analysis.SpikeCorrelator;
import com.lockstep.report.CliTables;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Checks the generator self-audit against a source that does not go through JFR at all.
 *
 * <p>The self-audit exists to answer one question: was the latency printed above the target's, or
 * was it partly this process stopping to collect garbage? If it under-reports, Lockstep certifies
 * its own contaminated measurements as clean, which is worse than not having the feature. So it
 * needs an oracle, and there is a good one: {@link GarbageCollectorMXBean#getCollectionTime()} is
 * maintained by the VM itself, independently of the flight recorder, and its delta across a window
 * is the pause time the collectors took in that window.
 *
 * <p>Two things are verified here. The first is the total, against the MXBean. The second is the
 * attribution in time, which has its own oracle: garbage collection only happens when something
 * allocates, so if the test allocates during one known second and sits quiet either side of it,
 * every pause must be attributed to that second's bucket.
 *
 * <p>These tests force real collections in the test JVM and read a JVM-wide counter, so they are
 * sensitive to anything else allocating at the same time. They are deliberately not parallelised.
 */
final class SelfAuditOracleTest {

    private static final long MS = 1_000_000L;
    private static final long SECOND = 1_000_000_000L;

    /**
     * The MXBean counts in whole milliseconds and the recorder in nanoseconds, so the two can
     * disagree by a fraction of a millisecond per collection by rounding alone. Measured on this
     * machine the two agreed exactly (70ms against 70ms, and 120ms against 120ms), so the
     * allowance is for rounding and for a collection landing in the sliver between the two reads,
     * not for a difference in what is being counted.
     */
    private static final double TOLERANCE = 0.15;
    private static final long TOLERANCE_FLOOR_NANOS = 15 * MS;

    private static final List<String> LEDGER = new ArrayList<>();

    private static void record(String claim, Object expected, Object reported, boolean ok) {
        LEDGER.add("  %-44s expected %-16s reported %-16s %s".formatted(
                claim, expected, reported, ok ? "MATCH" : "MISMATCH"));
    }

    private static void printLedger(String fixture) {
        System.out.println("\n=== oracle verification · " + fixture + " ===");
        LEDGER.forEach(System.out::println);
        LEDGER.clear();
    }

    /** Total pause time the collectors report, in nanoseconds. The independent source. */
    private static long mxBeanPausedNanos() {
        long millis = 0;
        for (GarbageCollectorMXBean bean : ManagementFactory.getGarbageCollectorMXBeans()) {
            long time = bean.getCollectionTime();
            if (time > 0) {
                millis += time;
            }
        }
        return millis * MS;
    }

    private static boolean agrees(long expected, long reported) {
        long allowance = Math.max(TOLERANCE_FLOOR_NANOS, (long) (expected * TOLERANCE));
        return Math.abs(expected - reported) <= allowance;
    }

    private static String ms(long nanos) {
        return "%.1fms".formatted(nanos / 1_000_000.0);
    }

    /** Churns short-lived garbage for a fixed time. Young collections, no full GC. */
    private static Object sink;

    private static void churnFor(long millis) {
        long deadline = System.nanoTime() + millis * MS;
        while (System.nanoTime() < deadline) {
            Object[] junk = new Object[2048];
            for (int i = 0; i < junk.length; i++) {
                junk[i] = new byte[1024];
            }
            sink = junk;
        }
    }

    private static void quietFor(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // --- the total is the VM's own total --------------------------------------------------------

    /**
     * The case that was broken. A generator under steady load produces a long tail of young
     * collections of a few milliseconds each and no single long pause. The threshold used to be
     * applied before the total was summed, so all of that time was dropped: measured here, 43
     * pauses came to 120ms of real stall in a four-second window and the audit reported zero,
     * while the report printed "no JVM pause above 10ms; the latencies above are the target's, not
     * this process's". Demanding agreement with the MXBean at the default threshold is what keeps
     * that from coming back.
     */
    @Test
    void manyShortPausesAreCountedAndMatchTheVmsOwnTotal() {
        SelfAudit audit = SelfAudit.start();
        assumeTrue(audit != null, "flight recorder unavailable");

        long before = mxBeanPausedNanos();
        Instant start = Instant.now();

        churnFor(3_000);

        long trueGcNanos = mxBeanPausedNanos() - before;
        // The default threshold deliberately: the point is that it no longer hides the total.
        SelfAudit.Report report = audit.stop(start, SECOND, 10);

        assumeTrue(report.available(), "recording unreadable: " + report.unavailableReason());
        assumeTrue(trueGcNanos > 0, "no collection happened; nothing to compare against");

        record("GC pause total (vs GC MXBean)", ms(trueGcNanos), ms(report.gcPausedNanos()),
                agrees(trueGcNanos, report.gcPausedNanos()));
        record("counted despite none over 10ms", "> 0",
                report.hasSignificantPauses() ? "some were over 10ms" : ms(report.totalPausedNanos()),
                report.totalPausedNanos() > 0);
        record("total >= GC share", "true",
                report.totalPausedNanos() >= report.gcPausedNanos(),
                report.totalPausedNanos() >= report.gcPausedNanos());
        printLedger("steady allocation, default 10ms threshold");

        assertThat(report.gcPausedNanos())
                .withFailMessage("""
                        the audit reported %s of GC pause and the VM's own collectors reported \
                        %s over the same window. A self-audit that under-reports hands back a \
                        clean bill of health for a contaminated measurement.""",
                        ms(report.gcPausedNanos()), ms(trueGcNanos))
                .matches(reported -> agrees(trueGcNanos, reported));

        assertThat(report.totalPausedNanos())
                .withFailMessage("""
                        %s of real stall and the audit totalled zero. Short pauses are not \
                        noise - a tail of 2-9ms pauses is exactly what moves a p99, and \
                        filtering them out of the total is how this process's own stalls got \
                        attributed to the target.""", ms(trueGcNanos))
                .isPositive();

        // The total includes safepoint time, which the MXBean does not count, so it can only be
        // the larger of the two.
        assertThat(report.totalPausedNanos()).isGreaterThanOrEqualTo(report.gcPausedNanos());
    }

    @Test
    void aForcedCollectionsPauseMatchesTheVmsOwnTotal() {
        SelfAudit audit = SelfAudit.start();
        assumeTrue(audit != null, "flight recorder unavailable");

        long before = mxBeanPausedNanos();
        Instant start = Instant.now();

        // A live set plus forced full collections: long pauses, well over the threshold.
        List<byte[]> retained = new ArrayList<>();
        for (int round = 0; round < 5; round++) {
            for (int i = 0; i < 300; i++) {
                retained.add(new byte[64 * 1024]);
            }
            System.gc();
        }
        assertThat(retained).isNotEmpty();

        long trueGcNanos = mxBeanPausedNanos() - before;
        SelfAudit.Report report = audit.stop(start, SECOND, 10);

        assumeTrue(report.available(), "recording unreadable: " + report.unavailableReason());
        assumeTrue(trueGcNanos > 0, "no collection happened");

        record("GC pause total (vs GC MXBean)", ms(trueGcNanos), ms(report.gcPausedNanos()),
                agrees(trueGcNanos, report.gcPausedNanos()));
        record("forced collections were seen", "5 or more", report.gcPauseCount(),
                report.gcPauseCount() >= 5);
        printLedger("five forced full collections");

        assertThat(report.gcPausedNanos())
                .withFailMessage("audit reported %s, the VM's collectors reported %s",
                        ms(report.gcPausedNanos()), ms(trueGcNanos))
                .matches(reported -> agrees(trueGcNanos, reported));
        assertThat(report.gcPauseCount())
                .withFailMessage("five System.gc() calls over a live set produced %d recorded "
                        + "pauses", report.gcPauseCount())
                .isGreaterThanOrEqualTo(5);
    }

    // --- the attribution in time is right ------------------------------------------------------

    /**
     * Garbage collection needs garbage. So allocating during exactly one second of a five-second
     * window makes the correct bucket known in advance, which is the part of this feature that
     * actually matters: a pause reported in the wrong bucket would exonerate the generator for a
     * spike it caused, or blame it for one it did not.
     */
    @Test
    void pauseTimeIsAttributedToTheSecondTheAllocationHappenedIn() {
        SelfAudit audit = SelfAudit.start();
        assumeTrue(audit != null, "flight recorder unavailable");

        Instant start = Instant.now();
        int churnBucket = 2;

        quietFor(2_000);                 // buckets 0 and 1: nothing allocated
        churnFor(1_000);                 // bucket 2: all of the garbage
        quietFor(2_000);                 // buckets 3 and 4: quiet again

        SelfAudit.Report report = audit.stop(start, SECOND, 5, 0);
        assumeTrue(report.available(), "recording unreadable: " + report.unavailableReason());
        assumeTrue(report.totalPausedNanos() > 0, "no pause recorded at all");

        long inChurnBucket = report.pausedNanosIn(churnBucket);
        long elsewhere = report.totalPausedNanos() - inChurnBucket;
        double shareInChurnBucket = (double) inChurnBucket / report.totalPausedNanos();
        long beforeChurn = report.pausedNanosIn(0) + report.pausedNanosIn(1);

        record("bucket holding the pause time", "bucket " + churnBucket,
                "bucket " + churnBucket + " holds "
                        + "%.0f%%".formatted(shareInChurnBucket * 100),
                shareInChurnBucket >= 0.80);
        record("pause time before any allocation", "0ms", ms(beforeChurn),
                beforeChurn <= 5 * MS);
        record("pause time outside that second", "near 0", ms(elsewhere),
                elsewhere <= Math.max(10 * MS, inChurnBucket / 4));
        printLedger("allocation confined to second " + churnBucket + " of a 5s window");

        assertThat(shareInChurnBucket)
                .withFailMessage("""
                        allocation happened only in second %d, so that is where the pause time \
                        must be attributed. %s of %s landed there (%.0f%%). Pause time in the \
                        wrong bucket means the generator is exonerated for a spike it caused, or \
                        blamed for one it did not: per-bucket map was %s""",
                        churnBucket, ms(inChurnBucket), ms(report.totalPausedNanos()),
                        shareInChurnBucket * 100, report.pausedNanosPerBucket())
                .isGreaterThanOrEqualTo(0.80);

        assertThat(beforeChurn)
                .withFailMessage("nothing was allocated in the first two seconds, so no "
                        + "collection pause can belong there; got %s", ms(beforeChurn))
                .isLessThanOrEqualTo(5 * MS);
    }

    // --- what the report says about it ---------------------------------------------------------

    /**
     * The renderer is part of the claim. A truthful total that prints as a clean bill of health is
     * no better than a wrong total, and the old wording said exactly that whenever every pause
     * was short.
     */
    @Test
    void aRunWithShortPausesIsNotPresentedAsACleanRun() {
        SelfAudit audit = SelfAudit.start();
        assumeTrue(audit != null, "flight recorder unavailable");

        Instant start = Instant.now();
        churnFor(2_000);
        SelfAudit.Report report = audit.stop(start, SECOND, 4);
        assumeTrue(report.available(), "recording unreadable");
        assumeTrue(report.totalPausedNanos() > 0, "no pause recorded");

        String out = CliTables.selfAuditTable(report, null, SECOND, 2 * SECOND);

        assertThat(out)
                .withFailMessage("""
                        this process was paused for %s and the report said it was not paused at \
                        all. Output was:
                        %s""", ms(report.totalPausedNanos()), out)
                .doesNotContain("not paused at all");
        assertThat(out).contains("this process was paused for");

        if (!report.hasSignificantPauses()) {
            assertThat(out)
                    .withFailMessage("with no single pause over the threshold there is no row to "
                            + "print, so the total has to be stated in words instead")
                    .contains("add up");
        }
    }

    /**
     * The contamination flag has to be judged on all the time a bucket lost, not only on the
     * pauses long enough to be listed. Forty short pauses in a spike bucket contaminate it just
     * as thoroughly as one long one, and none of them would appear in the table.
     */
    @Test
    void shortPausesInASpikeBucketStillCountAsContamination() {
        // Hand-built so the figures are exact: 40ms spread over a bucket, no single pause listed.
        SelfAudit.Report manyShort = new SelfAudit.Report(true, null,
                java.util.Map.of(4, 40 * MS), List.of(), 40 * MS, 20, 40 * MS, 0);

        var spike = new SpikeCorrelator.Spike(4, 4 * SECOND, "db",
                900 * MS, 50 * MS, true, SpikeCorrelator.Verdict.DB);
        var correlation = new SpikeCorrelator.CorrelationResult(List.of(spike), true);

        String out = CliTables.selfAuditTable(manyShort, correlation, SECOND, 10 * SECOND);

        assertThat(out)
                .withFailMessage("""
                        bucket 4 lost 40ms to this process and bucket 4 is reported as a storage \
                        spike. That overlap is the whole point of the feature and it must be \
                        stated even though no individual pause was long enough to list. Output:
                        %s""", out)
                .contains("not the target");
        assertThat(out).contains("40ms");
    }

    @Test
    void aGenuinelyQuietRunStillSaysSo() {
        SelfAudit.Report clean = new SelfAudit.Report(true, null,
                java.util.Map.of(), List.of(), 0, 0, 0, 0);

        String out = CliTables.selfAuditTable(clean, null, SECOND, 10 * SECOND);

        assertThat(out).contains("not paused at all");
        assertThat(out)
                .withFailMessage("a quiet run is the one case where the reassurance is earned")
                .contains("the target's, not this process's");
    }

    /** The fraction is what tells a reader whether to care. */
    @Test
    void thePausedFractionIsReportedAgainstTheRunLength() {
        SelfAudit.Report report = new SelfAudit.Report(true, null,
                java.util.Map.of(1, 300 * MS), List.of(), 300 * MS, 4, 300 * MS, 0);

        assertThat(report.pausedFractionOf(10 * SECOND)).isEqualTo(0.03);

        String out = CliTables.selfAuditTable(report, null, SECOND, 10 * SECOND);
        assertThat(out)
                .withFailMessage("300ms of stall in a 10s run is 3%%, and the share is what makes "
                        + "the number interpretable. Output: %s", out)
                .contains("3.0% of the run");

        assertThat(report.pausedFractionOf(0))
                .withFailMessage("a zero-length run must not divide by zero")
                .isZero();
    }

    @Test
    void anEventBeforeTheRunStartedIsNotAttributedToTheRun() {
        SelfAudit audit = SelfAudit.start();
        assumeTrue(audit != null, "flight recorder unavailable");

        churnFor(600);
        // The run "starts" only now, after all the allocation already happened.
        Instant lateStart = Instant.now();
        quietFor(300);

        SelfAudit.Report report = audit.stop(lateStart, SECOND, 2, 0);
        assumeTrue(report.available(), "recording unreadable");

        assertThat(report.totalPausedNanos())
                .withFailMessage("""
                        every collection happened before the run window opened, so none of it \
                        belongs to the run. Reporting it would blame the run for pauses that \
                        preceded it: got %s in buckets %s""",
                        ms(report.totalPausedNanos()), report.pausedNanosPerBucket())
                .isLessThanOrEqualTo(10 * MS);
    }

    @Test
    void theWindowEndsWithTheRun() {
        SelfAudit audit = SelfAudit.start();
        assumeTrue(audit != null, "flight recorder unavailable");

        Instant start = Instant.now();
        quietFor(1_200);
        // Allocation happens past the end of a 1-second, 1-bucket window.
        churnFor(800);

        SelfAudit.Report report = audit.stop(start, SECOND, 1, 0);
        assumeTrue(report.available(), "recording unreadable");

        assertThat(report.pausedNanosPerBucket().keySet())
                .withFailMessage("a one-bucket window can only hold bucket 0; got %s",
                        report.pausedNanosPerBucket().keySet())
                .allMatch(index -> index == 0);
    }

    @Test
    void durationIsReportedInNanosecondsNotMilliseconds() {
        // A unit slip here would show as "70ns of pause" in the report and be dismissed as noise.
        SelfAudit audit = SelfAudit.start();
        assumeTrue(audit != null, "flight recorder unavailable");

        long before = mxBeanPausedNanos();
        Instant start = Instant.now();
        List<byte[]> retained = new ArrayList<>();
        for (int i = 0; i < 400; i++) {
            retained.add(new byte[64 * 1024]);
        }
        System.gc();
        long trueGcNanos = mxBeanPausedNanos() - before;
        SelfAudit.Report report = audit.stop(start, SECOND, 5, 0);

        assumeTrue(report.available() && trueGcNanos > 0, "no measurable collection");
        assertThat(retained).isNotEmpty();

        // A millisecond figure stored as nanoseconds would be a million times too small.
        assertThat(report.gcPausedNanos())
                .withFailMessage("a real collection pause is at least a few hundred microseconds; "
                        + "%d suggests the units are wrong", report.gcPausedNanos())
                .isGreaterThan(100_000L);
        assertThat(Duration.ofNanos(report.gcPausedNanos()))
                .isLessThan(Duration.ofSeconds(30));
    }
}
