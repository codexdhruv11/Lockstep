package com.lockstep.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = false)
public record DbConfig(Target target, int rate) {
    public DbConfig {
        target = target == null ? new Target("", "", List.of()) : target;
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record Target(String conn, String driver, List<QuerySpec> queries) {
        public Target {
            conn = conn == null ? "" : conn;
            driver = driver == null ? "" : driver;
            queries = queries == null ? List.of() : List.copyOf(queries);
        }
    }
}
