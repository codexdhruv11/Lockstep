package com.lockstep.core;

import static org.assertj.core.api.Assertions.assertThat;

import com.lockstep.stats.RunnerSummary;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;
import org.junit.jupiter.api.Test;

final class PacedLoopTest {
    private static final long MS = 1_000_000L;
    private static final long SECOND = 1_000_000_000L;

    private static RunContext context(long durationNanos, long rampNanos, int concurrency) {
        return RunContext.startingNow(durationNanos, 200 * MS, rampNanos, concurrency);
    }

    @Test
    void firesRoughlyTheConfiguredNumberOfOperations() {
        LongAdder executed = new LongAdder();
        PacedLoop.LoopResult result = PacedLoop.run(context(SECOND, 0, 20), 100, scheduledOffset -> {
            executed.increment();
            return Operation.Outcome.OK;
        });

        assertThat(result.scheduledCount()).isBetween(95L, 105L);
        assertThat(result.executedCount()).isBetween(90L, 105L);
        assertThat(executed.sum()).isEqualTo(result.executedCount());
        assertThat(result.shedCount()).isZero();
    }

    @Test
    void aSlowTargetDoesNotSlowTheArrivalRate() {
        PacedLoop.LoopResult result = PacedLoop.run(context(SECOND, 0, 200), 100, scheduledOffset -> {
            Thread.sleep(200);
            return Operation.Outcome.OK;
        });

        assertThat(result.scheduledCount()).isBetween(95L, 105L);
        assertThat(result.executedCount()).isBetween(90L, 105L);

        assertThat(result.executedCount()).isGreaterThan(50);
    }

    @Test
    void saturationIsShedAndCountedRatherThanQueuedOrIgnored() {
        PacedLoop.LoopResult result = PacedLoop.run(context(SECOND, 0, 2), 50, scheduledOffset -> {
            Thread.sleep(200);
            return Operation.Outcome.OK;
        });

        assertThat(result.shedCount()).isGreaterThan(10);
        assertThat(result.executedCount()).isLessThan(30);
        assertThat(result.executedCount() + result.shedCount()).isEqualTo(result.scheduledCount());
        assertThat(result.fellShort()).isTrue();
    }

    @Test
    void latencyIncludesQueueDelayAndServiceTimeDoesNot() {
        PacedLoop.LoopResult result = PacedLoop.run(context(SECOND, 0, 1), 40, scheduledOffset -> {
            Thread.sleep(100);
            return Operation.Outcome.OK;
        });

        RunnerSummary summary = result.series().summarize("test", SECOND);
        assertThat(summary.serviceP99Nanos()).isBetween(95 * MS, 140 * MS);
        assertThat(summary.p99Nanos()).isGreaterThan(summary.serviceP99Nanos() * 2);
        assertThat(summary.queueDelayP99Nanos()).isGreaterThan(100 * MS);
    }

    @Test
    void queueDepthIsBoundedSoOverloadShedsInsteadOfBacklogging() {
        PacedLoop.LoopResult result = PacedLoop.run(context(SECOND, 0, 1), 100, scheduledOffset -> {
            Thread.sleep(100);
            return Operation.Outcome.OK;
        });

        assertThat(result.shedCount()).isGreaterThan(50);
        assertThat(result.executedCount() + result.shedCount()).isEqualTo(result.scheduledCount());

        long worstCase = (long) PacedLoop.QUEUE_DEPTH_MULTIPLIER * 100 * MS * 2;
        assertThat(result.series().summarize("test", SECOND).maxNanos()).isLessThan(worstCase);
    }

    @Test
    void failuresAndThrownExceptionsAreRecordedNotSwallowed() {
        AtomicInteger calls = new AtomicInteger();
        PacedLoop.LoopResult result = PacedLoop.run(context(500 * MS, 0, 10), 40, scheduledOffset -> {
            int n = calls.incrementAndGet();
            if (n % 3 == 0) {
                throw new IllegalStateException("boom");
            }
            if (n % 3 == 1) {
                return Operation.Outcome.failed(500, "server said no");
            }
            return Operation.Outcome.ok(200);
        });

        var series = result.series();
        assertThat(series.totalCount()).isEqualTo(result.executedCount());

        assertThat(series.errorCount()).isGreaterThan(series.totalCount() / 2);
        assertThat(series.statusCounts()).containsKeys(200, 500);
    }

    @Test
    void operationsLandInTheBucketOfTheirScheduledInstant() {
        PacedLoop.LoopResult result = PacedLoop.run(context(SECOND, 0, 20), 50,
                scheduledOffset -> Operation.Outcome.OK);

        var buckets = result.series().buckets();
        assertThat(buckets).hasSize(5);
        assertThat(result.series().nonEmptyBuckets()).hasSizeGreaterThanOrEqualTo(4);

        assertThat(buckets).allSatisfy(bucket -> assertThat(bucket.count()).isLessThan(20));
    }

    @Test
    void rampIssuesFewerOperationsEarlyThanLate() {
        PacedLoop.LoopResult result = PacedLoop.run(context(2 * SECOND, SECOND, 50), 100,
                scheduledOffset -> Operation.Outcome.OK);

        var buckets = result.series().buckets();
        long firstHalf = buckets.subList(0, 5).stream().mapToLong(b -> b.count()).sum();
        long secondHalf = buckets.subList(5, 10).stream().mapToLong(b -> b.count()).sum();

        assertThat(secondHalf).isGreaterThan(firstHalf);
        assertThat(buckets.get(0).count()).isLessThan(buckets.get(9).count());
    }

    @Test
    void inFlightWorkIsDrainedBeforeTheResultIsReturned() {
        LongAdder completed = new LongAdder();
        PacedLoop.LoopResult result = PacedLoop.run(context(300 * MS, 0, 50), 20, scheduledOffset -> {
            Thread.sleep(200);
            completed.increment();
            return Operation.Outcome.OK;
        });

        assertThat(result.drainedCleanly()).isTrue();

        assertThat(result.executedCount()).isEqualTo(completed.sum());
        assertThat(result.executedCount()).isGreaterThan(0);
    }

    @Test
    void routineSchedulerOvershootIsNotReportedAsFallingShort() {
        PacedLoop.LoopResult result = PacedLoop.run(context(SECOND, 0, 20), 50,
                scheduledOffset -> Operation.Outcome.OK);

        assertThat(result.executedCount()).isEqualTo(result.scheduledCount());

        assertThat(result.lateFireCount()).isLessThanOrEqualTo(1);
        assertThat(result.shedCount()).isZero();
    }

    @Test
    void latenessToleranceScalesWithTheArrivalInterval() {
        assertThat(PacedLoop.latenessToleranceNanos(50)).isEqualTo(2 * MS);
        assertThat(PacedLoop.latenessToleranceNanos(5000)).isEqualTo(MS);
        assertThat(PacedLoop.latenessToleranceNanos(1)).isEqualTo(100 * MS);
    }

    @Test
    void expectedHitsIsReportedSoShortfallIsVisible() {
        PacedLoop.LoopResult result = PacedLoop.run(context(SECOND, 0, 1), 200, scheduledOffset -> {
            Thread.sleep(50);
            return Operation.Outcome.OK;
        });

        assertThat(result.expectedHits()).isEqualTo(200);
        assertThat(result.executedCount()).isLessThan(result.expectedHits());
        assertThat(result.shedCount()).isGreaterThan(0);
        assertThat(result.fellShort()).isTrue();
    }
}
