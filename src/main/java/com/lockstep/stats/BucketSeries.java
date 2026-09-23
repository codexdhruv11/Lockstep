package com.lockstep.stats;

import java.util.List;
import java.util.Map;
import org.HdrHistogram.Histogram;

public record BucketSeries(
        long bucketWidthNanos,
        List<Bucket> buckets,
        Histogram mergedLatency,
        Histogram mergedServiceTime,
        long totalCount,
        long errorCount,
        long clampedEarlyCount,
        long clampedLateCount,
        Map<Integer, Long> statusCounts) {
    public BucketSeries {
        buckets = List.copyOf(buckets);
    }

    public List<Bucket> nonEmptyBuckets() {
        return buckets.stream().filter(bucket -> bucket.count() > 0).toList();
    }

    public RunnerSummary summarize(String runnerName, long runDurationNanos) {
        long successes = totalCount - errorCount;
        double seconds = runDurationNanos / 1_000_000_000.0;
        double achievedRate = seconds > 0 ? totalCount / seconds : 0;
        if (totalCount == 0) {
            return new RunnerSummary(runnerName, 0, 0, 0, achievedRate,
                    0, 0, 0, 0, 0, 0, statusCounts, clampedEarlyCount, clampedLateCount);
        }
        return new RunnerSummary(
                runnerName,
                totalCount,
                successes,
                errorCount,
                achievedRate,
                (long) mergedLatency.getMean(),
                mergedLatency.getValueAtPercentile(50),
                mergedLatency.getValueAtPercentile(95),
                mergedLatency.getValueAtPercentile(99),
                mergedLatency.getMaxValue(),
                mergedServiceTime.getValueAtPercentile(99),
                statusCounts,
                clampedEarlyCount,
                clampedLateCount);
    }
}
