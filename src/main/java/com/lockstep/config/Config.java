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
        String arrivals,
        long arrivalSeed,
        HttpConfig http,
        DbConfig db,
        RedisConfig redis,
        List<ScenarioConfig> scenario) {

    public Config(long duration, long bucketWidth, long ramp, int concurrency,
            HttpConfig http, DbConfig db, RedisConfig redis, List<ScenarioConfig> scenario) {
        this(duration, bucketWidth, ramp, concurrency, "constant", 0L, http, db, redis, scenario);
    }

    public com.lockstep.core.Arrivals arrivalModel() {
        return com.lockstep.core.Arrivals.parse(arrivals);
    }
    public Config {
        scenario = canonical(scenario);
    }

    private static List<ScenarioConfig> canonical(List<ScenarioConfig> scenario) {
        return scenario == null ? List.of() : List.copyOf(scenario);
    }

    public Config scaledBy(double multiplier) {
        return new Config(duration, bucketWidth, ramp, concurrency, arrivals, arrivalSeed,
                http == null ? null : new HttpConfig(http.target(), scaleRate(http.rate(), multiplier)),
                db == null ? null : new DbConfig(new DbConfig.Target(db.target().conn(),
                        db.target().driver(), db.target().queries(), db.target().poolSize()),
                        scaleRate(db.rate(), multiplier)),
                redis == null ? null : new RedisConfig(redis.target(), scaleRate(redis.rate(), multiplier)),
                scenario);
    }

    private static int scaleRate(int rate, double multiplier) {
        return Math.max(1, (int) Math.round(rate * multiplier));
    }

    public boolean hasScalableRate() {
        return http != null || db != null || redis != null;
    }

    public java.util.Map<String, Integer> ratesByRunner() {
        java.util.Map<String, Integer> rates = new java.util.LinkedHashMap<>();
        if (http != null) {
            rates.put("http", http.rate());
        }
        if (db != null) {
            rates.put("db", db.rate());
        }
        if (redis != null) {
            rates.put("redis", redis.rate());
        }
        return rates;
    }

    public Config withDuration(long durationNanos) {
        return new Config(durationNanos, bucketWidth, ramp, concurrency, arrivals, arrivalSeed,
                http, db, redis, scenario);
    }

    @JsonCreator
    static Config fromJson(
            @JsonProperty("duration") Object duration,
            @JsonProperty("bucket_width") Object bucketWidth,
            @JsonProperty("ramp") Object ramp,
            @JsonProperty("concurrency") Integer concurrency,
            @JsonProperty("arrivals") String arrivals,
            @JsonProperty("arrival_seed") Long arrivalSeed,
            @JsonProperty("http") HttpConfig http,
            @JsonProperty("db") DbConfig db,
            @JsonProperty("redis") RedisConfig redis,
            @JsonProperty("scenario") List<ScenarioConfig> scenario) {
        return new Config(
                durationNanos(duration, "duration"),

                bucketWidth == null ? 0L : durationNanos(bucketWidth, "bucket_width"),
                ramp == null ? 0L : durationNanos(ramp, "ramp"),
                concurrency == null ? 0 : concurrency,
                arrivals == null ? "constant" : arrivals,
                arrivalSeed == null ? 0L : arrivalSeed,
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
