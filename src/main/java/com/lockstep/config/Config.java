package com.lockstep.config;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.lockstep.util.ConfigDurationException;
import com.lockstep.util.Durations;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = false)
public record Config(
        long duration,
        long bucketWidth,
        long ramp,
        int concurrency,
        HttpConfig http,
        DbConfig db,
        RedisConfig redis,
        List<ScenarioConfig> scenario) {
    public Config {
        scenario = canonical(scenario);
    }

    private static List<ScenarioConfig> canonical(List<ScenarioConfig> scenario) {
        return scenario == null ? List.of() : List.copyOf(scenario);
    }

    @JsonCreator
    static Config fromJson(
            @JsonProperty("duration") Object duration,
            @JsonProperty("bucket_width") Object bucketWidth,
            @JsonProperty("ramp") Object ramp,
            @JsonProperty("concurrency") Integer concurrency,
            @JsonProperty("http") HttpConfig http,
            @JsonProperty("db") DbConfig db,
            @JsonProperty("redis") RedisConfig redis,
            @JsonProperty("scenario") List<ScenarioConfig> scenario) {
        return new Config(
                durationNanos(duration, "duration"),

                bucketWidth == null ? 0L : durationNanos(bucketWidth, "bucket_width"),
                ramp == null ? 0L : durationNanos(ramp, "ramp"),
                concurrency == null ? 0 : concurrency,
                http,
                db,
                redis,
                canonical(scenario));
    }

    private static long durationNanos(Object value, String field) {
        if (value == null) {
            throw new ConfigValidationException(field, field + " must be greater than zero");
        }
        try {
            return Durations.parseToNanos(String.valueOf(value));
        } catch (ConfigDurationException e) {
            throw new ConfigValidationException(field, e.getMessage());
        }
    }
}
