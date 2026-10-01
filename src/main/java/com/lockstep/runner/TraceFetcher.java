package com.lockstep.runner;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lockstep.analysis.TraceBreakdown;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Fetches traces back from a trace backend, so the report can print where a slow request's time
 * went rather than only its identifier.
 *
 * <p>Jaeger's query API only. Tempo and the commercial backends each expose a different shape, and
 * guessing at an API is how a feature quietly returns nothing: the one implemented here was read
 * off a running Jaeger rather than from documentation. {@link #describeSupport()} says so in the
 * report, so an unsupported backend is a stated limitation and not a silent empty section.
 *
 * <p>Fetching is retried briefly. A backend ingests asynchronously, so a trace for a request that
 * finished moments ago is routinely not queryable yet, and one attempt would usually find nothing.
 */
public final class TraceFetcher implements AutoCloseable {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    /** Jaeger reports microseconds; everything in Lockstep is nanoseconds. */
    private static final long MICROS_TO_NANOS = 1_000L;

    private static final int ATTEMPTS = 5;
    private static final long RETRY_MILLIS = 700;

    private final HttpClient client;
    private final String baseUrl;

    private TraceFetcher(HttpClient client, String baseUrl) {
        this.client = client;
        this.baseUrl = baseUrl;
    }

    public static TraceFetcher open(String baseUrl) {
        URI uri;
        try {
            uri = URI.create(baseUrl);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("--traces is not a valid URL: " + baseUrl, e);
        }
        if (uri.getScheme() == null || uri.getHost() == null) {
            throw new IllegalArgumentException(
                    "--traces must be an absolute URL, e.g. http://localhost:16686, got: "
                    + baseUrl);
        }
        String trimmed = baseUrl.endsWith("/")
                ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        return new TraceFetcher(
                HttpClient.newBuilder().connectTimeout(TIMEOUT).build(), trimmed);
    }

    public static String describeSupport() {
        return "Jaeger's query API (/api/traces/{id}); Tempo and vendor backends are not supported";
    }

    /** Null when the trace could not be fetched or held no spans. */
    public TraceBreakdown fetch(String traceId) {
        if (traceId == null || traceId.isBlank()) {
            return null;
        }
        for (int attempt = 1; attempt <= ATTEMPTS; attempt++) {
            try {
                TraceBreakdown breakdown = fetchOnce(traceId);
                if (breakdown != null && !breakdown.isEmpty()) {
                    return breakdown;
                }
            } catch (Exception e) {
                // Fall through to the retry: a backend that is still ingesting answers 404.
            }
            if (attempt < ATTEMPTS) {
                try {
                    Thread.sleep(RETRY_MILLIS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return null;
                }
            }
        }
        return null;
    }

    private TraceBreakdown fetchOnce(String traceId) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(
                        URI.create(baseUrl + "/api/traces/" + traceId))
                .timeout(TIMEOUT)
                .header("accept", "application/json")
                .GET()
                .build();
        HttpResponse<String> response =
                client.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            return null;
        }
        return parse(traceId, response.body());
    }

    /**
     * Parses Jaeger's response. The shape was taken from a running Jaeger 1.57: {@code data[0]}
     * holds {@code spans[]}, each with {@code spanID}, {@code operationName}, {@code startTime}
     * and {@code duration} in microseconds, and a parent in
     * {@code references[refType=CHILD_OF].spanID}. Service names live in a separate
     * {@code processes} map keyed by each span's {@code processID}.
     *
     * <p>Public because the response shape is the part of this most likely to be wrong, and a
     * pure function from a body to a breakdown is the only way to verify it without a running
     * backend. It is also useful on its own to anyone holding a saved response.
     */
    public static TraceBreakdown parse(String traceId, String body) throws Exception {
        JsonNode root = MAPPER.readTree(body);
        JsonNode data = root.path("data");
        if (!data.isArray() || data.isEmpty()) {
            return TraceBreakdown.empty(traceId);
        }
        JsonNode trace = data.get(0);

        Map<String, String> serviceByProcess = new LinkedHashMap<>();
        JsonNode processes = trace.path("processes");
        processes.fieldNames().forEachRemaining(processId ->
                serviceByProcess.put(processId,
                        processes.path(processId).path("serviceName").asText(null)));

        List<TraceBreakdown.Span> spans = new ArrayList<>();
        for (JsonNode span : trace.path("spans")) {
            String parent = null;
            for (JsonNode reference : span.path("references")) {
                if ("CHILD_OF".equals(reference.path("refType").asText())) {
                    parent = reference.path("spanID").asText(null);
                    break;
                }
            }
            spans.add(new TraceBreakdown.Span(
                    span.path("spanID").asText(null),
                    parent,
                    span.path("operationName").asText(null),
                    serviceByProcess.get(span.path("processID").asText("")),
                    span.path("startTime").asLong() * MICROS_TO_NANOS,
                    span.path("duration").asLong() * MICROS_TO_NANOS));
        }
        return TraceBreakdown.of(traceId, spans);
    }

    @Override
    public void close() {
        client.close();
    }
}
