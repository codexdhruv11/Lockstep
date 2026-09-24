package com.lockstep.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

final class DurationsTest {
    @Test
    void parsesSecondsMinutesHoursAndCompoundForms() {
        assertThat(Durations.parseToNanos("10s")).isEqualTo(10_000_000_000L);
        assertThat(Durations.parseToNanos("1m30s")).isEqualTo(90_000_000_000L);
        assertThat(Durations.parseToNanos("2h30m")).isEqualTo(9_000_000_000_000L);
        assertThat(Durations.parseToNanos("500ms")).isEqualTo(500_000_000L);
        assertThat(Durations.parseToNanos("2us")).isEqualTo(2_000L);
        assertThat(Durations.parseToNanos("1ns")).isEqualTo(1L);
        assertThat(Durations.parseToNanos("0.5s")).isEqualTo(500_000_000L);
        assertThat(Durations.parseToNanos("1.5s")).isEqualTo(1_500_000_000L);
        assertThat(Durations.parseToNanos("0s")).isZero();
    }

    @Test
    void bareIntegerMeansNanoseconds() {
        assertThat(Durations.parseToNanos("1500")).isEqualTo(1500L);
    }

    @Test
    void rejectsGarbage() {
        assertThatThrownBy(() -> Durations.parseToNanos("10x")).isInstanceOf(ConfigDurationException.class);
        assertThatThrownBy(() -> Durations.parseToNanos("s")).isInstanceOf(ConfigDurationException.class);
        assertThatThrownBy(() -> Durations.parseToNanos("1m30x")).isInstanceOf(ConfigDurationException.class);
        assertThatThrownBy(() -> Durations.parseToNanos("")).isInstanceOf(ConfigDurationException.class);
        assertThatThrownBy(() -> Durations.parseToNanos("   ")).isInstanceOf(ConfigDurationException.class);
        assertThatThrownBy(() -> Durations.parseToNanos("1..5s")).isInstanceOf(ConfigDurationException.class);
        assertThatThrownBy(() -> Durations.parseToNanos("1m s")).isInstanceOf(ConfigDurationException.class);
        assertThatThrownBy(() -> Durations.parseToNanos("-")).isInstanceOf(ConfigDurationException.class);
    }

    @Test
    void signedDurationsParseLikeGoTimeParseDuration() {
        assertThat(Durations.parseToNanos("-5s")).isEqualTo(-5_000_000_000L);
        assertThat(Durations.parseToNanos("+5s")).isEqualTo(5_000_000_000L);
        assertThat(Durations.parseToNanos("-1m30s")).isEqualTo(-90_000_000_000L);
    }

    @Test
    void formatsToLargestExactUnit() {
        assertThat(Durations.formatNanos(0L)).isEqualTo("0s");
        assertThat(Durations.formatNanos(10_000_000_000L)).isEqualTo("10s");
        assertThat(Durations.formatNanos(90_000_000_000L)).isEqualTo("90s");
        assertThat(Durations.formatNanos(500_000_000L)).isEqualTo("500ms");
        assertThat(Durations.formatNanos(2_000L)).isEqualTo("2us");
        assertThat(Durations.formatNanos(1L)).isEqualTo("1ns");
        assertThat(Durations.formatNanos(3_600_000_000_000L)).isEqualTo("1h");
        assertThat(Durations.formatNanos(-500_000_000L)).isEqualTo("-500ms");
    }

    @Test
    void parseAndFormatRoundTrip() {
        for (long nanos : new long[] {1L, 999L, 1_000L, 1_500_000_000L, 90_000_000_000L, 3_600_000_000_001L}) {
            assertThat(Durations.parseToNanos(Durations.formatNanos(nanos))).as("nanos %d", nanos).isEqualTo(nanos);
        }
    }

    @Test
    void latencyFormattingDoesNotStrandAValueInTheWrongUnit() {
        assertThat(com.lockstep.util.Numbers.latency(999_600L)).isEqualTo("1ms");
        assertThat(com.lockstep.util.Numbers.latency(999_600_000L)).isEqualTo("1s");
        assertThat(com.lockstep.util.Numbers.latency(1_000_000L)).isEqualTo("1ms");
        assertThat(com.lockstep.util.Numbers.latency(45_000_000L)).isEqualTo("45ms");
        assertThat(com.lockstep.util.Numbers.latency(500L)).isEqualTo("500ns");
    }

    @Test
    void aNegativeLatencyKeepsItsUnitInsteadOfFallingBackToNanoseconds() {
        assertThat(com.lockstep.util.Numbers.latency(-5_000_000L)).isEqualTo("-5ms");
    }
}
