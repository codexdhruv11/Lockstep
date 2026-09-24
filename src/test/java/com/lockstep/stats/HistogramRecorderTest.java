package com.lockstep.stats;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

final class HistogramRecorderTest {
    private static final long MS = 1_000_000L;
    private static final long SECOND = 1_000_000_000L;

    @Test
    void knownDistributionYieldsKnownPercentiles() {
        HistogramRecorder recorder = new HistogramRecorder(SECOND, 1);

        for (int i = 1; i <= 1000; i++) {
            recorder.record(0, i * MS, i * MS, true, null);
        }

        RunnerSummary summary = recorder.snapshot().summarize("db", SECOND);

        assertThat(summary.count()).isEqualTo(1000);
        assertThat(summary.p50Nanos()).isBetween(495 * MS, 505 * MS);
        assertThat(summary.p95Nanos()).isBetween(940 * MS, 960 * MS);
        assertThat(summary.p99Nanos()).isBetween(980 * MS, 1000 * MS);
        assertThat(summary.maxNanos()).isBetween(990 * MS, 1010 * MS);
        assertThat(summary.meanNanos()).isBetween(495 * MS, 505 * MS);
    }

    @Test
    void bucketIndexComesFromTheScheduledOffsetNotCompletionTime() {
        HistogramRecorder recorder = new HistogramRecorder(SECOND, 5);

        recorder.record(500 * MS, 2500 * MS, 2500 * MS, true, null);
        recorder.record(1500 * MS, 10 * MS, 10 * MS, true, null);
        recorder.record(4999 * MS, 10 * MS, 10 * MS, true, null);

        var buckets = recorder.snapshot().buckets();
        assertThat(buckets.get(0).count()).isEqualTo(1);
        assertThat(buckets.get(1).count()).isEqualTo(1);
        assertThat(buckets.get(2).count()).isZero();
        assertThat(buckets.get(4).count()).isEqualTo(1);
    }

    @Test
    void bucketBoundaryIsHalfOpen() {
        HistogramRecorder recorder = new HistogramRecorder(SECOND, 3);
        recorder.record(SECOND - 1, MS, MS, true, null);
        recorder.record(SECOND, MS, MS, true, null);

        var buckets = recorder.snapshot().buckets();
        assertThat(buckets.get(0).count()).isEqualTo(1);
        assertThat(buckets.get(1).count()).isEqualTo(1);
        assertThat(buckets.get(0).startOffsetNanos()).isZero();
        assertThat(buckets.get(0).endOffsetNanos()).isEqualTo(SECOND);
    }

    @Test
    void runWidePercentileMergesHistogramsRatherThanAveragingPercentiles() {
        HistogramRecorder recorder = new HistogramRecorder(SECOND, 10);

        for (int bucket = 0; bucket < 9; bucket++) {
            for (int i = 0; i < 1000; i++) {
                recorder.record(bucket * SECOND, 10 * MS, 10 * MS, true, null);
            }
        }
        for (int i = 0; i < 1000; i++) {
            recorder.record(9 * SECOND, 900 * MS, 900 * MS, true, null);
        }

        BucketSeries series = recorder.snapshot();
        long mergedP99 = series.summarize("db", 10 * SECOND).p99Nanos();

        double averageOfBucketP99s = series.nonEmptyBuckets().stream()
                .mapToLong(Bucket::p99Nanos)
                .average()
                .orElseThrow();

        assertThat(mergedP99).isBetween(891 * MS, 909 * MS);
        assertThat(averageOfBucketP99s).isLessThan(110.0 * MS);
        assertThat((double) mergedP99).isGreaterThan(averageOfBucketP99s * 5);
    }

    @Test
    void memoryIsBoundedByBucketCountNotObservationCount() {
        HistogramRecorder small = new HistogramRecorder(SECOND, 60);
        recordSpread(small, 10_000);
        long smallHeap = retainedHeapOf(small);

        HistogramRecorder large = new HistogramRecorder(SECOND, 60);
        recordSpread(large, 10_000_000);
        long largeHeap = retainedHeapOf(large);

        assertThat(large.snapshot().totalCount()).isEqualTo(10_000_000);
        assertThat(small.snapshot().totalCount()).isEqualTo(10_000);

        assertThat(largeHeap - smallHeap).isLessThan(32L * 1024 * 1024);
        assertThat(large.estimatedFootprintBytes())
                .isLessThan((long) (small.estimatedFootprintBytes() * 1.1));
    }

