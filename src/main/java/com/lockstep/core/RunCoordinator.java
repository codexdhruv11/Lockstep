package com.lockstep.core;

import com.lockstep.config.Config;
import com.lockstep.runner.db.DbRunner;
import com.lockstep.runner.http.HttpRunner;
import com.lockstep.runner.redis.RedisRunner;
import com.lockstep.scenario.ScenarioRunner;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public final class RunCoordinator {
    private RunCoordinator() {}

    public record RunResult(RunContext context, Map<String, PacedLoop.LoopResult> byRunner,
            ScenarioRunner scenarioRunner, DbRunner dbRunner, RedisRunner redisRunner,
            HttpRunner httpRunner) {
        public RunResult(RunContext context, Map<String, PacedLoop.LoopResult> byRunner,
                ScenarioRunner scenarioRunner) {
            this(context, byRunner, scenarioRunner, null, null, null);
        }

        public RunResult {
            byRunner = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(byRunner));
        }
    }

    public static RunResult execute(Config config, RunProgress progress) {
        return execute(config, progress, 0, 0);
    }

    public static RunResult execute(Config config, RunProgress progress, long explainThresholdNanos,
            long redisThresholdNanos) {
        return execute(config, progress, explainThresholdNanos, redisThresholdNanos, false);
    }

    public static RunResult execute(Config config, RunProgress progress, long explainThresholdNanos,
            long redisThresholdNanos, boolean trace) {
        int concurrency = config.concurrency() <= 0 ? RunContext.DEFAULT_CONCURRENCY : config.concurrency();
        List<Runner> runners = build(config, concurrency, trace);
        RunContext context = RunContext.startingAfterSetup(
                config.duration(), config.bucketWidth(), config.ramp(), config.concurrency(),
                0L, config.arrivalModel(), config.arrivalSeed());
        try {
            Map<String, PacedLoop.LoopResult> results = runAll(runners, context, progress);
            DbRunner db = first(runners, DbRunner.class);
            if (db != null && explainThresholdNanos > 0) {
                db.capturePlans(explainThresholdNanos);
            }
            RedisRunner redis = first(runners, RedisRunner.class);
            if (redis != null && redisThresholdNanos > 0) {
                redis.captureSlowlog(redisThresholdNanos);
            }
            return new RunResult(context, results, first(runners, ScenarioRunner.class), db, redis,
                    first(runners, HttpRunner.class));
        } finally {
            closeAll(runners);
        }
    }

    private static <T extends Runner> T first(List<Runner> runners, Class<T> type) {
        return runners.stream().filter(type::isInstance).map(type::cast).findFirst().orElse(null);
    }

    static List<Runner> build(Config config, int concurrency) {
        return build(config, concurrency, false);
    }

    static List<Runner> build(Config config, int concurrency, boolean trace) {
        List<Runner> runners = new ArrayList<>();
        try {
            if (config.http() != null) {
                runners.add(HttpRunner.create(config.http(), concurrency, trace));
            }
            if (!config.scenario().isEmpty()) {
                runners.add(ScenarioRunner.create(config.scenario(), scenarioRate(config), trace));
            }
            if (config.db() != null) {
                runners.add(DbRunner.create(config.db(), concurrency));
            }
            if (config.redis() != null) {
                runners.add(RedisRunner.create(config.redis(), concurrency));
            }
            return runners;
        } catch (RuntimeException e) {
            closeAll(runners);
            throw e;
        }
    }

    private static int scenarioRate(Config config) {
        return Math.max(1, config.concurrency());
    }

    private static Map<String, PacedLoop.LoopResult> runAll(
            List<Runner> runners, RunContext context, RunProgress progress) {
        Map<String, Future<PacedLoop.LoopResult>> futures = new LinkedHashMap<>();

        try (ExecutorService pacers = Executors.newFixedThreadPool(Math.max(1, runners.size()))) {
            for (Runner runner : runners) {
                RunProgress.Counter counter = progress == null ? null : progress.forRunner(runner.name());
                futures.put(runner.name(), pacers.submit(() -> runWith(runner, context, counter)));
            }
            Map<String, PacedLoop.LoopResult> results = new LinkedHashMap<>();
            for (Map.Entry<String, Future<PacedLoop.LoopResult>> entry : futures.entrySet()) {
                results.put(entry.getKey(), await(entry.getValue()));
            }
            return results;
        }
    }

    private static PacedLoop.LoopResult runWith(Runner runner, RunContext context, RunProgress.Counter counter) {
        if (counter == null) {
            return runner.run(context);
        }
        if (runner instanceof HttpRunner http) {
            return http.run(context, counter);
        }
        if (runner instanceof DbRunner db) {
            return db.run(context, counter);
        }
        if (runner instanceof RedisRunner redis) {
            return redis.run(context, counter);
        }
        if (runner instanceof ScenarioRunner scenario) {
            return scenario.run(context, counter);
        }
        return runner.run(context);
    }

    private static PacedLoop.LoopResult await(Future<PacedLoop.LoopResult> future) {
        try {
            return future.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("run interrupted", e);
        } catch (java.util.concurrent.ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new IllegalStateException("runner failed", cause);
        }
    }

    private static void closeAll(List<Runner> runners) {
        for (Runner runner : runners) {
            try {
                runner.close();
            } catch (RuntimeException e) {
            }
        }
    }
}
