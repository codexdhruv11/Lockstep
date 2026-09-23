package com.lockstep.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = false)
public record RedisConfig(Target target, int rate) {
    public RedisConfig {
        target = target == null ? new Target("", 0, "", List.of()) : target;
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record Target(String addr, int db, String password, List<QuerySpec> queries) {
        public Target {
            addr = addr == null ? "" : addr;
            password = password == null ? "" : password;
            queries = queries == null ? List.of() : List.copyOf(queries);
        }
    }
}
