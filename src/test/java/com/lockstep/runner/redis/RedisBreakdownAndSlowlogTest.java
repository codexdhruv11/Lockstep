package com.lockstep.runner.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.lockstep.config.QuerySpec;
import com.lockstep.config.RedisConfig;
import com.lockstep.core.PacedLoop;
import com.lockstep.core.RunContext;
import com.lockstep.runner.QueryLabels;
import com.lockstep.stats.BucketSeries;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

final class RedisBreakdownAndSlowlogTest {
    private static final long MS = 1_000_000L;
    private static final long SECOND = 1_000_000_000L;

    private GenericContainer<?> redis(String... command) {
        return new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
                .withExposedPorts(6379)
                .withCommand(command);
    }

    private RedisConfig config(GenericContainer<?> redis, List<QuerySpec> queries, int rate) {
        String addr = redis.getHost() + ":" + redis.getFirstMappedPort();
        return new RedisConfig(new RedisConfig.Target(addr, 0, "", queries), rate);
    }

    @Test
    void everyOperationIsAttributedToExactlyOneCommand() {
        assumeTrue(DockerClientFactory.instance().isDockerAvailable(),
                "Docker is not available — Redis breakdown test skipped");

        try (GenericContainer<?> redis = redis("redis-server")) {
            redis.start();
            RedisConfig config = config(redis, List.of(
                    new QuerySpec("PING", 50, null, null),
                    new QuerySpec("SET k v", 30, null, null),
                    new QuerySpec("GET k", 20, null, null)), 300);

            try (RedisRunner runner = RedisRunner.create(config, 8)) {
                PacedLoop.LoopResult result = runner.run(
                        RunContext.startingNow(SECOND, 200 * MS, 0, 8));

                Map<String, BucketSeries> breakdown = runner.commandBreakdown();
                assertThat(breakdown).hasSize(3);

                long attributed = breakdown.values().stream().mapToLong(BucketSeries::totalCount).sum();
                assertThat(attributed)
                        .withFailMessage("per-command counts must account for every recorded "
                                + "operation: runner saw %d, commands account for %d",
                                result.executedCount(), attributed)
                        .isEqualTo(result.executedCount());

                assertThat(breakdown.get(QueryLabels.of(0, "PING")).totalCount())
                        .isGreaterThan(breakdown.get(QueryLabels.of(2, "GET k")).totalCount());
            }
        }
    }

    @Test
    void theServersOwnTimingIsCapturedForACommandThatWasGenuinelySlow() {
        assumeTrue(DockerClientFactory.instance().isDockerAvailable(),
                "Docker is not available — Redis slowlog test skipped");

        try (GenericContainer<?> redis = redis("redis-server", "--slowlog-log-slower-than", "0")) {
            redis.start();
            RedisConfig config = config(redis, List.of(
                    new QuerySpec("SET slow:key value", 50, null, null),
                    new QuerySpec("GET slow:key", 50, null, null)), 60);

            try (RedisRunner runner = RedisRunner.create(config, 4)) {
                runner.run(RunContext.startingNow(SECOND, 200 * MS, 0, 4));

                runner.captureSlowlog(1);

                assertThat(runner.slowlog()).isNotEmpty();
                assertThat(runner.slowlogNote()).isNull();

                assertThat(runner.slowlog()).anySatisfy(entry -> {
                    assertThat(entry.command()).contains("slow:key");
                    assertThat(entry.durationMicros()).isGreaterThanOrEqualTo(0);
                });
            }
        }
    }

    @Test
    void entriesFromBeforeTheRunAreNotReportedAsItsOwn() {
        assumeTrue(DockerClientFactory.instance().isDockerAvailable(),
                "Docker is not available — Redis slowlog test skipped");

        try (GenericContainer<?> redis = redis("redis-server", "--slowlog-log-slower-than", "0")) {
            redis.start();

            RedisConfig warmup = config(redis, List.of(
                    new QuerySpec("SET before:the:run 1", 1, null, null)), 20);
            try (RedisRunner first = RedisRunner.create(warmup, 1)) {
                first.run(RunContext.startingNow(300 * MS, 100 * MS, 0, 1));
            }

            RedisConfig config = config(redis, List.of(
                    new QuerySpec("SET during:the:run 1", 1, null, null)), 40);
            try (RedisRunner runner = RedisRunner.create(config, 2)) {
                runner.run(RunContext.startingNow(500 * MS, 100 * MS, 0, 2));
                runner.captureSlowlog(1);

                assertThat(runner.slowlog()).isNotEmpty();
                assertThat(runner.slowlog()).noneSatisfy(entry ->
                        assertThat(entry.command()).contains("before:the:run"));
                assertThat(runner.slowlog()).anySatisfy(entry ->
                        assertThat(entry.command()).contains("during:the:run"));
            }
        }
    }

    @Test
    void aServerThatLoggedNothingSaysWhyRatherThanShowingAnEmptyTable() {
        assumeTrue(DockerClientFactory.instance().isDockerAvailable(),
                "Docker is not available — Redis slowlog test skipped");

        try (GenericContainer<?> redis = redis("redis-server")) {
            redis.start();
            RedisConfig config = config(redis, List.of(
                    new QuerySpec("PING", 1, null, null)), 100);

            try (RedisRunner runner = RedisRunner.create(config, 4)) {
                runner.run(RunContext.startingNow(500 * MS, 100 * MS, 0, 4));
                runner.captureSlowlog(1);

                assertThat(runner.slowlog()).isEmpty();
                assertThat(runner.slowlogNote())
                        .contains("slowlog-log-slower-than")
                        .contains("10000");
            }
        }
    }

    @Test
    void noThresholdMeansTheServerIsNotQueriedAtAll() {
        assumeTrue(DockerClientFactory.instance().isDockerAvailable(),
                "Docker is not available — Redis slowlog test skipped");

        try (GenericContainer<?> redis = redis("redis-server", "--slowlog-log-slower-than", "0")) {
            redis.start();
            RedisConfig config = config(redis, List.of(
                    new QuerySpec("PING", 1, null, null)), 40);

            try (RedisRunner runner = RedisRunner.create(config, 2)) {
                runner.run(RunContext.startingNow(400 * MS, 100 * MS, 0, 2));
                runner.captureSlowlog(0);

                assertThat(runner.slowlog()).isEmpty();
                assertThat(runner.slowlogNote()).isNull();
            }
        }
    }

    @Test
    void aFastRunDoesNotDragTheServersLogIntoTheReport() {
        assumeTrue(DockerClientFactory.instance().isDockerAvailable(),
                "Docker is not available — Redis slowlog test skipped");

        try (GenericContainer<?> redis = redis("redis-server", "--slowlog-log-slower-than", "0")) {
            redis.start();
            RedisConfig config = config(redis, List.of(
                    new QuerySpec("PING", 1, null, null)), 100);

            try (RedisRunner runner = RedisRunner.create(config, 4)) {
                runner.run(RunContext.startingNow(500 * MS, 100 * MS, 0, 4));
                runner.captureSlowlog(SECOND);

                assertThat(runner.slowlog()).isEmpty();
                assertThat(runner.slowlogNote()).isNull();
            }
        }
    }
}
