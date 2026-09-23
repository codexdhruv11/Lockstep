package com.lockstep.analysis;

import com.lockstep.core.PacedLoop;
import com.lockstep.core.RunCoordinator;
import com.lockstep.stats.Bucket;
import com.lockstep.stats.HistogramRecorder;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class SpikeCorrelator {
    private SpikeCorrelator() {}

    public enum Verdict {
        HTTP,

        DB,

        REDIS,

        EVEN
    }

    public record Thresholds(long httpNanos, long dbNanos, long redisNanos) {
        private static final long DEFAULT_NANOS = 100_000_000L;

        public static Thresholds defaults() {
            return new Thresholds(DEFAULT_NANOS, DEFAULT_NANOS, DEFAULT_NANOS);
        }

        public long forStorage(String runnerName) {
            return "redis".equals(runnerName) ? redisNanos : dbNanos;
        }
    }

    public record Spike(
            int bucketIndex,
            long startOffsetNanos,
            String storageRunner,
            long storageP99Nanos,
            long appP99Nanos,
            boolean masked,
            Verdict verdict) {}

    public record CorrelationResult(List<Spike> spikes, boolean appReferencePresent) {
        public CorrelationResult {
            spikes = List.copyOf(spikes);
        }

        public static CorrelationResult empty(boolean appReferencePresent) {
            return new CorrelationResult(List.of(), appReferencePresent);
        }

        public List<Spike> correlated() {
            return spikes.stream().filter(spike -> !spike.masked()).toList();
        }

        public List<Spike> masked() {
            return spikes.stream().filter(Spike::masked).toList();
        }
    }

    public static CorrelationResult correlate(RunCoordinator.RunResult result, Thresholds thresholds) {
        PacedLoop.LoopResult http = result.byRunner().get("http");
        Map<String, List<Bucket>> storage = new LinkedHashMap<>();
        result.byRunner().forEach((name, loop) -> {
            if (!"http".equals(name)) {
                storage.put(name, loop.series().buckets());
            }
        });
        if (http == null) {
            return CorrelationResult.empty(false);
        }
        return correlate(http.series().buckets(), storage, thresholds);
    }

    public static CorrelationResult correlate(List<Bucket> appBuckets,
            Map<String, List<Bucket>> storageByRunner, Thresholds thresholds) {
        if (appBuckets == null || appBuckets.isEmpty()) {
            return CorrelationResult.empty(false);
        }
        Map<Integer, Bucket> appByIndex = new LinkedHashMap<>();
        for (Bucket bucket : appBuckets) {
            if (bucket.count() > 0) {
                appByIndex.put(bucket.index(), bucket);
            }
        }

        List<Spike> spikes = new ArrayList<>();
        storageByRunner.forEach((runnerName, buckets) -> {
            long threshold = thresholds.forStorage(runnerName);
            for (Bucket storageBucket : buckets) {
                if (storageBucket.count() == 0 || storageBucket.p99Nanos() <= threshold) {
                    continue;
                }
                Bucket appBucket = appByIndex.get(storageBucket.index());
                if (appBucket == null) {
                    continue;
                }
                boolean masked = appBucket.p99Nanos() <= thresholds.httpNanos();
                spikes.add(new Spike(
                        storageBucket.index(),
                        storageBucket.startOffsetNanos(),
                        runnerName,
                        storageBucket.p99Nanos(),
                        appBucket.p99Nanos(),
                        masked,
                        verdictFor(runnerName, appBucket.p99Nanos(), storageBucket.p99Nanos())));
            }
        });

        spikes.sort(Comparator.comparingInt(Spike::bucketIndex));
        return new CorrelationResult(spikes, true);
    }

    static Verdict verdictFor(String storageRunner, long appP99Nanos, long storageP99Nanos) {
        long larger = Math.max(appP99Nanos, storageP99Nanos);
        long gap = Math.abs(appP99Nanos - storageP99Nanos);
        if (gap <= larger * HistogramRecorder.PERCENTILE_PRECISION) {
            return Verdict.EVEN;
        }
        if (appP99Nanos > storageP99Nanos) {
            return Verdict.HTTP;
        }
        return "redis".equals(storageRunner) ? Verdict.REDIS : Verdict.DB;
    }
}
