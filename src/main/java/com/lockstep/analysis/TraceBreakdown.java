package com.lockstep.analysis;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Divides a trace's duration among the things that actually consumed it.
 *
 * <p>A span's own duration is not its cost: a server span that took 4.2 seconds while waiting on a
 * query that took 3.9 is responsible for 0.3. What a reader wants is <em>self time</em> — the part
 * of a span not accounted for by its children — and the sum of self times across a trace is the
 * trace's duration, which makes the breakdown add up.
 *
 * <h2>How self time is computed, and why it is not simply a subtraction</h2>
 *
 * <p>Self time is a span's duration minus the time its children covered. Subtracting the sum of
 * child durations is wrong whenever children run in parallel, because the same wall-clock
 * microsecond would be subtracted twice and self time can go negative. So the children's intervals
 * are <strong>unioned</strong> first and the union's length subtracted.
 *
 * <p>Children are clamped to the parent's window before the union. An asynchronous child can start
 * before its parent or outlive it — fire-and-forget work is routinely recorded that way — and only
 * the overlap is time the parent was waiting. Without clamping such a child would subtract time
 * the parent never spent.
 *
 * <p>A trace whose spans do not form a tree is still summarised; orphans are treated as roots. A
 * missing parent is common when sampling drops a span, and refusing to report in that case would
 * discard a usable answer.
 */
public record TraceBreakdown(
        String traceId,
        List<Span> spans,
        List<Contribution> contributions,
        long totalNanos) {

    /** One span as the trace backend reported it. Times are nanoseconds. */
    public record Span(String spanId, String parentSpanId, String name, String service,
            long startNanos, long durationNanos) {

        public long endNanos() {
            return startNanos + durationNanos;
        }
    }

    /** One operation's share of the trace, after children are discounted. */
    public record Contribution(String name, String service, long selfNanos, int spanCount) {}

    public TraceBreakdown {
        spans = spans == null ? List.of() : List.copyOf(spans);
        contributions = contributions == null ? List.of() : List.copyOf(contributions);
    }

    public static TraceBreakdown empty(String traceId) {
        return new TraceBreakdown(traceId, List.of(), List.of(), 0);
    }

    public static TraceBreakdown of(String traceId, List<Span> spans) {
        if (spans == null || spans.isEmpty()) {
            return empty(traceId);
        }

        Map<String, List<Span>> childrenByParent = new LinkedHashMap<>();
        Map<String, Span> byId = new LinkedHashMap<>();
        for (Span span : spans) {
            byId.put(span.spanId(), span);
        }
        for (Span span : spans) {
            String parent = span.parentSpanId();
            // An orphan — a parent that was sampled away — is treated as a root rather than
            // dropped, so a partially sampled trace still produces an answer.
            if (parent != null && byId.containsKey(parent)) {
                childrenByParent.computeIfAbsent(parent, key -> new ArrayList<>()).add(span);
            }
        }

        Map<String, long[]> selfByName = new LinkedHashMap<>();
        Map<String, String> serviceByName = new LinkedHashMap<>();
        for (Span span : spans) {
            long self = selfNanos(span, childrenByParent.getOrDefault(span.spanId(), List.of()));
            String key = span.name() == null ? "(unnamed)" : span.name();
            long[] tally = selfByName.computeIfAbsent(key, k -> new long[2]);
            tally[0] += self;
            tally[1]++;
            serviceByName.putIfAbsent(key, span.service());
        }

        List<Contribution> contributions = new ArrayList<>();
        selfByName.forEach((name, tally) -> contributions.add(
                new Contribution(name, serviceByName.get(name), tally[0], (int) tally[1])));
        contributions.sort(Comparator.comparingLong(Contribution::selfNanos).reversed());

        // The trace's span, not the sum of its spans: the wall-clock window the whole trace
        // occupied, which is what a caller waited for.
        long earliest = spans.stream().mapToLong(Span::startNanos).min().orElse(0);
        long latest = spans.stream().mapToLong(Span::endNanos).max().orElse(0);

        return new TraceBreakdown(traceId, spans, contributions, Math.max(0, latest - earliest));
    }

    /** A span's duration minus the union of its children's intervals, clamped to its window. */
    static long selfNanos(Span span, List<Span> children) {
        if (children.isEmpty()) {
            return span.durationNanos();
        }
        List<long[]> clamped = new ArrayList<>();
        for (Span child : children) {
            long start = Math.max(child.startNanos(), span.startNanos());
            long end = Math.min(child.endNanos(), span.endNanos());
            if (end > start) {
                clamped.add(new long[] {start, end});
            }
        }
        if (clamped.isEmpty()) {
            return span.durationNanos();
        }
        clamped.sort(Comparator.comparingLong(interval -> interval[0]));

        long covered = 0;
        long currentStart = clamped.get(0)[0];
        long currentEnd = clamped.get(0)[1];
        for (int i = 1; i < clamped.size(); i++) {
            long[] interval = clamped.get(i);
            if (interval[0] <= currentEnd) {
                currentEnd = Math.max(currentEnd, interval[1]);
            } else {
                covered += currentEnd - currentStart;
                currentStart = interval[0];
                currentEnd = interval[1];
            }
        }
        covered += currentEnd - currentStart;

        return Math.max(0, span.durationNanos() - covered);
    }

    public List<Contribution> top(int limit) {
        return contributions.stream().limit(Math.max(0, limit)).toList();
    }

    public double shareOf(Contribution contribution) {
        long total = contributions.stream().mapToLong(Contribution::selfNanos).sum();
        return total <= 0 ? -1 : (double) contribution.selfNanos() / total;
    }

    /** Self times sum to the accounted total; that is the property that makes this add up. */
    public long accountedNanos() {
        return contributions.stream().mapToLong(Contribution::selfNanos).sum();
    }

    public boolean isEmpty() {
        return spans.isEmpty();
    }
}
