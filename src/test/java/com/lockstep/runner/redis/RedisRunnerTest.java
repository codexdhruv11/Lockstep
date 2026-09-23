package com.lockstep.runner.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.lockstep.config.QuerySpec;
import com.lockstep.config.RedisConfig;
import com.lockstep.core.PacedLoop;
import com.lockstep.core.RunContext;
import java.util.List;
import org.junit.jupiter.api.Test;

final class RedisRunnerTest {
    private static final long MS = 1_000_000L;
    private static final long SECOND = 1_000_000_000L;

    private static RedisConfig config(int port, int rate, List<QuerySpec> queries) {
        return new RedisConfig(new RedisConfig.Target("127.0.0.1:" + port, 0, "", queries), rate);
    }

    private static QuerySpec command(String text, int weight) {
        return new QuerySpec(text, weight, null, null);
    }

    @Test
    void runsAMixedCommandWorkloadAndRecordsLatencies() throws Exception {
        try (StubRedisServer server = new StubRedisServer()) {
            RedisConfig config = config(server.port(), 200, List.of(
                    command("PING", 50),
                    command("SET sess:loadtest ok", 30),
                    command("GET sess:loadtest", 20)));

            try (RedisRunner runner = RedisRunner.create(config, 8)) {
                PacedLoop.LoopResult result = runner.run(
                        RunContext.startingNow(SECOND, 200 * MS, 0, 8));

                assertThat(result.executedCount()).isGreaterThan(100);
                assertThat(result.series().errorCount())
                        .withFailMessage("expected no errors, last failure was: %s", runner.lastFailure())
                        .isZero();
                assertThat(result.series().summarize("redis", SECOND).p99Nanos()).isGreaterThan(0);

                assertThat(server.commandsReceived()).isGreaterThanOrEqualTo(result.executedCount());
            }
        }
    }

    @Test
    void everyReplyTypeDecodesWithoutError() throws Exception {
        try (StubRedisServer server = new StubRedisServer()) {
            RedisConfig config = config(server.port(), 100, List.of(
                    command("PING", 1),
                    command("GET k", 1),
                    command("INCR counter", 1),
                    command("HGETALL h", 1),
                    command("HGET h missing", 1)));

            try (RedisRunner runner = RedisRunner.create(config, 4)) {
                PacedLoop.LoopResult result = runner.run(
                        RunContext.startingNow(SECOND, 200 * MS, 0, 4));

                assertThat(result.executedCount()).isGreaterThan(50);
                assertThat(result.series().errorCount()).isZero();
                assertThat(server.seenCommands()).anyMatch(c -> c.startsWith("HGETALL"));
            }
        }
    }

    @Test
    void argumentsArriveAsSeparateTokensNotOneString() throws Exception {
        try (StubRedisServer server = new StubRedisServer()) {
            RedisConfig config = config(server.port(), 40,
                    List.of(command("SET greeting \"hello world\"", 1)));

            try (RedisRunner runner = RedisRunner.create(config, 2)) {
                runner.run(RunContext.startingNow(400 * MS, 100 * MS, 0, 2));
            }

            assertThat(server.seenCommands()).contains("SET greeting hello world");
        }
    }

    @Test
    void serverErrorsAreRecordedAsFailedOperationsNotThrown() throws Exception {
        try (StubRedisServer server = new StubRedisServer()) {
            RedisConfig config = config(server.port(), 40, List.of(command("BROKEN", 1)));

            try (RedisRunner runner = RedisRunner.create(config, 2)) {
                PacedLoop.LoopResult result = runner.run(
                        RunContext.startingNow(500 * MS, 100 * MS, 0, 2));

                assertThat(result.executedCount()).isGreaterThan(0);
                assertThat(result.series().errorCount()).isEqualTo(result.executedCount());
                assertThat(result.drainedCleanly()).isTrue();
            }
        }
    }

    @Test
    void anUnreachableServerFailsAtCreation() {
        RedisConfig config = config(1, 10, List.of(command("PING", 1)));
        assertThatThrownBy(() -> RedisRunner.create(config, 2))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    void aMalformedCommandFailsAtStartupNotOncePerOperation() throws Exception {
        try (StubRedisServer server = new StubRedisServer()) {
            RedisConfig config = config(server.port(), 10, List.of(command("SET x \"unterminated", 1)));

            assertThatThrownBy(() -> RedisRunner.create(config, 2))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("unterminated quote");
        }
    }

    @Test
    void addressParsingHandlesHostPortAndDefaults() {
        assertThat(RedisRunner.hostOf("localhost:6379")).isEqualTo("localhost");
        assertThat(RedisRunner.portOf("localhost:6379")).isEqualTo(6379);
        assertThat(RedisRunner.hostOf("10.0.0.5:7000")).isEqualTo("10.0.0.5");
        assertThat(RedisRunner.portOf("10.0.0.5:7000")).isEqualTo(7000);
        assertThat(RedisRunner.hostOf("redis-host")).isEqualTo("redis-host");
        assertThat(RedisRunner.portOf("redis-host")).isEqualTo(6379);
        assertThatThrownBy(() -> RedisRunner.portOf("host:abc"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("non-numeric port");
    }
}
