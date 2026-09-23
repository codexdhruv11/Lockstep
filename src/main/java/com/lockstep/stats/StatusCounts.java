package com.lockstep.stats;

import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

public final class StatusCounts {
    private final ConcurrentHashMap<Integer, LongAdder> counts = new ConcurrentHashMap<>();

    public void record(int statusCode) {
        counts.computeIfAbsent(statusCode, code -> new LongAdder()).increment();
    }

    public Map<Integer, Long> snapshot() {
        Map<Integer, Long> copy = new TreeMap<>();
        counts.forEach((code, adder) -> copy.put(code, adder.sum()));
        return java.util.Collections.unmodifiableMap(copy);
    }
}
