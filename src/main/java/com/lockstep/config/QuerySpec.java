package com.lockstep.config;

import java.util.List;

public record QuerySpec(
        String query,
        int weight,
        String type,
        List<Object> args) {
    public QuerySpec {
        args = args == null ? List.of() : List.copyOf(args);
    }
}
