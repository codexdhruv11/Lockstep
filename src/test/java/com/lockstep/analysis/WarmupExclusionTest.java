package com.lockstep.analysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.lockstep.core.RunContext;
import com.lockstep.stats.Bucket;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class WarmupExclusionTest {
    private static final long MS = 1_000_000L;
    private static final long SECOND = 1_000_000_000L;

    private static Bucket bucket(int index, long p99Nanos) {
        return new Bucket(index, index * SECOND, (index + 1) * SECOND, 100, 0,
                p99Nanos / 2, p99Nanos / 2, p99Nanos - MS, p99Nanos, p99Nanos, p99Nanos);
    }

    @Test
    void aSpikeInsideTheWarmupWindowIsNotReportedAsAFinding() {
        List<Bucket> app = List.of(bucket(0, 900 * MS), bucket(1, 15 * MS), bucket(2, 15 * MS),
                bucket(3, 15 * MS), bucket(4, 15 * MS));
        Map<String, List<Bucket>> db = Map.of("db", List.of(bucket(0, 800 * MS), bucket(1, 5 * MS),
                bucket(2, 5 * MS), bucket(3, 5 * MS), bucket(4, 600 * MS)));

        var withoutWarmup = SpikeCorrelator.correlate(app, db, SpikeCorrelator.Thresholds.defaults());
        var withWarmup = SpikeCorrelator.correlate(app, db, SpikeCorrelator.Thresholds.defaults(), 2 * SECOND);

        assertThat(withoutWarmup.spikes()).extracting(SpikeCorrelator.Spike::bucketIndex)
                .containsExactly(0, 4);
        assertThat(withWarmup.spikes()).extracting(SpikeCorrelator.Spike::bucketIndex)
                .containsExactly(4);
    }

    @Test
    void theCapacityBaselineIgnoresWarmupLatencies() {
        List<Bucket> buckets = new ArrayList<>();
        buckets.add(bucket(0, 900 * MS));
        buckets.add(bucket(1, 800 * MS));
        for (int i = 2; i < 14; i++) {
            buckets.add(bucket(i, 20 * MS));
        }
        for (int i = 14; i < 20; i++) {
            buckets.add(bucket(i, 400 * MS));
        }

        var withWarmup = CapacityFinder.find(buckets, 50, 0, SECOND, 2 * SECOND);

        assertThat(withWarmup.baselineP99Nanos()).isEqualTo(20 * MS);
        assertThat(withWarmup.strained()).isTrue();
        assertThat(withWarmup.strainBucketIndex()).isEqualTo(14);
    }

    @Test
    void warmupBucketsAreStillMeasuredAndStillPresentInTheTimeline() {
        List<Bucket> app = List.of(bucket(0, 900 * MS), bucket(1, 15 * MS));
        var result = SpikeCorrelator.correlate(app, Map.of("db", List.of(bucket(0, 800 * MS),
                bucket(1, 5 * MS))), SpikeCorrelator.Thresholds.defaults(), 2 * SECOND);

        assertThat(result.spikes()).isEmpty();
        assertThat(result.appReferencePresent()).isTrue();
        assertThat(app.get(0).p99Nanos()).isEqualTo(900 * MS);
    }

    @Test
    void aWarmupLongerThanTheRunIsRejected() {
        assertThatThrownBy(() -> RunContext.startingNow(2 * SECOND, SECOND, 0, 4, 5 * SECOND))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("shorter than the run");
    }

    @Test
    void noWarmupMeansNothingIsExcluded() {
        RunContext context = RunContext.startingNow(10 * SECOND, SECOND, 0, 4);
        assertThat(context.warmupNanos()).isZero();
        assertThat(context.isWarmup(0)).isFalse();
    }
}
