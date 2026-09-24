package com.lockstep.runner;

import com.lockstep.config.QuerySpec;
import java.util.List;

public final class QueryPicker {
    private final WeightedPicker<QuerySpec> picker;

    public QueryPicker(List<QuerySpec> queries) {
        if (queries == null || queries.isEmpty()) {
            throw new IllegalArgumentException("at least one query is required");
        }

        this.picker = new WeightedPicker<>(queries, QuerySpec::weight, false);
    }

    public QuerySpec pick() {
        return picker.pick();
    }

    public int pickIndex() {
        return picker.pickIndex();
    }

    public int totalWeight() {
        return picker.totalWeight();
    }

    public List<QuerySpec> queries() {
        return picker.items();
    }
}
