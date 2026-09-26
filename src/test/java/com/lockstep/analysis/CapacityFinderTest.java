package com.lockstep.analysis;

import static org.assertj.core.api.Assertions.assertThat;

import com.lockstep.analysis.CapacityFinder.Capacity;
import com.lockstep.stats.Bucket;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

final class CapacityFinderTest {
    private static final long MS = 1_000_000L;
    private static final long SECOND = 1_000_000_000L;

    private static Bucket bucket(int index, long p99Nanos) {
        return new Bucket(index, index * SECOND, (index + 1) * SECOND,
                100, 0, p99Nanos / 2, p99Nanos / 2, p99Nanos - MS, p99Nanos, p99Nanos, p99Nanos);
    }

    private static List<Bucket> timeline(int length, long flatNanos, int strainFrom, long strainNanos) {
        List<Bucket> buckets = new ArrayList<>();
        for (int i = 0; i < length; i++) {
            buckets.add(bucket(i, strainFrom >= 0 && i >= strainFrom ? strainNanos : flatNanos));
        }
        return buckets;
    }

    @Test
    void aRunThatWasSaturatedThroughoutIsNotReportedAsHavingNoStrain() {
        List<Bucket> flatAndTerrible = timeline(20, 2_700 * MS, -1, 0);

        CapacityFinder.Capacity shed =
                CapacityFinder.find(flatAndTerrible, 10, 0, SECOND, 0, 489.0 / 1200);

        assertThat(shed.overCapacityThroughout())
                .withFailMessage("a run that delivered 41%% of its load was over capacity, and "
                        + "saying 'no strain' about it is the opposite of the truth")
                .isTrue();
        assertThat(shed.strained()).isFalse();

        assertThat(shed.suggestedNextConcurrency()).isLessThan(10);
    }

    @Test
    void theSameFlatTimelineWithNothingShedStillReadsAsNoStrain() {
        List<Bucket> flatAndTerrible = timeline(20, 2_700 * MS, -1, 0);

        CapacityFinder.Capacity delivered =
                CapacityFinder.find(flatAndTerrible, 10, 0, SECOND, 0, 1.0);

        assertThat(delivered.overCapacityThroughout()).isFalse();
        assertThat(delivered.strained()).isFalse();
        assertThat(delivered.suggestedNextConcurrency()).isGreaterThan(10);
    }

    @Test
    void aFewSheddingsAtTheDrainAreNotACapacityVerdict() {
        List<Bucket> healthy = timeline(20, 10 * MS, -1, 0);

        CapacityFinder.Capacity capacity = CapacityFinder.find(healthy, 10, 0, SECOND, 0, 0.995);

        assertThat(capacity.overCapacityThroughout()).isFalse();
    }

    @Test
    void aRunWithARealStrainPointStillReportsThePointEvenIfItAlsoShed() {
        List<Bucket> turnsBad = timeline(20, 10 * MS, 12, 900 * MS);

        CapacityFinder.Capacity capacity = CapacityFinder.find(turnsBad, 10, 0, SECOND, 0, 0.6);

        assertThat(capacity.strained()).isTrue();
        assertThat(capacity.overCapacityThroughout()).isFalse();
        assertThat(capacity.strainBucketIndex()).isEqualTo(12);
    }

    @Test
    void findsTheFirstSustainedStrainAndEstimatesUsersThere() {
        List<Bucket> buckets = timeline(20, 20 * MS, 10, 400 * MS);

        Capacity capacity = CapacityFinder.find(buckets, 100, 20 * SECOND, SECOND);

        assertThat(capacity.usable()).isTrue();
        assertThat(capacity.strained()).isTrue();
        assertThat(capacity.strainBucketIndex()).isEqualTo(10);
        assertThat(capacity.strainUsers()).isEqualTo(55);
        assertThat(capacity.baselineP99Nanos()).isBetween(20 * MS, 400 * MS);
    }

    @Test
    void strainCoveringHalfTheRunIsStillDetected() {
        List<Bucket> buckets = timeline(20, 20 * MS, 10, 400 * MS);

        Capacity capacity = CapacityFinder.find(buckets, 100, 0, SECOND);

        assertThat(capacity.strained()).isTrue();
        assertThat(capacity.strainBucketIndex()).isEqualTo(10);
        assertThat(capacity.baselineP99Nanos()).isEqualTo(20 * MS);
    }

    @Test
    void theBaselineIsTakenFromTheHealthyPopulationNotTheWholeRun() {
        List<Bucket> buckets = timeline(20, 20 * MS, 10, 400 * MS);
        assertThat(CapacityFinder.baselineP99(buckets)).isEqualTo(20 * MS);
    }

