package com.lockstep.core;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Generates W3C {@code traceparent} headers so the target's own instrumentation can tie its spans
 * to the request Lockstep sent.
 *
 * <p>This is the whole integration. Any server instrumented with OpenTelemetry — in any language,
 * exporting to any backend that speaks OTLP — reads this header and makes its server span a child
 * of ours, then its SQL, cache and outbound calls children of that. Lockstep does not need to know
 * what the target is written in or what it talks to.
 *
 * <p>So a report can say which request was slow <em>and</em> hand over the identifier that shows
 * where inside the target the time went. That is the one thing no amount of client-side
 * measurement can establish: whether 200ms went to middleware, to serialisation, or to a query.
 *
 * <h2>Why this is off by default</h2>
 *
 * <p>The sampled flag has to be set or the target records nothing and the feature is pointless.
 * But a sampled flag on every request means the target exports a trace for every request, and
 * most commercial backends bill per span. Switching this on by default would quietly turn a load
 * test into a bill. It is opt-in, and the report says when it is off.
 *
 * @see <a href="https://www.w3.org/TR/trace-context/">W3C Trace Context</a>
 */
public final class TraceContext {

    public static final String HEADER = "traceparent";

    /** Only version 00 is defined; a receiver must reject anything it cannot parse. */
    private static final String VERSION = "00";

    /** 01 = sampled. Without it the target drops the trace and there is nothing to look at. */
    private static final String SAMPLED = "01";

    private static final int TRACE_ID_HEX = 32;
    private static final int SPAN_ID_HEX = 16;

    private TraceContext() {}

    /**
     * A fresh trace id: 32 hex characters, never all zeros, which the specification forbids.
     *
     * <p>{@link ThreadLocalRandom} rather than {@code SecureRandom}: this runs once per request on
     * the hot path, and a trace id needs to be unique rather than unguessable.
     */
    public static String newTraceId() {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        long high = random.nextLong();
        long low = random.nextLong();
        if (high == 0 && low == 0) {
            low = 1;
        }
        return "%016x%016x".formatted(high, low);
    }

    /** A fresh span id: 16 hex characters, never all zeros. */
    public static String newSpanId() {
        long id = ThreadLocalRandom.current().nextLong();
        return "%016x".formatted(id == 0 ? 1 : id);
    }

    /** A complete header value for a new trace. */
    public static String newHeader() {
        return header(newTraceId(), newSpanId());
    }

    /**
     * A header value continuing an existing trace with a new span.
     *
     * <p>Used for the steps of a scenario, so a journey arrives in the trace backend as one trace
     * with a span per step rather than as unrelated requests. That is what makes a multi-step
     * journey readable there.
     */
    public static String header(String traceId, String spanId) {
        return VERSION + "-" + traceId + "-" + spanId + "-" + SAMPLED;
    }

    /** The trace id out of a header value, or null when it is not a header this class would emit. */
    public static String traceIdOf(String header) {
        if (header == null) {
            return null;
        }
        String[] parts = header.split("-");
        if (parts.length != 4 || parts[1].length() != TRACE_ID_HEX) {
            return null;
        }
        return parts[1];
    }

    /** Whether a value is a well-formed, sampled traceparent that a receiver would accept. */
    public static boolean isValid(String header) {
        if (header == null) {
            return false;
        }
        String[] parts = header.split("-");
        if (parts.length != 4) {
            return false;
        }
        if (!parts[0].equals(VERSION)
                || parts[1].length() != TRACE_ID_HEX
                || parts[2].length() != SPAN_ID_HEX
                || parts[3].length() != 2) {
            return false;
        }
        if (parts[1].chars().allMatch(c -> c == '0') || parts[2].chars().allMatch(c -> c == '0')) {
            return false;
        }
        return isHex(parts[1]) && isHex(parts[2]) && isHex(parts[3]);
    }

    private static boolean isHex(String text) {
        return text.chars().allMatch(c -> (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f'));
    }
}
