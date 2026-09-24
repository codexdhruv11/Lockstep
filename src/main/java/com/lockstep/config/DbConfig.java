package com.lockstep.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = false)
public record DbConfig(Target target, int rate) {
    public DbConfig {
        target = target == null ? new Target("", "", List.of(), 0) : target;
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record Target(String conn, String driver, List<QuerySpec> queries,
            @JsonProperty("pool_size") int poolSize) {
        public Target {
            conn = conn == null ? "" : conn;
            driver = driver == null ? "" : driver;
            queries = queries == null ? List.of() : List.copyOf(queries);
        }

        public Target(String conn, String driver, List<QuerySpec> queries) {
            this(conn, driver, queries, 0);
        }
    }
}