    @Test
    void aSingleSlowBucketIsNotStrain() {
        List<Bucket> buckets = timeline(20, 20 * MS, -1, 0);
        buckets.set(7, bucket(7, 500 * MS));

        Capacity capacity = CapacityFinder.find(buckets, 50, 0, SECOND);

        assertThat(capacity.usable()).isTrue();
        assertThat(capacity.strained()).isFalse();
    }

    @Test
    void twoConsecutiveSlowBucketsAreStillNotEnough() {
        List<Bucket> buckets = timeline(20, 20 * MS, -1, 0);
        buckets.set(7, bucket(7, 500 * MS));
        buckets.set(8, bucket(8, 500 * MS));

        assertThat(CapacityFinder.find(buckets, 50, 0, SECOND).strained()).isFalse();
    }

    @Test
    void threeConsecutiveSlowBucketsAreStrain() {
        List<Bucket> buckets = timeline(20, 20 * MS, -1, 0);
        buckets.set(7, bucket(7, 500 * MS));
        buckets.set(8, bucket(8, 500 * MS));
        buckets.set(9, bucket(9, 500 * MS));

        Capacity capacity = CapacityFinder.find(buckets, 50, 0, SECOND);
        assertThat(capacity.strained()).isTrue();
        assertThat(capacity.strainBucketIndex()).isEqualTo(7);
    }

    @Test
    void aSteadyRunReportsNoStrainRatherThanInventingOne() {
        Capacity capacity = CapacityFinder.find(timeline(30, 25 * MS, -1, 0), 80, 10 * SECOND, SECOND);

        assertThat(capacity.usable()).isTrue();
        assertThat(capacity.strained()).isFalse();
        assertThat(capacity.usersAtEnd()).isEqualTo(80);

        assertThat(capacity.suggestedNextConcurrency()).isEqualTo(160);
    }

    @Test
    void aFastTargetIsNotCalledStrainedJustForDoublingATinyMedian() {
        List<Bucket> buckets = timeline(20, 2 * MS, 10, 5 * MS);

        assertThat(CapacityFinder.find(buckets, 50, 0, SECOND).strained()).isFalse();
    }

    @Test
    void aShortRunSaysItCannotTellRatherThanClaimingNoStrain() {
        Capacity capacity = CapacityFinder.find(timeline(9, 20 * MS, -1, 0), 50, 0, SECOND);

        assertThat(capacity.usable()).isFalse();
        assertThat(capacity.strained()).isFalse();
    }

    @Test
    void emptyBucketsDoNotCountTowardsTheRunLength() {
        List<Bucket> buckets = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            buckets.add(bucket(i, 20 * MS));
        }
        for (int i = 5; i < 20; i++) {
            buckets.add(Bucket.empty(i, SECOND));
        }

        assertThat(CapacityFinder.find(buckets, 50, 0, SECOND).usable()).isFalse();
    }

    @Test
    void userEstimateGrowsThroughTheRampThenHolds() {
        assertThat(CapacityFinder.usersAt(0, 100, 10 * SECOND, SECOND)).isEqualTo(10);
        assertThat(CapacityFinder.usersAt(4 * SECOND, 100, 10 * SECOND, SECOND)).isEqualTo(50);
        assertThat(CapacityFinder.usersAt(9 * SECOND, 100, 10 * SECOND, SECOND)).isEqualTo(100);
        assertThat(CapacityFinder.usersAt(30 * SECOND, 100, 10 * SECOND, SECOND)).isEqualTo(100);

        assertThat(CapacityFinder.usersAt(0, 40, 0, SECOND)).isEqualTo(40);
    }

    @Test
    void strainDuringARampSuggestsRetestingJustAboveThatLevel() {
        List<Bucket> buckets = timeline(20, 20 * MS, 10, 400 * MS);
        Capacity capacity = CapacityFinder.find(buckets, 100, 20 * SECOND, SECOND);

        assertThat(capacity.suggestedNextConcurrency()).isGreaterThan(capacity.strainUsers());
        assertThat(capacity.suggestedNextConcurrency()).isLessThan(capacity.strainUsers() * 2);
    }

    @Test
    void aRunWithNoConcurrencyOrNoBucketsIsNotUsable() {
        assertThat(CapacityFinder.find(List.of(), 50, 0, SECOND).usable()).isFalse();
        assertThat(CapacityFinder.find(timeline(20, 20 * MS, -1, 0), 0, 0, SECOND).usable()).isFalse();
        assertThat(CapacityFinder.find(null, 50, 0, SECOND).usable()).isFalse();
    }
}
