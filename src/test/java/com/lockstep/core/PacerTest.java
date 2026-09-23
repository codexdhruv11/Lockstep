package com.lockstep.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

final class PacerTest {
    private static final long SECOND = 1_000_000_000L;

    @Test
    void withoutRampTheSpacingIsFlat() {
        Pacer pacer = new Pacer(100, 0);
        assertThat(pacer.scheduledOffsetNanos(1)).isEqualTo(10_000_000L);
        assertThat(pacer.scheduledOffsetNanos(50)).isEqualTo(500_000_000L);
        assertThat(pacer.scheduledOffsetNanos(100)).isEqualTo(SECOND);
    }

    @Test
    void rampSchedulesFewerHitsEarlyAndReachesFullRateAtTheRampEnd() {
        Pacer pacer = new Pacer(100, 10 * SECOND);

        assertThat(pacer.scheduledOffsetNanos(500)).isBetween(9_900_000_000L, 10_100_000_000L);
        long afterRamp = pacer.scheduledOffsetNanos(600) - pacer.scheduledOffsetNanos(500);
        assertThat(afterRamp).isBetween(990_000_000L, 1_010_000_000L);

        long firstGap = pacer.scheduledOffsetNanos(2) - pacer.scheduledOffsetNanos(1);
        long lateGap = pacer.scheduledOffsetNanos(400) - pacer.scheduledOffsetNanos(399);
        assertThat(firstGap).isGreaterThan(lateGap * 5);
    }

    @Test
    void scheduleIsMonotonic() {
        Pacer pacer = new Pacer(250, 3 * SECOND);
        long previous = -1;
        for (long hit = 1; hit <= 5_000; hit++) {
            long offset = pacer.scheduledOffsetNanos(hit);
            assertThat(offset).isGreaterThanOrEqualTo(previous);
            previous = offset;
        }
    }

    @Test
    void expectedHitsMatchesTheRampIntegral() {
        Pacer ramped = new Pacer(100, 10 * SECOND);

        assertThat(ramped.expectedHits(10 * SECOND)).isEqualTo(500);
        assertThat(ramped.expectedHits(20 * SECOND)).isEqualTo(1500);
        assertThat(ramped.expectedHits(5 * SECOND)).isEqualTo(125);

        Pacer flat = new Pacer(100, 0);
        assertThat(flat.expectedHits(10 * SECOND)).isEqualTo(1000);
    }

    @Test
    void expectedHitsAgreesWithTheActualSchedule() {
        Pacer pacer = new Pacer(200, 4 * SECOND);
        long window = 10 * SECOND;
        long counted = 0;
        for (long hit = 1; pacer.scheduledOffsetNanos(hit) < window; hit++) {
            counted++;
        }
        assertThat(counted).isCloseTo(pacer.expectedHits(window),
                org.assertj.core.data.Offset.offset(2L));
    }

    @Test
    void rateAtGrowsLinearlyThroughTheRampThenHolds() {
        Pacer pacer = new Pacer(100, 10 * SECOND);
        assertThat(pacer.rateAt(0)).isEqualTo(1);
        assertThat(pacer.rateAt(5 * SECOND)).isEqualTo(50);
        assertThat(pacer.rateAt(10 * SECOND)).isEqualTo(100);
        assertThat(pacer.rateAt(60 * SECOND)).isEqualTo(100);
    }

    @Test
    void invalidArgumentsFailLoudly() {
        assertThatThrownBy(() -> new Pacer(0, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ratePerSecond");
        assertThatThrownBy(() -> new Pacer(10, -1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("rampNanos");
        assertThatThrownBy(() -> new Pacer(10, 0).scheduledOffsetNanos(0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("1-based");
    }
}
