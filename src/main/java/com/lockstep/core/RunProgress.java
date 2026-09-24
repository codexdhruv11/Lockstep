package com.lockstep.core;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

public final class RunProgress {
    private final Map<String, Counter> counters = new ConcurrentHashMap<>();

    private final java.util.List<String> order = new java.util.concurrent.CopyOnWriteArrayList<>();

    public Counter forRunner(String runnerName) {
        return counters.computeIfAbsent(runnerName, name -> {
            order.add(name);
            return new Counter();
        });
    }

    public Map<String, Counts> snapshot() {
        Map<String, Counts> copy = new LinkedHashMap<>();
        for (String name : order) {
            Counter counter = counters.get(name);
            if (counter != null) {
                copy.put(name, new Counts(counter.fired.sum(), counter.errors.sum()));
            }
        }
        return copy;
    }

    public static final class Counter {
        private final LongAdder fired = new LongAdder();
        private final LongAdder errors = new LongAdder();

        public void record(boolean success) {
            fired.increment();
            if (!success) {
                errors.increment();
            }
        }
    }

    public record Counts(long fired, long errors) {}
}
