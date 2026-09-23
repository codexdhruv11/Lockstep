package com.lockstep.report;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.lockstep.core.PacedLoop;
import com.lockstep.core.RunCoordinator;
import com.lockstep.stats.Bucket;
import com.lockstep.stats.HistogramRecorder;
import com.lockstep.stats.RunnerSummary;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
public record RunReport(
        String tool,
        int schemaVersion,
        String toolVersion,
        String startedAt,
        long durationNanos,
        long bucketWidthNanos,
        long rampNanos,
        int concurrency,
        double percentilePrecision,
        List<RunnerReport> runners) {
    public static final int SCHEMA_VERSION = 1;

    public RunReport {
        runners = List.copyOf(runners);
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record RunnerReport(
            String name,
            long count,
            long successCount,
            long errorCount,
            double achievedRatePerSecond,
            long scheduledCount,
            long expectedHits,
            long shedCount,
            long lateFireCount,
            long maxLatenessNanos,
            boolean drainedCleanly,
            long meanNanos,
            long p50Nanos,
            long p95Nanos,
            long p99Nanos,
            long maxNanos,
            long serviceP99Nanos,
            Map<String, Long> statusCounts,
            List<BucketReport> buckets) {
        public RunnerReport {
            statusCounts = statusCounts == null
                    ? Map.of()
                    : java.util.Collections.unmodifiableMap(new LinkedHashMap<>(statusCounts));
            buckets = buckets == null ? List.of() : List.copyOf(buckets);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record BucketReport(
            int index,
            long startOffsetNanos,
            long count,
            long errorCount,
            long p50Nanos,
            long p95Nanos,
            long p99Nanos,
            long maxNanos,
            long serviceP99Nanos) {}

    public static RunReport from(RunCoordinator.RunResult result, String toolVersion) {
        List<RunnerReport> runners = new ArrayList<>();
        result.byRunner().forEach((name, loop) -> runners.add(runnerReport(name, loop, result)));
        return new RunReport(
                "lockstep",
                SCHEMA_VERSION,
                toolVersion,
                result.context().startWallClock().toString(),
                result.context().durationNanos(),
                result.context().bucketWidthNanos(),
                result.context().rampNanos(),
                result.context().concurrency(),
                HistogramRecorder.PERCENTILE_PRECISION,
                runners);
    }

    private static RunnerReport runnerReport(String name, PacedLoop.LoopResult loop,
            RunCoordinator.RunResult result) {
        RunnerSummary summary = loop.series().summarize(name, result.context().durationNanos());
        Map<String, Long> statuses = new LinkedHashMap<>();
        summary.statusCounts().forEach((code, count) -> statuses.put(String.valueOf(code), count));

        List<BucketReport> buckets = new ArrayList<>();
        for (Bucket bucket : loop.series().buckets()) {
            buckets.add(new BucketReport(
                    bucket.index(), bucket.startOffsetNanos(), bucket.count(), bucket.errorCount(),
                    bucket.p50Nanos(), bucket.p95Nanos(), bucket.p99Nanos(), bucket.maxNanos(),
                    bucket.serviceP99Nanos()));
        }

        return new RunnerReport(
                name, summary.count(), summary.successCount(), summary.errorCount(),
                summary.achievedRatePerSecond(), loop.scheduledCount(), loop.expectedHits(),
                loop.shedCount(), loop.lateFireCount(), loop.maxLatenessNanos(), loop.drainedCleanly(),
                summary.meanNanos(), summary.p50Nanos(), summary.p95Nanos(), summary.p99Nanos(),
                summary.maxNanos(), summary.serviceP99Nanos(), statuses, buckets);
    }

    public RunnerReport runner(String name) {
        return runners.stream().filter(runner -> runner.name().equals(name)).findFirst().orElse(null);
    }
}
