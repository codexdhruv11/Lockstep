package com.lockstep.core;

import java.time.Instant;

public record RunContext(
        long startNanoTime,
        Instant startWallClock,
        long durationNanos,
        long bucketWidthNanos,
        long rampNanos,
        int concurrency) {
    public static final long DEFAULT_BUCKET_WIDTH_NANOS = 1_000_000_000L;

    public static final int DEFAULT_CONCURRENCY = 10;

    public RunContext {
        if (durationNanos <= 0) {
            throw new IllegalArgumentException("durationNanos must be positive, got " + durationNanos);
        }
        if (bucketWidthNanos <= 0) {
            bucketWidthNanos = DEFAULT_BUCKET_WIDTH_NANOS;
        }
        if (concurrency <= 0) {
            concurrency = DEFAULT_CONCURRENCY;
        }
        if (rampNanos < 0) {
            throw new IllegalArgumentException("rampNanos must not be negative, got " + rampNanos);
        }
    }

    public static final long SETUP_GRACE_NANOS = 250_000_000L;

    public static RunContext startingNow(long durationNanos, long bucketWidthNanos,
            long rampNanos, int concurrency) {
        return new RunContext(System.nanoTime(), Instant.now(),
                durationNanos, bucketWidthNanos, rampNanos, concurrency);
    }

    public static RunContext startingAfterSetup(long durationNanos, long bucketWidthNanos,
            long rampNanos, int concurrency) {
        return new RunContext(System.nanoTime() + SETUP_GRACE_NANOS,
                Instant.now().plusNanos(SETUP_GRACE_NANOS),
                durationNanos, bucketWidthNanos, rampNanos, concurrency);
    }

    public long elapsedNanos() {
        return System.nanoTime() - startNanoTime;
    }

    public long deadlineFor(long offsetNanos) {
        return startNanoTime + offsetNanos;
    }

    public boolean isOver() {
        return elapsedNanos() >= durationNanos;
    }
}