    private static long retainedHeapOf(HistogramRecorder recorder) {
        Runtime runtime = Runtime.getRuntime();
        for (int attempt = 0; attempt < 4; attempt++) {
            System.gc();
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        long used = runtime.totalMemory() - runtime.freeMemory();

        assertThat(recorder.snapshot().totalCount()).isNotNegative();
        return used;
    }

    private static void recordSpread(HistogramRecorder recorder, int operations) {
        for (int i = 0; i < operations; i++) {
            long scheduled = (i % 60) * SECOND;
            long latency = (1 + (i % 500)) * MS;
            recorder.record(scheduled, latency, latency, true, null);
        }
    }

    @Test
    void errorsAndStatusCodesAreTalliedPerBucketAndPerRun() {
        HistogramRecorder recorder = new HistogramRecorder(SECOND, 2);
        recorder.record(0, MS, MS, true, 200);
        recorder.record(0, MS, MS, false, 500);
        recorder.record(SECOND, MS, MS, true, 200);
        recorder.record(SECOND, MS, MS, false, 429);

        BucketSeries series = recorder.snapshot();
        assertThat(series.errorCount()).isEqualTo(2);
        assertThat(series.buckets().get(0).errorCount()).isEqualTo(1);
        assertThat(series.buckets().get(1).errorCount()).isEqualTo(1);
        assertThat(series.statusCounts()).containsExactly(
                java.util.Map.entry(200, 2L),
                java.util.Map.entry(429, 1L),
                java.util.Map.entry(500, 1L));
        assertThat(series.summarize("http", 2 * SECOND).successRate()).isEqualTo(0.5);
    }

    @Test
    void queueDelayIsVisibleAsTheGapBetweenLatencyAndServiceTime() {
        HistogramRecorder recorder = new HistogramRecorder(SECOND, 1);

        for (int i = 0; i < 100; i++) {
            recorder.record(0, 100 * MS, 10 * MS, true, null);
        }

        RunnerSummary summary = recorder.snapshot().summarize("db", SECOND);
        assertThat(summary.p99Nanos()).isBetween(99 * MS, 101 * MS);
        assertThat(summary.serviceP99Nanos()).isBetween(9 * MS, 11 * MS);
        assertThat(summary.queueDelayP99Nanos()).isBetween(88 * MS, 92 * MS);

        assertThat(HistogramRecorder.PERCENTILE_PRECISION).isEqualTo(0.01);
    }

    @Test
    void outOfRangeOffsetsAreClampedAndCounted() {
        HistogramRecorder recorder = new HistogramRecorder(SECOND, 3);
        recorder.record(-5 * SECOND, MS, MS, true, null);
        recorder.record(99 * SECOND, MS, MS, true, null);
        recorder.record(SECOND, MS, MS, true, null);

        BucketSeries series = recorder.snapshot();
        assertThat(series.totalCount()).isEqualTo(3);
        assertThat(series.clampedEarlyCount()).isEqualTo(1);
        assertThat(series.clampedLateCount()).isEqualTo(1);
        assertThat(series.buckets().get(0).count()).isEqualTo(1);
        assertThat(series.buckets().get(2).count()).isEqualTo(1);
    }

    @Test
    void concurrentRecordingLosesNothing() throws Exception {
        HistogramRecorder recorder = new HistogramRecorder(SECOND, 10);
        int threads = 16;
        int perThread = 5_000;
        CountDownLatch start = new CountDownLatch(1);

        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int t = 0; t < threads; t++) {
                pool.submit(() -> {
                    start.await();
                    for (int i = 0; i < perThread; i++) {
                        recorder.record((i % 10) * SECOND, MS, MS, true, 200);
                    }
                    return null;
                });
            }
            start.countDown();
            pool.shutdown();
            assertThat(pool.awaitTermination(60, TimeUnit.SECONDS)).isTrue();
        }

        BucketSeries series = recorder.snapshot();
        assertThat(series.totalCount()).isEqualTo((long) threads * perThread);
        assertThat(series.mergedLatency().getTotalCount()).isEqualTo((long) threads * perThread);
        assertThat(series.statusCounts().get(200)).isEqualTo((long) threads * perThread);
    }

    @Test
    void bucketsForRoundsUpAndNeverReturnsZero() {
        assertThat(HistogramRecorder.bucketsFor(10 * SECOND, SECOND)).isEqualTo(10);
        assertThat(HistogramRecorder.bucketsFor(10 * SECOND + 1, SECOND)).isEqualTo(11);
        assertThat(HistogramRecorder.bucketsFor(0, SECOND)).isEqualTo(1);
    }

    @Test
    void invalidConstructionFailsLoudly() {
        assertThatThrownBy(() -> new HistogramRecorder(0, 10))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("bucketWidthNanos");
        assertThatThrownBy(() -> new HistogramRecorder(SECOND, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("bucketCount");
    }

    @Test
    void snapshotIsFrozenAndLaterRecordingDoesNotChangeIt() {
        HistogramRecorder recorder = new HistogramRecorder(SECOND, 1);
        recorder.record(0, MS, MS, true, null);
        BucketSeries first = recorder.snapshot();

        recorder.record(0, MS, MS, true, null);

        assertThat(first.totalCount()).isEqualTo(1);
        assertThat(first.buckets().get(0).count()).isEqualTo(1);
        assertThat(recorder.snapshot().totalCount()).isEqualTo(2);
    }
}
