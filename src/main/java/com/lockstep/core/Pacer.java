package com.lockstep.core;

public final class Pacer {
    private static final double NANOS_PER_SECOND = 1_000_000_000.0;

    private final int ratePerSecond;
    private final long rampNanos;

    public Pacer(int ratePerSecond, long rampNanos) {
        if (ratePerSecond <= 0) {
            throw new IllegalArgumentException("ratePerSecond must be positive, got " + ratePerSecond);
        }
        if (rampNanos < 0) {
            throw new IllegalArgumentException("rampNanos must not be negative, got " + rampNanos);
        }
        this.ratePerSecond = ratePerSecond;
        this.rampNanos = rampNanos;
    }

    public long scheduledOffsetNanos(long hitNumber) {
        if (hitNumber < 1) {
            throw new IllegalArgumentException("hitNumber is 1-based, got " + hitNumber);
        }
        if (rampNanos <= 0) {
            return (long) ((hitNumber * NANOS_PER_SECOND) / ratePerSecond);
        }
        double rampSeconds = rampNanos / NANOS_PER_SECOND;
        double hitsDuringRamp = ratePerSecond * rampSeconds / 2.0;
        double seconds;
        if (hitNumber <= hitsDuringRamp) {
            seconds = Math.sqrt(2.0 * rampSeconds * hitNumber / ratePerSecond);
        } else {
            seconds = rampSeconds + (hitNumber - hitsDuringRamp) / ratePerSecond;
        }
        return (long) (seconds * NANOS_PER_SECOND);
    }

    public int rateAt(long elapsedNanos) {
        if (rampNanos > 0 && elapsedNanos < rampNanos) {
            double scaled = (double) ratePerSecond * elapsedNanos / rampNanos;
            return Math.max(1, (int) scaled);
        }
        return ratePerSecond;
    }

    public long expectedHits(long durationNanos) {
        if (durationNanos <= 0) {
            return 0;
        }
        double seconds = durationNanos / NANOS_PER_SECOND;
        double rampSeconds = rampNanos / NANOS_PER_SECOND;
        if (rampNanos <= 0) {
            return (long) (seconds * ratePerSecond);
        }
        if (seconds <= rampSeconds) {
            return (long) (ratePerSecond * seconds * seconds / (2 * rampSeconds));
        }
        double duringRamp = ratePerSecond * rampSeconds / 2.0;
        return (long) (duringRamp + (seconds - rampSeconds) * ratePerSecond);
    }

    public int ratePerSecond() {
        return ratePerSecond;
    }

    public long rampNanos() {
        return rampNanos;
    }
}
