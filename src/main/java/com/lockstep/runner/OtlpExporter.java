package com.lockstep.runner;

import com.lockstep.core.PacedLoop;
import com.lockstep.core.RunCoordinator;
import com.lockstep.stats.RunnerSummary;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Sends a run's results to an OpenTelemetry collector, so a load test lands in the same dashboard
 * as the production traffic it is meant to resemble.
 *
 * <p>OTLP over HTTP with a JSON body, which the protocol defines alongside protobuf. That avoids a
 * dependency on the OpenTelemetry SDK for what amounts to one POST of a few dozen numbers — and
 * the SDK would pull in a metrics pipeline this has no use for, since the aggregation has already
 * happened.
 *
 * <p>Exported as explicit data points rather than through an SDK instrument because these are
 * already-computed summaries of a finished run, not live measurements. A percentile is a gauge at
 * the moment the run ended; pretending otherwise would misrepresent it.
 */
public final class OtlpExporter {

    private static final Duration TIMEOUT = Duration.ofSeconds(15);

    /** OTLP's enum for cumulative temporality, which is what a finished run's counters are. */
    private static final int AGGREGATION_TEMPORALITY_CUMULATIVE = 2;

    private final HttpClient client;
    private final String endpoint;
    private final String serviceName;

    private OtlpExporter(HttpClient client, String endpoint, String serviceName) {
        this.client = client;
        this.endpoint = endpoint;
        this.serviceName = serviceName;
    }

    public static OtlpExporter open(String endpoint, String serviceName) {
        URI uri;
        try {
            uri = URI.create(endpoint);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("--otlp is not a valid URL: " + endpoint, e);
        }
        if (uri.getScheme() == null || uri.getHost() == null) {
            throw new IllegalArgumentException(
                    "--otlp must be an absolute URL, e.g. http://localhost:4318/v1/metrics, got: "
                    + endpoint);
        }
        return new OtlpExporter(HttpClient.newBuilder().connectTimeout(TIMEOUT).build(),
                endpoint, serviceName == null || serviceName.isBlank() ? "lockstep" : serviceName);
    }

    /** Returns null on success, or a description of why the export did not land. */
    public String export(RunCoordinator.RunResult result) {
        String body = toJson(result);
        try {
            HttpResponse<String> response = client.send(
                    HttpRequest.newBuilder(URI.create(endpoint))
                            .timeout(TIMEOUT)
                            .header("content-type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(body))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                return "the collector answered HTTP " + response.statusCode()
                        + (response.body() == null || response.body().isBlank()
                                ? "" : ": " + response.body().strip());
            }
            return null;
        } catch (Exception e) {
            String message = e.getMessage();
            return e.getClass().getSimpleName() + (message == null ? "" : ": " + message);
        }
    }

    /** The OTLP/HTTP JSON body for a finished run. Package-visible so it can be verified. */
    public String toJson(RunCoordinator.RunResult result) {
        long endNanos = System.currentTimeMillis() * 1_000_000L;
        long startNanos = endNanos - result.context().durationNanos();

        List<String> metrics = new ArrayList<>();
        result.byRunner().forEach((name, loop) -> {
            RunnerSummary summary =
                    loop.series().summarize(name, result.context().durationNanos());
            String attributes = attribute("runner", name);

            metrics.add(sum("lockstep.requests", "{request}", summary.count(),
                    attributes, startNanos, endNanos));
            metrics.add(sum("lockstep.errors", "{request}", summary.errorCount(),
                    attributes, startNanos, endNanos));
            metrics.add(sum("lockstep.shed", "{request}", loop.shedCount(),
                    attributes, startNanos, endNanos));
            metrics.add(sum("lockstep.scheduled", "{request}", loop.scheduledCount(),
                    attributes, startNanos, endNanos));

            metrics.add(gauge("lockstep.latency.p50", "ns", summary.p50Nanos(),
                    attributes, endNanos));
            metrics.add(gauge("lockstep.latency.p95", "ns", summary.p95Nanos(),
                    attributes, endNanos));
            metrics.add(gauge("lockstep.latency.p99", "ns", summary.p99Nanos(),
                    attributes, endNanos));
            metrics.add(gauge("lockstep.latency.max", "ns", summary.maxNanos(),
                    attributes, endNanos));
            metrics.add(gauge("lockstep.service_time.p99", "ns", summary.serviceP99Nanos(),
                    attributes, endNanos));
            metrics.add(doubleGauge("lockstep.rate", "{request}/s",
                    summary.achievedRatePerSecond(), attributes, endNanos));
        });

        return """
                {"resourceMetrics":[{"resource":{"attributes":[%s]},\
                "scopeMetrics":[{"scope":{"name":"lockstep"},"metrics":[%s]}]}]}"""
                .formatted(attribute("service.name", serviceName), String.join(",", metrics));
    }

    private static String sum(String name, String unit, long value, String attributes,
            long startNanos, long endNanos) {
        return """
                {"name":"%s","unit":"%s","sum":{"dataPoints":[{"asInt":"%d",\
                "startTimeUnixNano":"%d","timeUnixNano":"%d","attributes":[%s]}],\
                "aggregationTemporality":%d,"isMonotonic":true}}"""
                .formatted(name, unit, value, startNanos, endNanos, attributes,
                        AGGREGATION_TEMPORALITY_CUMULATIVE);
    }

    private static String gauge(String name, String unit, long value, String attributes,
            long timeNanos) {
        return """
                {"name":"%s","unit":"%s","gauge":{"dataPoints":[{"asInt":"%d",\
                "timeUnixNano":"%d","attributes":[%s]}]}}"""
                .formatted(name, unit, value, timeNanos, attributes);
    }

    private static String doubleGauge(String name, String unit, double value, String attributes,
            long timeNanos) {
        return """
                {"name":"%s","unit":"%s","gauge":{"dataPoints":[{"asDouble":%s,\
                "timeUnixNano":"%d","attributes":[%s]}]}}"""
                .formatted(name, unit,
                        String.format(java.util.Locale.ROOT, "%.4f", value),
                        timeNanos, attributes);
    }

    private static String attribute(String key, String value) {
        return """
                {"key":"%s","value":{"stringValue":"%s"}}"""
                .formatted(key, escape(value));
    }

    private static String escape(String text) {
        return text == null ? "" : text.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /** How many metrics a run of this shape exports, for the report to state. */
    public static int metricsPerRunner() {
        return 10;
    }

    public String endpoint() {
        return endpoint;
    }
}
