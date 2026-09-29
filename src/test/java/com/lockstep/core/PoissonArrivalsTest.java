package com.lockstep.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

final class PoissonArrivalsTest {

    private static final long SECOND = 1_000_000_000L;

    private static List<Long> gaps(Pacer pacer, int hits) {
        List<Long> gaps = new ArrayList<>();
        long previous = pacer.scheduledOffsetNanos(1);
        for (int hit = 2; hit <= hits; hit++) {
            long offset = pacer.scheduledOffsetNanos(hit);
            gaps.add(offset - previous);
            previous = offset;
        }
        return gaps;
    }

    @Test
    void constantArrivalsAreEvenlySpacedAndPoissonAreNot() {
        List<Long> constant = gaps(new Pacer(100, 0, Arrivals.CONSTANT, 0), 500);
        assertThat(constant).allSatisfy(gap -> assertThat(gap).isEqualTo(10_000_000L));

        List<Long> poisson = gaps(new Pacer(100, 0, Arrivals.POISSON, 42), 500);
        assertThat(poisson.stream().distinct().count())
                .withFailMessage("poisson gaps must vary; identical gaps mean the draw is not happening")
                .isGreaterThan(400);
    }

    @Test
    void poissonPreservesTheMeanRate() {
        int hits = 20_000;
        Pacer pacer = new Pacer(1_000, 0, Arrivals.POISSON, 7);
        long last = 0;
        for (int hit = 1; hit <= hits; hit++) {
            last = pacer.scheduledOffsetNanos(hit);
        }
        double seconds = last / 1_000_000_000.0;
        double observedRate = hits / seconds;

        assertThat(observedRate)
                .withFailMessage("poisson must keep the configured mean rate, got %.1f/s for 1000/s",
                        observedRate)
                .isBetween(950.0, 1050.0);
    }

    @Test
    void poissonGapsAreExponentiallyDistributed() {
        List<Long> gaps = gaps(new Pacer(1_000, 0, Arrivals.POISSON, 11), 20_000);

        double mean = gaps.stream().mapToLong(Long::longValue).average().orElseThrow();
        double variance = gaps.stream()
                .mapToDouble(g -> (g - mean) * (g - mean))
                .average().orElseThrow();
        double stdDev = Math.sqrt(variance);

        assertThat(stdDev / mean)
                .withFailMessage("an exponential distribution has stddev == mean (CV of 1); got %.3f",
                        stdDev / mean)
                .isBetween(0.9, 1.1);
    }

    @Test
    void poissonProducesBurstsThatConstantPacingNeverWill() {
        List<Long> gaps = gaps(new Pacer(100, 0, Arrivals.POISSON, 3), 5_000);
        long meanGap = 10_000_000L;

        long veryShort = gaps.stream().filter(g -> g < meanGap / 10).count();
        long veryLong = gaps.stream().filter(g -> g > meanGap * 3).count();

        assertThat(veryShort)
                .withFailMessage("poisson must produce near-simultaneous arrivals; none found")
                .isGreaterThan(100);
        assertThat(veryLong)
                .withFailMessage("poisson must produce long idle gaps; none found")
                .isGreaterThan(100);
    }

    @Test
    void aSeedMakesTheArrivalScheduleReproducible() {
        List<Long> first = gaps(new Pacer(500, 0, Arrivals.POISSON, 99), 1_000);
        List<Long> second = gaps(new Pacer(500, 0, Arrivals.POISSON, 99), 1_000);

        assertThat(first)
                .withFailMessage("the same seed must produce the same schedule, or a run cannot be reproduced")
                .isEqualTo(second);

        List<Long> different = gaps(new Pacer(500, 0, Arrivals.POISSON, 100), 1_000);
        assertThat(different).isNotEqualTo(first);
    }

    @Test
    void poissonStillRampsOnAverage() {
        Pacer pacer = new Pacer(1_000, 4 * SECOND, Arrivals.POISSON, 5);

        long inRamp = 0;
        long hit = 1;
        long offset = pacer.scheduledOffsetNanos(1);
        while (offset < 2 * SECOND && hit < 100_000) {
            hit++;
            offset = pacer.scheduledOffsetNanos(hit);
            inRamp++;
        }

        long expectedAtHalfRamp = (long) (1_000 * 2.0 * 2.0 / (2 * 4.0));
        assertThat(inRamp)
                .withFailMessage("poisson must still follow the ramp: expected about %d hits in the "
                        + "first 2s of a 4s ramp, got %d", expectedAtHalfRamp, inRamp)
                .isBetween((long) (expectedAtHalfRamp * 0.7), (long) (expectedAtHalfRamp * 1.3));
    }

    @Test
    void poissonRefusesOutOfOrderAccessRatherThanInventingAGap() {
        Pacer pacer = new Pacer(100, 0, Arrivals.POISSON, 1);
        pacer.scheduledOffsetNanos(1);
        pacer.scheduledOffsetNanos(2);

        assertThatThrownBy(() -> pacer.scheduledOffsetNanos(99))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("sequentially");

        assertThatThrownBy(() -> new Pacer(100, 0, Arrivals.POISSON, 1).scheduledOffsetNanos(5))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void constantRemainsAPureFunctionOfTheHitNumber() {
        Pacer pacer = new Pacer(100, 0, Arrivals.CONSTANT, 0);

        assertThat(pacer.scheduledOffsetNanos(50)).isEqualTo(pacer.scheduledOffsetNanos(50));
        assertThat(pacer.scheduledOffsetNanos(1)).isZero();
        assertThat(pacer.scheduledOffsetNanos(500)).isEqualTo(4_990_000_000L);
        assertThat(pacer.scheduledOffsetNanos(2)).isEqualTo(10_000_000L);
    }

    @Test
    void expectedHitsIsUnchangedByTheArrivalModel() {
        Pacer constant = new Pacer(200, SECOND, Arrivals.CONSTANT, 0);
        Pacer poisson = new Pacer(200, SECOND, Arrivals.POISSON, 0);

        assertThat(poisson.expectedHits(10 * SECOND)).isEqualTo(constant.expectedHits(10 * SECOND));
    }

    @Test
    void parsingAcceptsTheDocumentedNamesAndRejectsTypos() {
        assertThat(Arrivals.parse(null)).isEqualTo(Arrivals.CONSTANT);
        assertThat(Arrivals.parse("")).isEqualTo(Arrivals.CONSTANT);
        assertThat(Arrivals.parse("constant")).isEqualTo(Arrivals.CONSTANT);
        assertThat(Arrivals.parse("POISSON")).isEqualTo(Arrivals.POISSON);
        assertThat(Arrivals.parse(" poisson ")).isEqualTo(Arrivals.POISSON);

        assertThatThrownBy(() -> Arrivals.parse("passion"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("passion");
    }
}
