package com.lockstep.stats;

public record Bucket(
        int index,
        long startOffsetNanos,
        long endOffsetNanos,
        long count,
        long errorCount,
        long meanNanos,
        long p50Nanos,
        long p95Nanos,
        long p99Nanos,
        long maxNanos,
        long serviceP99Nanos) {
    public long queueDelayP99Nanos() {
        return Math.max(0, p99Nanos - serviceP99Nanos);
    }

    public static Bucket empty(int index, long bucketWidthNanos) {
        long start = (long) index * bucketWidthNanos;
        return new Bucket(index, start, start + bucketWidthNanos, 0, 0, 0, 0, 0, 0, 0, 0);
    }
}
