package com.lockstep.analysis;

import com.lockstep.core.PacedLoop;
import com.lockstep.core.RunCoordinator;
import com.lockstep.stats.Bucket;
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

    public static final java.util.Set<String> STORAGE_RUNNERS = java.util.Set.of("db", "redis");

    public static CorrelationResult correlate(RunCoordinator.RunResult result, Thresholds thresholds) {
        return correlate(appTimelineOf(result), storageTimelinesOf(result), thresholds,
                result.context().warmupNanos());
    }

    public static List<Bucket> appTimelineOf(RunCoordinator.RunResult result) {
        PacedLoop.LoopResult http = result.byRunner().get("http");
        if (http != null) {
            return http.series().buckets();
        }
        return result.scenarioRunner() == null ? List.of() : result.scenarioRunner().appTimeline();
    }

    public static Map<String, List<Bucket>> storageTimelinesOf(RunCoordinator.RunResult result) {
        Map<String, List<Bucket>> storage = new LinkedHashMap<>();
        result.byRunner().forEach((name, loop) -> {
            if (STORAGE_RUNNERS.contains(name)) {
                storage.put(name, loop.series().buckets());
            }
        });
        return storage;
    }

    public static CorrelationResult correlate(List<Bucket> appBuckets,
            Map<String, List<Bucket>> storageByRunner, Thresholds thresholds) {
        return correlate(appBuckets, storageByRunner, thresholds, 0);
    }

    public static CorrelationResult correlate(List<Bucket> appBuckets,
            Map<String, List<Bucket>> storageByRunner, Thresholds thresholds, long warmupNanos) {
        if (appBuckets == null || appBuckets.isEmpty()) {
            return CorrelationResult.empty(false);
        }
        Map<Integer, Bucket> appByIndex = new LinkedHashMap<>();
        for (Bucket bucket : appBuckets) {
            if (bucket.count() > 0) {
                appByIndex.put(bucket.index(), bucket);
            }
        }

        long appBaseline = baselineOf(appBuckets, warmupNanos);

        List<Spike> spikes = new ArrayList<>();
        storageByRunner.forEach((runnerName, buckets) -> {
            long threshold = thresholds.forStorage(runnerName);
            long storageBaseline = baselineOf(buckets, warmupNanos);
            for (Bucket storageBucket : buckets) {
                if (storageBucket.count() == 0 || storageBucket.p99Nanos() <= threshold) {
                    continue;
                }
                if (warmupNanos > 0 && storageBucket.startOffsetNanos() < warmupNanos) {
                    continue;
                }
                Bucket appBucket = appByIndex.get(storageBucket.index());
                if (appBucket == null) {
                    continue;
                }
                Verdict verdict = verdictFor(runnerName, appBucket.p99Nanos(),
                        storageBucket.p99Nanos(), appBaseline, storageBaseline);

                boolean masked = appBucket.p99Nanos() <= thresholds.httpNanos() && verdict != Verdict.HTTP;
                spikes.add(new Spike(
                        storageBucket.index(),
                        storageBucket.startOffsetNanos(),
                        runnerName,
                        storageBucket.p99Nanos(),
                        appBucket.p99Nanos(),
                        masked,
                        verdict));
            }
        });

        spikes.sort(Comparator.comparingInt(Spike::bucketIndex));
        return new CorrelationResult(spikes, true);
    }

    /**
     * How much of its own time the app must add, as a multiple of the storage tier's, before the
     * app is named at all. Below this the storage tier accounts for the app's slowness.
     *
     * <p>The two figures are not symmetric: an endpoint that queries the database waits for the
     * database, so its latency contains the storage latency and is necessarily the larger of the
     * two. Measured against a fixture where the endpoint's only work was the slow query, the app
     * came out 20% above the storage probe - 304ms against 254ms - purely from the request
     * round trip and the handler. Treating "larger" as "to blame" named the app for a fault that
     * was entirely the database's.
     */
    private static final double OWN_TIME_RATIO = 1.25;

    /**
     * Above this the app is named outright: it has added at least as much again on top of
     * whatever the storage tier cost it.
     */
    private static final double DOMINANT_RATIO = 2.0;

    static Verdict verdictFor(String storageRunner, long appP99Nanos, long storageP99Nanos) {
        return verdictFor(storageRunner, appP99Nanos, storageP99Nanos, 0, 0);
    }

    /**
     * Names the tier responsible, comparing how far each rose above its own quiet-period
     * baseline rather than comparing the two latencies directly.
     *
     * <p>Baselines matter because a fixed cost in one tier otherwise swamps the comparison: an
     * endpoint that always takes 200ms of its own, hit by a database that jumps from 2ms to
     * 300ms, ends at 500ms against 300ms and looks like the app's fault. Measured as growth it is
     * 300ms against 298ms, which is the database and nothing else.
     *
     * <p>A baseline of zero means there was no quiet bucket to learn one from, and the comparison
     * falls back to the latencies themselves.
     */
    static Verdict verdictFor(String storageRunner, long appP99Nanos, long storageP99Nanos,
            long appBaselineNanos, long storageBaselineNanos) {
        long appGrowth = growthOver(appP99Nanos, appBaselineNanos);
        long storageGrowth = growthOver(storageP99Nanos, storageBaselineNanos);
        Verdict storageVerdict = "redis".equals(storageRunner) ? Verdict.REDIS : Verdict.DB;

        if (storageGrowth <= 0) {
            return appGrowth > 0 ? Verdict.HTTP : Verdict.EVEN;
        }
        if (appGrowth > storageGrowth * DOMINANT_RATIO) {
            return Verdict.HTTP;
        }
        if (appGrowth > storageGrowth * OWN_TIME_RATIO) {
            // The app added time of its own, of the same order as the storage wait. Neither one
            // explains the spike by itself.
            return Verdict.EVEN;
        }
        return storageVerdict;
    }

    /**
     * How far a bucket rose above the tier's usual level.
     *
     * <p>A bucket at or below its own baseline falls back to the latency itself. Without that,
     * a run where the fault covers more than half the buckets - which puts the median inside the
     * fault - would make every tier look as though it had not moved.
     */
    private static long growthOver(long p99Nanos, long baselineNanos) {
        return p99Nanos > baselineNanos ? p99Nanos - baselineNanos : p99Nanos;
    }

    /**
     * The p99 this runner ran at when nothing was wrong, estimated as the lower quartile of the
     * buckets that saw traffic.
     *
     * <p>A low-order statistic rather than the median, because a bucket's p99 behaves like a
     * maximum: a bucket only partly covered by a fault still reports the fault's latency, so a
     * fault elevates every bucket it touches at all. Measured on a fixture with a five-second
     * fault in an eleven-second run, seven of the eleven buckets came back elevated and the
     * median sat inside the fault - which made the fault look like the tier's normal cost and the
     * growth over it nil. The quartile survives a fault covering up to three quarters of a run.
     *
     * <p>Across all buckets rather than only those under the spike threshold, because an endpoint
     * that normally costs more than the threshold would otherwise get no baseline at all, which
     * is precisely the case a baseline exists for. An empty bucket has no p99 and is skipped.
     */
    public static long baselineOf(List<Bucket> buckets, long warmupNanos) {
        List<Long> seen = new ArrayList<>();
        for (Bucket bucket : buckets) {
            if (bucket.count() == 0) {
                continue;
            }
            if (warmupNanos > 0 && bucket.startOffsetNanos() < warmupNanos) {
                continue;
            }
            seen.add(bucket.p99Nanos());
        }
        if (seen.isEmpty()) {
            return 0;
        }
        seen.sort(null);
        return seen.get((seen.size() - 1) / 4);
    }
}
