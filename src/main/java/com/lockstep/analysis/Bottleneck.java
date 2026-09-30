package com.lockstep.analysis;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Names the resource that bounded the run, instead of leaving the reader to infer it from a
 * latency number.
 *
 * <p>Built from samples of {@code pg_stat_activity} taken while the load runs. Each sample records
 * how many of the target's backends were doing something and what they were waiting on. Over a run
 * that produces two useful things: whether the backend count sat against a ceiling, and what the
 * backends spent their time waiting for.
 *
 * <p>The connection ceiling is <em>inferred</em>, not read. Lockstep is outside the target and
 * cannot see its pool configuration, but a client pool reveals itself: the number of busy backends
 * plateaus at exactly the pool size and stays there while work is queued. A plateau held for most
 * of a saturated run is almost certainly the pool limit, and is reported as an inference.
 */
public record Bottleneck(
        boolean available,
        String unavailableReason,
        int samples,
        double meanActiveBackends,
        int maxActiveBackends,
        int plateauBackends,
        int plateauSamples,
        long serverMaxConnections,
        Map<String, Integer> waitsByType,
        Map<String, Integer> waitsByEvent) {

    /**
     * How often the sampler looks. Frequent enough to see a plateau in a run of a few seconds,
     * rare enough that the sampling is not itself load: one trivial query against a system view
     * four times a second.
     */
    public static final long SAMPLE_INTERVAL_MILLIS = 250;

    /** A plateau held for at least this share of samples is treated as a real ceiling. */
    public static final double PLATEAU_SHARE = 0.5;

    /** A wait type this dominant is named as the bound. */
    public static final double DOMINANT_WAIT_SHARE = 0.4;

    /** Postgres reports no wait event for a backend that is actually running. */
    public static final String RUNNING = "(running)";

    public Bottleneck {
        waitsByType = waitsByType == null
                ? Map.of() : java.util.Collections.unmodifiableMap(new LinkedHashMap<>(waitsByType));
        waitsByEvent = waitsByEvent == null
                ? Map.of() : java.util.Collections.unmodifiableMap(new LinkedHashMap<>(waitsByEvent));
    }

    public static Bottleneck unavailable(String reason) {
        return new Bottleneck(false, reason, 0, 0, 0, 0, 0, 0, Map.of(), Map.of());
    }

    public enum Verdict {
        /** Busy backends sat at a ceiling: more work could not get a connection. */
        CONNECTIONS,

        /** Backends were mostly waiting for the disk. */
        STORAGE_IO,

        /** Backends were mostly blocked on each other. */
        LOCK_CONTENTION,

        /** Backends were mostly running, not waiting: the server was doing the work. */
        CPU,

        /** Nothing was near a limit. */
        NOT_SATURATED,

        /** Too few samples, or nothing to conclude from. */
        UNKNOWN
    }

    public boolean hasSamples() {
        return samples > 0;
    }

    /** The share of samples in which the busy-backend count sat at its plateau. */
    public double plateauShare() {
        return samples <= 0 ? 0 : (double) plateauSamples / samples;
    }

    /**
     * True when busy backends held a plateau for most of the run at a value above one. A plateau
     * of one is a single-threaded client, not a pool ceiling.
     */
    public boolean connectionCeilingReached() {
        return plateauBackends > 1 && plateauShare() >= PLATEAU_SHARE
                && (serverMaxConnections <= 0 || plateauBackends < serverMaxConnections);
    }

    /** The wait type with the largest share, or null when nothing was recorded. */
    public String dominantWaitType() {
        String best = null;
        int bestCount = 0;
        for (Map.Entry<String, Integer> entry : waitsByType.entrySet()) {
            if (entry.getValue() > bestCount) {
                bestCount = entry.getValue();
                best = entry.getKey();
            }
        }
        return best;
    }

    public double shareOfWaits(String type) {
        int total = waitsByType.values().stream().mapToInt(Integer::intValue).sum();
        return total <= 0 ? 0 : (double) waitsByType.getOrDefault(type, 0) / total;
    }

    public Verdict verdict() {
        if (!available || samples < 3) {
            return Verdict.UNKNOWN;
        }
        if (waitsByType.isEmpty() && meanActiveBackends < 1) {
            return Verdict.NOT_SATURATED;
        }
        if (connectionCeilingReached()) {
            return Verdict.CONNECTIONS;
        }
        String dominant = dominantWaitType();
        if (dominant == null) {
            return Verdict.NOT_SATURATED;
        }
        double share = shareOfWaits(dominant);
        if (share < DOMINANT_WAIT_SHARE) {
            return Verdict.NOT_SATURATED;
        }
        return switch (dominant) {
            case "IO" -> Verdict.STORAGE_IO;
            case "Lock" -> Verdict.LOCK_CONTENTION;
            case RUNNING -> Verdict.CPU;
            default -> Verdict.NOT_SATURATED;
        };
    }

    /** Wait events by share, largest first, for the report. */
    public List<Map.Entry<String, Integer>> topWaitEvents(int limit) {
        return waitsByEvent.entrySet().stream()
                .sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
                .limit(Math.max(0, limit))
                .toList();
    }

    public int totalWaitObservations() {
        return waitsByEvent.values().stream().mapToInt(Integer::intValue).sum();
    }
}
