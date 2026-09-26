package com.lockstep.stats;

import java.util.Map;

public record RunnerSummary(
        String runnerName,
        long count,
        long successCount,
        long errorCount,
        double achievedRatePerSecond,
        long meanNanos,
        long minNanos,
        long p50Nanos,
        long p90Nanos,
        long p95Nanos,
        long p99Nanos,
        long maxNanos,
        long serviceP99Nanos,
        Map<Integer, Long> statusCounts,
        long clampedEarlyCount,
        long clampedLateCount) {
    public double successRate() {
        return count == 0 ? 0 : (double) successCount / count;
    }

    public long queueDelayP99Nanos() {
        return Math.max(0, p99Nanos - serviceP99Nanos);
    }
}
