package com.lockstep.core;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

public final class RunProgress {
    private final Map<String, Counter> counters = new ConcurrentHashMap<>();

    public Counter forRunner(String runnerName) {
        return counters.computeIfAbsent(runnerName, name -> new Counter());
    }

    public Map<String, Counts> snapshot() {
        Map<String, Counts> copy = new LinkedHashMap<>();
        counters.forEach((name, counter) ->
                copy.put(name, new Counts(counter.fired.sum(), counter.errors.sum())));
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
