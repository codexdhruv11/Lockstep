package com.lockstep.stats;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;

/**
 * Keeps the slowest few requests of a run, with the trace id of each, so a report can hand over
 * identifiers rather than only percentiles.
 *
 * <p>A histogram answers "how slow was the tail" and cannot answer "which requests were in it".
 * This keeps a bounded number of the worst, so the report can say: here is the slowest request and
 * here is the trace that shows where its time went.
 *
 * <p>Bounded deliberately. Recording every request's id would be unbounded memory on a long run,
 * and the whole project's position is that a measurement tool must not be the thing that runs out
 * of memory.
 *
 * <h2>Why the lock is cheap</h2>
 *
 * <p>This is written to from every virtual thread on the hot path, so the common case must not
 * contend. A volatile floor holds the latency of the current worst entry once the heap is full;
 * a request slower than nothing in the heap fails that comparison and never takes the lock. Once
 * the heap is full the floor only rises, so the share of requests that lock falls away as the run
 * proceeds.
 */
public final class SlowestRequests {

    public static final int DEFAULT_CAPACITY = 10;

    /** latency, the trace id if one was sent, and when in the run it happened. */
    public record Entry(long latencyNanos, String traceId, long scheduledOffsetNanos) {}

    private final int capacity;
    private final PriorityQueue<Entry> worst =
            new PriorityQueue<>(Comparator.comparingLong(Entry::latencyNanos));
    private final Object lock = new Object();

    /** The smallest latency currently held, once full. Read without the lock. */
    private volatile long floorNanos = Long.MIN_VALUE;

    public SlowestRequests() {
        this(DEFAULT_CAPACITY);
    }

    public SlowestRequests(int capacity) {
        this.capacity = Math.max(1, capacity);
    }

    public void record(long latencyNanos, String traceId, long scheduledOffsetNanos) {
        // The fast path: already full, and this request is not among the worst.
        if (latencyNanos <= floorNanos) {
            return;
        }
        synchronized (lock) {
            worst.add(new Entry(latencyNanos, traceId, scheduledOffsetNanos));
            while (worst.size() > capacity) {
                worst.poll();
            }
            floorNanos = worst.size() >= capacity && worst.peek() != null
                    ? worst.peek().latencyNanos()
                    : Long.MIN_VALUE;
        }
    }

    /** The slowest first. */
    public List<Entry> snapshot() {
        List<Entry> entries;
        synchronized (lock) {
            entries = new ArrayList<>(worst);
        }
        entries.sort(Comparator.comparingLong(Entry::latencyNanos).reversed());
        return List.copyOf(entries);
    }

    public boolean anyTraced() {
        return snapshot().stream().anyMatch(entry -> entry.traceId() != null);
    }
}
