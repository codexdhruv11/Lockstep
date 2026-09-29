package com.lockstep.core;

import java.util.random.RandomGenerator;

public final class Pacer {
    private static final double NANOS_PER_SECOND = 1_000_000_000.0;

    private static final double MIN_UNIFORM = 1e-12;

    private final int ratePerSecond;
    private final long rampNanos;
    private final Arrivals arrivals;
    private final RandomGenerator random;

    private long lastHit;
    private long cumulativeNanos;

    public Pacer(int ratePerSecond, long rampNanos) {
        this(ratePerSecond, rampNanos, Arrivals.CONSTANT, 0L);
    }

    public Pacer(int ratePerSecond, long rampNanos, Arrivals arrivals, long seed) {
        if (ratePerSecond <= 0) {
            throw new IllegalArgumentException("ratePerSecond must be positive, got " + ratePerSecond);
        }
        if (rampNanos < 0) {
            throw new IllegalArgumentException("rampNanos must not be negative, got " + rampNanos);
        }
        this.ratePerSecond = ratePerSecond;
        this.rampNanos = rampNanos;
        this.arrivals = arrivals == null ? Arrivals.CONSTANT : arrivals;
        this.random = seed == 0
                ? RandomGenerator.getDefault()
                : new java.util.Random(seed);
    }

    public long scheduledOffsetNanos(long hitNumber) {
        if (hitNumber < 1) {
            throw new IllegalArgumentException("hitNumber is 1-based, got " + hitNumber);
        }
        if (arrivals == Arrivals.CONSTANT) {
            return constantOffsetNanos(hitNumber);
        }
        return poissonOffsetNanos(hitNumber);
    }

    private long constantOffsetNanos(long hitNumber) {
        long index = hitNumber - 1;
        if (rampNanos <= 0) {
            return (long) ((index * NANOS_PER_SECOND) / ratePerSecond);
        }
        double rampSeconds = rampNanos / NANOS_PER_SECOND;
        double hitsDuringRamp = ratePerSecond * rampSeconds / 2.0;
        double seconds;
        if (index <= hitsDuringRamp) {
            seconds = Math.sqrt(2.0 * rampSeconds * index / ratePerSecond);
        } else {
            seconds = rampSeconds + (index - hitsDuringRamp) / ratePerSecond;
        }
        return (long) (seconds * NANOS_PER_SECOND);
    }

    private long poissonOffsetNanos(long hitNumber) {
        if (hitNumber == 1) {
            lastHit = 1;
            cumulativeNanos = 0;
            return 0;
        }
        if (hitNumber != lastHit + 1) {
            throw new IllegalStateException(
                    "poisson arrivals are generated sequentially: expected hit " + (lastHit + 1)
                            + " but got " + hitNumber
                            + " (each gap is drawn once and cannot be recomputed on demand)");
        }
        long meanGapNanos = constantOffsetNanos(hitNumber) - constantOffsetNanos(hitNumber - 1);
        cumulativeNanos += exponentialNanos(meanGapNanos);
        lastHit = hitNumber;
        return cumulativeNanos;
    }

    private long exponentialNanos(long meanNanos) {
        if (meanNanos <= 0) {
            return 0;
        }
        double u = random.nextDouble();
        if (u < MIN_UNIFORM) {
            u = MIN_UNIFORM;
        }
        double gap = -Math.log(u) * meanNanos;
        if (gap > Long.MAX_VALUE / 2.0) {
            return Long.MAX_VALUE / 2;
        }
        return (long) gap;
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

    public Arrivals arrivals() {
        return arrivals;
    }
}
