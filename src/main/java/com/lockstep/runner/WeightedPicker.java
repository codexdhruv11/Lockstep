package com.lockstep.runner;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.ToIntFunction;

public final class WeightedPicker<T> {
    private final List<T> items;
    private final int[] cumulativeWeights;
    private final int totalWeight;

    public WeightedPicker(List<T> items, ToIntFunction<T> weightOf, boolean zeroMeansOne) {
        if (items == null || items.isEmpty()) {
            throw new IllegalArgumentException("at least one item is required");
        }
        this.items = List.copyOf(items);
        this.cumulativeWeights = new int[this.items.size()];
        int running = 0;
        for (int i = 0; i < this.items.size(); i++) {
            int weight = Math.max(0, weightOf.applyAsInt(this.items.get(i)));
            if (weight == 0 && zeroMeansOne) {
                weight = 1;
            }
            running += weight;
            cumulativeWeights[i] = running;
        }
        this.totalWeight = running;
        if (totalWeight <= 0) {
            throw new IllegalArgumentException("total weight must be greater than zero");
        }
    }

    public T pick() {
        return items.get(pickIndex());
    }

    public int pickIndex() {
        int draw = ThreadLocalRandom.current().nextInt(totalWeight);
        int low = 0;
        int high = cumulativeWeights.length - 1;
        while (low < high) {
            int mid = (low + high) >>> 1;
            if (draw < cumulativeWeights[mid]) {
                high = mid;
            } else {
                low = mid + 1;
            }
        }
        return low;
    }

    public int totalWeight() {
        return totalWeight;
    }

    public List<T> items() {
        return items;
    }
}
