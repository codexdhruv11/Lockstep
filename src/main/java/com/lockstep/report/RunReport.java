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
        List<RunnerReport> runners,
        List<SpikeReport> spikes,
        CapacityReport capacity,
        List<StepReport> steps,
        CorrelationContext correlation) {
    public static final int SCHEMA_VERSION = 1;

    public RunReport {
        runners = List.copyOf(runners);
        spikes = spikes == null ? List.of() : List.copyOf(spikes);
        steps = steps == null ? List.of() : List.copyOf(steps);
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record StepReport(
            String label,
            long count,
            long errorCount,
            long meanNanos,
            long p50Nanos,
            long p95Nanos,
            long p99Nanos,
            long maxNanos) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CorrelationContext(boolean appReferencePresent, boolean storageRunnersPresent) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CapacityReport(
            boolean usable,
            boolean strained,
            int strainBucketIndex,
            long strainOffsetNanos,
            int strainUsers,
            int usersAtEnd,
            long baselineP99Nanos,
            long strainLevelNanos,
            int suggestedNextConcurrency) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SpikeReport(
            int bucketIndex,
            long startOffsetNanos,
            String storageRunner,
            long storageP99Nanos,
            long appP99Nanos,
            boolean masked,
            String verdict) {}

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
            long abandonedCount,
            long lateFireCount,
            long maxLatenessNanos,
            boolean drainedCleanly,
            long clampedEarlyCount,
            long clampedLateCount,
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
        return from(result, toolVersion, null, null);
    }

    private static CorrelationContext correlationContext(RunCoordinator.RunResult result,
            com.lockstep.analysis.SpikeCorrelator.CorrelationResult correlation) {
        if (correlation == null) {
            return null;
        }
        boolean storage = result.byRunner().containsKey("db") || result.byRunner().containsKey("redis");
        return new CorrelationContext(correlation.appReferencePresent(), storage);
    }

    private static List<StepReport> stepReports(RunCoordinator.RunResult result) {
        if (result.scenarioRunner() == null) {
            return List.of();
        }
        List<StepReport> steps = new ArrayList<>();
        result.scenarioRunner().stepSeries().forEach((label, series) -> {
            var summary = series.summarize(label, result.context().durationNanos());
            steps.add(new StepReport(label, summary.count(), summary.errorCount(),
                    summary.meanNanos(), summary.p50Nanos(), summary.p95Nanos(),
                    summary.p99Nanos(), summary.maxNanos()));
        });
        return steps;
    }

    public static RunReport from(RunCoordinator.RunResult result, String toolVersion,
            com.lockstep.analysis.SpikeCorrelator.CorrelationResult correlation) {
        return from(result, toolVersion, correlation, null);
    }

    public static RunReport from(RunCoordinator.RunResult result, String toolVersion,
            com.lockstep.analysis.SpikeCorrelator.CorrelationResult correlation,
            com.lockstep.analysis.CapacityFinder.Capacity capacity) {
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
                runners,
                spikeReports(correlation),
                capacityReport(capacity),
                stepReports(result),
                correlationContext(result, correlation));
    }

    private static CapacityReport capacityReport(
            com.lockstep.analysis.CapacityFinder.Capacity capacity) {
        if (capacity == null) {
            return null;
        }
        return new CapacityReport(capacity.usable(), capacity.strained(),
                capacity.strainBucketIndex(), capacity.strainOffsetNanos(), capacity.strainUsers(),
                capacity.usersAtEnd(), capacity.baselineP99Nanos(), capacity.strainLevelNanos(),
                capacity.suggestedNextConcurrency());
    }

    private static List<SpikeReport> spikeReports(
            com.lockstep.analysis.SpikeCorrelator.CorrelationResult correlation) {
        if (correlation == null) {
            return List.of();
        }
        List<SpikeReport> reports = new ArrayList<>();
        for (var spike : correlation.spikes()) {
            reports.add(new SpikeReport(spike.bucketIndex(), spike.startOffsetNanos(),
                    spike.storageRunner(), spike.storageP99Nanos(), spike.appP99Nanos(),
                    spike.masked(), spike.verdict().name()));
        }
        return reports;
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
                loop.shedCount(), loop.abandonedCount(), loop.lateFireCount(),
                loop.maxLatenessNanos(), loop.drainedCleanly(),
                summary.clampedEarlyCount(), summary.clampedLateCount(),
                summary.meanNanos(), summary.p50Nanos(), summary.p95Nanos(), summary.p99Nanos(),
                summary.maxNanos(), summary.serviceP99Nanos(), statuses, buckets);
    }

    public RunnerReport runner(String name) {
        return runners.stream().filter(runner -> runner.name().equals(name)).findFirst().orElse(null);
    }
}
