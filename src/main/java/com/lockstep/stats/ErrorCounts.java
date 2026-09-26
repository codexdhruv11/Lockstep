package com.lockstep.stats;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

public final class ErrorCounts {
    static final int MAX_DISTINCT = 20;

    static final int MAX_MESSAGE_LENGTH = 140;

    public static final String OVERFLOW_KEY = "(other failure messages)";

    private final ConcurrentHashMap<String, LongAdder> counts = new ConcurrentHashMap<>();

    public void record(String message) {
        if (message == null || message.isBlank()) {
            return;
        }
        String key = truncate(message.strip());
        LongAdder existing = counts.get(key);
        if (existing != null) {
            existing.increment();
            return;
        }

        if (counts.size() >= MAX_DISTINCT) {
            counts.computeIfAbsent(OVERFLOW_KEY, ignored -> new LongAdder()).increment();
            return;
        }
        counts.computeIfAbsent(key, ignored -> new LongAdder()).increment();
    }

    private static String truncate(String message) {
        return message.length() <= MAX_MESSAGE_LENGTH
                ? message
                : message.substring(0, MAX_MESSAGE_LENGTH - 1) + "…";
    }

    public Map<String, Long> snapshot() {
        Map<String, Long> out = new LinkedHashMap<>();
        counts.entrySet().stream()
                .map(entry -> Map.entry(entry.getKey(), entry.getValue().sum()))

                .sorted((a, b) -> {
                    boolean aOverflow = a.getKey().equals(OVERFLOW_KEY);
                    boolean bOverflow = b.getKey().equals(OVERFLOW_KEY);
                    if (aOverflow != bOverflow) {
                        return aOverflow ? 1 : -1;
                    }
                    return Long.compare(b.getValue(), a.getValue());
                })
                .forEach(entry -> out.put(entry.getKey(), entry.getValue()));
        return out;
    }

    public boolean truncated() {
        return counts.containsKey(OVERFLOW_KEY);
    }
}
