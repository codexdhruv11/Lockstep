package com.lockstep.runner;

import com.lockstep.config.QuerySpec;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

public final class QueryPicker {
    private final List<QuerySpec> queries;
    private final int[] cumulativeWeights;
    private final int totalWeight;

    public QueryPicker(List<QuerySpec> queries) {
        if (queries == null || queries.isEmpty()) {
            throw new IllegalArgumentException("at least one query is required");
        }
        this.queries = List.copyOf(queries);
        this.cumulativeWeights = new int[this.queries.size()];
        int running = 0;
        for (int i = 0; i < this.queries.size(); i++) {
            running += Math.max(0, this.queries.get(i).weight());
            cumulativeWeights[i] = running;
        }
        this.totalWeight = running;
        if (totalWeight <= 0) {
            throw new IllegalArgumentException("total query weight must be greater than zero");
        }
    }

    public QuerySpec pick() {
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
        return queries.get(low);
    }

    public int totalWeight() {
        return totalWeight;
    }

    public List<QuerySpec> queries() {
        return queries;
    }
}
