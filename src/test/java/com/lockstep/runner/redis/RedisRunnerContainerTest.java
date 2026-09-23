package com.lockstep.runner.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.lockstep.config.QuerySpec;
import com.lockstep.config.RedisConfig;
import com.lockstep.core.PacedLoop;
import com.lockstep.core.RunContext;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

final class RedisRunnerContainerTest {
    private static final long MS = 1_000_000L;
    private static final long SECOND = 1_000_000_000L;

    @Test
    void runsAMixedCommandWorkloadAgainstRealRedis() {
        assumeTrue(DockerClientFactory.instance().isDockerAvailable(),
                "Docker is not available — Redis container test skipped");

        try (GenericContainer<?> redis =
                new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379)) {
            redis.start();

            String addr = redis.getHost() + ":" + redis.getFirstMappedPort();
            RedisConfig config = new RedisConfig(new RedisConfig.Target(addr, 0, "", List.of(
                    new QuerySpec("PING", 40, null, null),
                    new QuerySpec("SET sess:loadtest ok", 30, null, null),
                    new QuerySpec("GET sess:loadtest", 20, null, null),
                    new QuerySpec("INCR hits", 10, null, null))), 300);

            try (RedisRunner runner = RedisRunner.create(config, 8)) {
                PacedLoop.LoopResult result = runner.run(
                        RunContext.startingNow(2 * SECOND, 200 * MS, 0, 8));

                assertThat(result.executedCount()).isGreaterThan(200);
                assertThat(result.series().errorCount())
                        .withFailMessage("expected no errors, last failure was: %s", runner.lastFailure())
                        .isZero();
                assertThat(result.series().nonEmptyBuckets()).hasSizeGreaterThan(5);
            }
        }
    }
}
