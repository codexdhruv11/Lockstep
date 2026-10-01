package com.lockstep.analysis;

import static org.assertj.core.api.Assertions.assertThat;

import com.lockstep.analysis.SpikeCorrelator.CorrelationResult;
import com.lockstep.analysis.SpikeCorrelator.Spike;
import com.lockstep.analysis.SpikeCorrelator.Thresholds;
import com.lockstep.analysis.SpikeCorrelator.Verdict;
import com.lockstep.core.PacedLoop;
import com.lockstep.core.RunContext;
import com.lockstep.core.RunCoordinator;
import com.lockstep.stats.Bucket;
import com.lockstep.stats.BucketSeries;
import com.lockstep.stats.HistogramRecorder;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class SpikeCorrelatorTest {
    private static final long MS = 1_000_000L;
    private static final long SECOND = 1_000_000_000L;

    private static Bucket bucket(int index, long p99Nanos) {
        return new Bucket(index, index * SECOND, (index + 1) * SECOND,
                100, 0, p99Nanos / 2, p99Nanos / 2, p99Nanos - MS, p99Nanos, p99Nanos, p99Nanos);
    }

    private static Bucket emptyBucket(int index) {
        return Bucket.empty(index, SECOND);
    }

    private static Map<String, List<Bucket>> storage(String name, List<Bucket> buckets) {
        Map<String, List<Bucket>> map = new LinkedHashMap<>();
        map.put(name, buckets);
        return map;
    }

    @Test
    void aStorageSpikeTheAppDidNotFeelIsMasked() {
        CorrelationResult result = SpikeCorrelator.correlate(
                List.of(bucket(0, 20 * MS), bucket(1, 20 * MS)),
                storage("db", List.of(bucket(0, 20 * MS), bucket(1, 500 * MS))),
                Thresholds.defaults());

        assertThat(result.spikes()).hasSize(1);
        Spike spike = result.spikes().get(0);
        assertThat(spike.bucketIndex()).isEqualTo(1);
        assertThat(spike.storageRunner()).isEqualTo("db");
        assertThat(spike.masked()).isTrue();
        assertThat(spike.storageP99Nanos()).isEqualTo(500 * MS);
        assertThat(spike.appP99Nanos()).isEqualTo(20 * MS);
        assertThat(result.masked()).hasSize(1);
        assertThat(result.correlated()).isEmpty();
    }

    @Test
    void aStorageSpikeTheAppFeltIsCorrelatedAndNamesTheSlowerLayer() {
        CorrelationResult result = SpikeCorrelator.correlate(
                List.of(bucket(0, 20 * MS), bucket(1, 800 * MS)),
                storage("db", List.of(bucket(0, 20 * MS), bucket(1, 900 * MS))),
                Thresholds.defaults());

        assertThat(result.spikes()).hasSize(1);
        Spike spike = result.spikes().get(0);
        assertThat(spike.masked()).isFalse();
        assertThat(spike.verdict()).isEqualTo(Verdict.DB);
        assertThat(result.correlated()).hasSize(1);
    }

    @Test
    void theAppAddingTimeOfItsOwnComparableToTheStoresIsCalledEven() {
        // 900ms against 500ms: the store accounts for 500 of the app's 900, leaving 400 of the
        // app's own. Neither explains the spike by itself.
        CorrelationResult result = SpikeCorrelator.correlate(
                List.of(bucket(0, 900 * MS)),
                storage("db", List.of(bucket(0, 500 * MS))),
                Thresholds.defaults());

        assertThat(result.spikes().get(0).verdict()).isEqualTo(Verdict.EVEN);
    }

    @Test
    void theAppDominatingTheStoreNamesHttp() {
        // 1.5s against 300ms: four fifths of the app's time is its own.
        CorrelationResult result = SpikeCorrelator.correlate(
                List.of(bucket(0, 1500 * MS)),
                storage("db", List.of(bucket(0, 300 * MS))),
                Thresholds.defaults());

        assertThat(result.spikes().get(0).verdict()).isEqualTo(Verdict.HTTP);
    }

    /**
     * An endpoint that waits on the database is necessarily slower than the database, so being
     * slower by a margin consistent with a request round trip is not evidence against the app.
     * This used to be read as HTTP, which named the app for faults that were entirely the
     * database's - confirmed against a real database in {@code SpikeCorrelationOracleTest}.
     */
    @Test
    void theAppBeingSlowerOnlyByItsOwnOverheadStillNamesTheStore() {
        CorrelationResult result = SpikeCorrelator.correlate(
                List.of(bucket(0, 502 * MS)),
                storage("db", List.of(bucket(0, 500 * MS))),
                Thresholds.defaults());

        assertThat(result.spikes().get(0).verdict()).isEqualTo(Verdict.DB);
    }

    @Test
    void aSmallExcessOverTheStoreIsAttributedToTheStore() {
        assertThat(SpikeCorrelator.verdictFor("db", 530 * MS, 500 * MS)).isEqualTo(Verdict.DB);
        assertThat(SpikeCorrelator.verdictFor("db", 500 * MS, 530 * MS)).isEqualTo(Verdict.DB);
        assertThat(SpikeCorrelator.verdictFor("redis", 500 * MS, 530 * MS)).isEqualTo(Verdict.REDIS);
        assertThat(SpikeCorrelator.verdictFor("redis", 530 * MS, 500 * MS)).isEqualTo(Verdict.REDIS);
    }

    @Test
    void aBaselineSeparatesAnAppsFixedCostFromTheSpike() {
        // An endpoint that always costs 200ms of its own, hit by a database that jumps from 2ms
        // to 300ms. Compared directly, 500ms against 300ms looks like the app. Compared as
        // growth, 300ms against 298ms is the database and nothing else.
        assertThat(SpikeCorrelator.verdictFor("db", 500 * MS, 300 * MS, 200 * MS, 2 * MS))
                .isEqualTo(Verdict.DB);
    }

    @Test
    void aStoreAtItsBaselineIsNotBlamedForTheAppsSpike() {
        assertThat(SpikeCorrelator.verdictFor("db", 800 * MS, 5 * MS, 10 * MS, 5 * MS))
                .isEqualTo(Verdict.HTTP);
    }

    @Test
    void spikingBucketsCannotDragTheBaselineUp() {
        // Three quiet buckets around 10ms and two spiking: the lower quartile stays with the
        // quiet ones even though the spikes are 40% of the run.
        List<com.lockstep.stats.Bucket> buckets = List.of(
                bucket(0, 10 * MS), bucket(1, 12 * MS), bucket(2, 11 * MS),
                bucket(3, 900 * MS), bucket(4, 950 * MS));

        assertThat(SpikeCorrelator.baselineOf(buckets, 0)).isEqualTo(11 * MS);
    }

    @Test
    void aFaultCoveringMostOfTheRunStillLeavesTheBaselineWithTheQuietBuckets() {
        // Eight of eleven buckets elevated: the median would sit inside the fault.
        List<com.lockstep.stats.Bucket> buckets = new java.util.ArrayList<>();
        buckets.add(bucket(0, 20 * MS));
        buckets.add(bucket(1, 22 * MS));
        buckets.add(bucket(2, 21 * MS));
        for (int i = 3; i < 11; i++) {
            buckets.add(bucket(i, 800 * MS));
        }

        assertThat(SpikeCorrelator.baselineOf(buckets, 0))
                .withFailMessage("the baseline must come from the quiet buckets, not the fault")
                .isLessThanOrEqualTo(25 * MS);
    }

    @Test
    void anEmptyBucketHasNoP99AndIsNotPartOfTheBaseline() {
        List<com.lockstep.stats.Bucket> buckets = new java.util.ArrayList<>(List.of(
                bucket(0, 40 * MS), bucket(1, 60 * MS)));
        buckets.add(com.lockstep.stats.Bucket.empty(2, SECOND));

        assertThat(SpikeCorrelator.baselineOf(buckets, 0))
                .withFailMessage("an empty bucket counted as 0ms would drag the baseline to zero")
                .isEqualTo(40 * MS);
    }

    @Test
    void aWarmupBucketIsNotPartOfTheBaseline() {
        List<com.lockstep.stats.Bucket> buckets = List.of(
                bucket(0, 500 * MS), bucket(1, 20 * MS), bucket(2, 22 * MS));

        assertThat(SpikeCorrelator.baselineOf(buckets, SECOND))
                .withFailMessage("a cold first bucket must not set the baseline for the rest")
                .isEqualTo(20 * MS);
    }

    @Test
    void anHttpOnlySpikeIsDeliberatelyNotFlagged() {
        CorrelationResult result = SpikeCorrelator.correlate(
                List.of(bucket(0, 20 * MS), bucket(1, 900 * MS)),
                storage("db", List.of(bucket(0, 5 * MS), bucket(1, 6 * MS))),
                Thresholds.defaults());

        assertThat(result.spikes()).isEmpty();
        assertThat(result.appReferencePresent()).isTrue();
    }

    @Test
    void bothStorageRunnersAreCheckedAgainstTheirOwnThresholds() {
        Map<String, List<Bucket>> storage = new LinkedHashMap<>();
        storage.put("db", List.of(bucket(0, 150 * MS)));
        storage.put("redis", List.of(bucket(0, 60 * MS)));

        CorrelationResult result = SpikeCorrelator.correlate(
                List.of(bucket(0, 20 * MS)),
                storage,
                new Thresholds(100 * MS, 100 * MS, 50 * MS));

        assertThat(result.spikes()).hasSize(2);
        assertThat(result.spikes()).extracting(Spike::storageRunner)
                .containsExactlyInAnyOrder("db", "redis");
        assertThat(result.spikes()).allMatch(Spike::masked);
    }

    @Test
    void redisUnderItsThresholdIsNotFlaggedWhileDbOverItsOwnIs() {
        Map<String, List<Bucket>> storage = new LinkedHashMap<>();
        storage.put("db", List.of(bucket(0, 150 * MS)));
        storage.put("redis", List.of(bucket(0, 80 * MS)));

        CorrelationResult result = SpikeCorrelator.correlate(
                List.of(bucket(0, 20 * MS)), storage,
                new Thresholds(100 * MS, 100 * MS, 100 * MS));

        assertThat(result.spikes()).hasSize(1);
        assertThat(result.spikes().get(0).storageRunner()).isEqualTo("db");
    }

    @Test
    void spikesComeBackInTimeOrder() {
        Map<String, List<Bucket>> storage = new LinkedHashMap<>();
        storage.put("db", List.of(bucket(0, 5 * MS), bucket(3, 500 * MS)));
        storage.put("redis", List.of(bucket(1, 500 * MS), bucket(2, 500 * MS)));

        CorrelationResult result = SpikeCorrelator.correlate(
                List.of(bucket(0, 20 * MS), bucket(1, 20 * MS), bucket(2, 20 * MS), bucket(3, 20 * MS)),
                storage, Thresholds.defaults());

        assertThat(result.spikes()).extracting(Spike::bucketIndex).containsExactly(1, 2, 3);
    }

    @Test
    void bucketsPresentOnOnlyOneSideAreSkippedRatherThanGuessed() {
        CorrelationResult result = SpikeCorrelator.correlate(
                List.of(bucket(0, 20 * MS)),
                storage("db", List.of(bucket(0, 20 * MS), bucket(1, 900 * MS))),
                Thresholds.defaults());

        assertThat(result.spikes()).isEmpty();
    }

    @Test
    void emptyBucketsAreIgnoredOnBothSides() {
        CorrelationResult result = SpikeCorrelator.correlate(
                List.of(emptyBucket(0), bucket(1, 20 * MS)),
                storage("db", List.of(bucket(0, 900 * MS), emptyBucket(1))),
                Thresholds.defaults());

        assertThat(result.spikes()).isEmpty();
    }

    @Test
    void aRunWithNoApplicationSideSaysSoInsteadOfReportingEverythingAsMasked() {
        HistogramRecorder recorder = new HistogramRecorder(SECOND, 2);
        recorder.record(0, 900 * MS, 900 * MS, true, null);
        Map<String, PacedLoop.LoopResult> runners = new LinkedHashMap<>();
        runners.put("db", new PacedLoop.LoopResult(recorder.snapshot(), 1, 0, 0, 0, 0, 1, true));
        var result = new RunCoordinator.RunResult(
                new RunContext(0, Instant.EPOCH, 2 * SECOND, SECOND, 0, 4, 0), runners, null);

        CorrelationResult correlation = SpikeCorrelator.correlate(result, Thresholds.defaults());

        assertThat(correlation.appReferencePresent()).isFalse();
        assertThat(correlation.spikes()).isEmpty();
    }

    @Test
    void correlatesAWholeRunEndToEnd() {
        HistogramRecorder http = new HistogramRecorder(SECOND, 4);
        HistogramRecorder db = new HistogramRecorder(SECOND, 4);
        for (int bucketIndex = 0; bucketIndex < 4; bucketIndex++) {
            boolean badWindow = bucketIndex == 2;
            for (int i = 0; i < 50; i++) {
                long httpLatency = badWindow ? 700 * MS : 15 * MS;
                long dbLatency = badWindow ? 650 * MS : 5 * MS;
                http.record(bucketIndex * SECOND, httpLatency, httpLatency, true, 200);
                db.record(bucketIndex * SECOND, dbLatency, dbLatency, true, null);
            }
        }
        Map<String, PacedLoop.LoopResult> runners = new LinkedHashMap<>();
        BucketSeries httpSeries = http.snapshot();
        BucketSeries dbSeries = db.snapshot();
        runners.put("http", new PacedLoop.LoopResult(httpSeries, 200, 0, 0, 0, 0, 200, true));
        runners.put("db", new PacedLoop.LoopResult(dbSeries, 200, 0, 0, 0, 0, 200, true));
        var result = new RunCoordinator.RunResult(
                new RunContext(0, Instant.EPOCH, 4 * SECOND, SECOND, 0, 8, 0), runners, null);

        CorrelationResult correlation = SpikeCorrelator.correlate(result, Thresholds.defaults());

        assertThat(correlation.appReferencePresent()).isTrue();
        assertThat(correlation.spikes()).hasSize(1);
        Spike spike = correlation.spikes().get(0);
        assertThat(spike.bucketIndex()).isEqualTo(2);
        assertThat(spike.masked()).isFalse();
        // The app rose 685ms over its 15ms baseline while the database rose 645ms over its 5ms
        // one. The database accounts for 94% of the app's rise, so it is the database.
        assertThat(spike.verdict()).isEqualTo(Verdict.DB);
    }
}
