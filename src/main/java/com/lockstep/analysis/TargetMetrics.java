package com.lockstep.analysis;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The target's own account of the run, read from its metrics endpoint.
 *
 * <p>Everything else Lockstep reports is measured from outside. That leaves one question open: how
 * much of the latency a caller saw was the target <em>working</em>, and how much was the request
 * waiting to be worked on. The server's own timing answers it, because the gap between what the
 * server thinks it took and what the client experienced <strong>is</strong> the queue — measured
 * rather than inferred.
 *
 * <p>It also makes the bottleneck question answerable for targets that are not PostgreSQL. Pool
 * saturation, garbage collection and CPU are reported by the runtime itself, whatever it is, so
 * reading them needs no per-database work.
 *
 * <h2>Naming</h2>
 *
 * <p>Metric names differ between Micrometer and the OpenTelemetry conventions, and the
 * conventions themselves have changed. So each figure is looked up under several names and the
 * report states which one was found, rather than reporting zero for a metric that was simply
 * called something else.
 */
public record TargetMetrics(
        boolean available,
        String unavailableReason,
        String endpoint,
        long requestCountDelta,
        double requestSecondsDelta,
        String requestMetricUsed,
        double poolActiveMean,
        double poolActiveMax,
        double poolMaxConfigured,
        String poolMetricUsed,
        long gcPauseCountDelta,
        double gcPauseSecondsDelta,
        String gcMetricUsed,
        double cpuMean,
        double cpuMax,
        String cpuMetricUsed,
        int samples,
        Map<String, String> metricsNotFound) {

    /** How often gauges are sampled. Counters only need a before and an after. */
    public static final long SAMPLE_INTERVAL_MILLIS = 250;

    /** Candidate names for each figure, newest convention first. */
    public static final List<String> REQUEST_COUNT_NAMES = List.of(
            "http_server_requests_seconds_count",          // Micrometer / Spring Boot
            "http_server_request_duration_seconds_count",   // OTel semconv 1.23+
            "http_server_duration_milliseconds_count",      // OTel semconv, older
            "http_requests_total");                         // common hand-rolled
    public static final List<String> REQUEST_SECONDS_NAMES = List.of(
            "http_server_requests_seconds_sum",
            "http_server_request_duration_seconds_sum",
            "http_server_duration_milliseconds_sum");
    public static final List<String> POOL_ACTIVE_NAMES = List.of(
            "hikaricp_connections_active",
            "db_client_connections_usage",
            "pool_connections_active");
    public static final List<String> POOL_MAX_NAMES = List.of(
            "hikaricp_connections_max",
            "db_client_connections_max",
            "pool_connections_max");
    public static final List<String> GC_COUNT_NAMES = List.of(
            "jvm_gc_pause_seconds_count",
            "go_gc_duration_seconds_count",
            "nodejs_gc_duration_seconds_count");
    public static final List<String> GC_SECONDS_NAMES = List.of(
            "jvm_gc_pause_seconds_sum",
            "go_gc_duration_seconds_sum",
            "nodejs_gc_duration_seconds_sum");
    public static final List<String> CPU_NAMES = List.of(
            "process_cpu_usage",
            "system_cpu_usage",
            "process_cpu_seconds_total");

    public TargetMetrics {
        metricsNotFound = metricsNotFound == null
                ? Map.of()
                : java.util.Collections.unmodifiableMap(new LinkedHashMap<>(metricsNotFound));
    }

    public static TargetMetrics unavailable(String endpoint, String reason) {
        return new TargetMetrics(false, reason, endpoint, 0, 0, null, -1, -1, -1, null,
                0, 0, null, -1, -1, null, 0, Map.of());
    }

    /** The server's own mean service time, or -1 when it did not report one. */
    public long serverMeanNanos() {
        if (requestCountDelta <= 0 || requestSecondsDelta <= 0) {
            return -1;
        }
        double seconds = requestSecondsDelta / requestCountDelta;
        // The older OTel convention reports milliseconds in the metric name, not seconds.
        if (requestMetricUsed != null && requestMetricUsed.contains("milliseconds")) {
            seconds = seconds / 1_000.0;
        }
        // Rounded, not truncated: these are derived through floating-point division, and
        // truncation turns 0.6/12 seconds into 49999999ns instead of 50ms — the same slip
        // already fixed once in GrowthCurve.
        return Math.round(seconds * 1_000_000_000d);
    }

    /**
     * How much of a caller's latency was spent waiting rather than being served: the client's
     * observed mean minus the server's own. Negative when it cannot be computed.
     *
     * <p>This is the measurement that replaces an inference. A large gap means requests were
     * queued — in an accept backlog, a thread pool, or a connection pool — and the target's own
     * timing cannot see any of it, because its clock starts when the work does.
     */
    public long queueingNanos(long clientMeanNanos) {
        long server = serverMeanNanos();
        if (server < 0 || clientMeanNanos <= 0) {
            return -1;
        }
        return Math.max(0, clientMeanNanos - server);
    }

    public double poolSaturation() {
        if (poolActiveMax < 0 || poolMaxConfigured <= 0) {
            return -1;
        }
        return poolActiveMax / poolMaxConfigured;
    }

    public boolean poolExhausted() {
        double saturation = poolSaturation();
        return saturation >= 0.95;
    }

    /** Mean GC pause over the run, or -1. */
    public long meanGcPauseNanos() {
        if (gcPauseCountDelta <= 0 || gcPauseSecondsDelta <= 0) {
            return -1;
        }
        return Math.round(gcPauseSecondsDelta / gcPauseCountDelta * 1_000_000_000d);
    }

    /** Total time the target spent paused for garbage collection during the run. */
    public long totalGcPauseNanos() {
        return gcPauseSecondsDelta <= 0
                ? -1 : Math.round(gcPauseSecondsDelta * 1_000_000_000d);
    }

    /** The share of the run the target spent collecting garbage, or -1. */
    public double gcShareOfRun(long runDurationNanos) {
        long total = totalGcPauseNanos();
        if (total < 0 || runDurationNanos <= 0) {
            return -1;
        }
        return (double) total / runDurationNanos;
    }

    public boolean anythingFound() {
        return requestMetricUsed != null || poolMetricUsed != null
                || gcMetricUsed != null || cpuMetricUsed != null;
    }
}
