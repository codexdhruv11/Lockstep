package com.lockstep.verify;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.lockstep.config.QuerySpec;
import com.lockstep.config.RedisConfig;
import com.lockstep.core.PacedLoop;
import com.lockstep.core.RunContext;
import com.lockstep.runner.redis.RedisRunner;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Checks the Redis runner's reported figures against a server whose behaviour is dictated.
 *
 * <p>Redis makes an unusually good oracle. It is single threaded, so {@code DEBUG SLEEP} blocks
 * the whole server for an exact duration — the latency is chosen, not observed. And its slowlog
 * threshold is settable at runtime, so which commands should and should not be recorded is known
 * before the run starts.
 *
 * <p>The slowlog is the interesting case. Lockstep reads it non-destructively, taking the newest
 * entry's id before the run and ignoring anything at or below it afterwards, because
 * {@code SLOWLOG RESET} would destroy evidence belonging to whoever else is watching the server.
 * That watermark has never been verified against a server that already had entries in its
 * slowlog, which is exactly the case it exists for.
 */
final class RedisOracleVerificationTest {
    private static final long MS = 1_000_000L;
    private static final long SECOND = 1_000_000_000L;

    private static final List<String> LEDGER = new ArrayList<>();

    private static void record(String claim, Object expected, Object reported, boolean ok) {
        LEDGER.add("  %-38s expected %-16s reported %-16s %s".formatted(
                claim, expected, reported, ok ? "MATCH" : "MISMATCH"));
    }

    private static void printLedger(String fixture) {
        System.out.println("\n=== oracle verification · " + fixture + " ===");
        LEDGER.forEach(System.out::println);
        LEDGER.clear();
    }

    /**
     * Redis 7 refuses DEBUG unless it is explicitly enabled, and "local" is not enough because
     * the runner connects through a mapped port rather than from inside the container.
     */
    private static GenericContainer<?> redis() {
        return new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
                .withExposedPorts(6379)
                .withCommand("redis-server", "--enable-debug-command", "yes");
    }

    private static String addr(GenericContainer<?> redis) {
        return redis.getHost() + ":" + redis.getMappedPort(6379);
    }

    /** Runs a redis-cli command inside the container and returns its output. */
    private static String cli(GenericContainer<?> redis, String... command) throws Exception {
        List<String> full = new ArrayList<>(List.of("redis-cli"));
        full.addAll(List.of(command));
        var result = redis.execInContainer(full.toArray(String[]::new));
        return result.getStdout().trim();
    }

    // --- a dictated command latency ---------------------------------------------------------------

    @Test
    void aCommandThatBlocksTheServerIsReportedAtThatDuration() throws Exception {
        assumeTrue(DockerClientFactory.instance().isDockerAvailable(), "Docker unavailable");

        final long sleepMillis = 150;
        try (GenericContainer<?> redis = redis()) {
            redis.start();

            // DEBUG SLEEP blocks Redis entirely for the duration, so every command's latency is
            // the sleep plus a round trip. One worker, so the sleeps cannot overlap.
            RedisConfig config = new RedisConfig(new RedisConfig.Target(addr(redis), 0, "",
                    List.of(new QuerySpec("DEBUG SLEEP 0.15", 1, "read", null))), 5);

            PacedLoop.LoopResult result;
            try (RedisRunner runner = RedisRunner.create(config, 1)) {
                result = runner.run(RunContext.startingNow(3 * SECOND, 500 * MS, 0, 1));
            }
            var summary = result.series().summarize("redis", 3 * SECOND);
            double p50 = summary.p50Nanos() / (double) MS;

            record("executed", "> 3", result.executedCount(), result.executedCount() > 3);
            record("errors", 0, result.series().errorCount(), result.series().errorCount() == 0);
            record("p50 (ms)", sleepMillis, "%.1f".formatted(p50),
                    p50 >= sleepMillis * 0.95 && p50 <= sleepMillis * 1.25);
            record("min >= sleep", ">= " + sleepMillis,
                    "%.1f".formatted(summary.minNanos() / (double) MS),
                    summary.minNanos() >= sleepMillis * MS * 0.95);
            printLedger("redis latency, DEBUG SLEEP " + sleepMillis + "ms");

            assertThat(result.series().errorCount())
                    .withFailMessage("DEBUG SLEEP is a valid command: %s", result.errorCounts())
                    .isZero();
            assertThat(p50)
                    .withFailMessage("""
                            the server blocks for %dms on every command, so the median must be \
                            %dms plus a round trip; the recorder says %.2fms""",
                            sleepMillis, sleepMillis, p50)
                    .isBetween(sleepMillis * 0.95, sleepMillis * 1.25);
            assertThat(summary.minNanos())
                    .withFailMessage("no command can return faster than the server's own sleep")
                    .isGreaterThanOrEqualTo((long) (sleepMillis * MS * 0.95));
        }
    }

    // --- the command breakdown, against known weights ---------------------------------------------

    @Test
    void theCommandBreakdownSeparatesTheConfiguredCommands() throws Exception {
        assumeTrue(DockerClientFactory.instance().isDockerAvailable(), "Docker unavailable");

        try (GenericContainer<?> redis = redis()) {
            redis.start();

            // One command sleeps, the other does not. Equal weights, so each should take about
            // half the operations, and their latencies must differ by the sleep.
            RedisConfig config = new RedisConfig(new RedisConfig.Target(addr(redis), 0, "",
                    List.of(new QuerySpec("DEBUG SLEEP 0.1", 1, "read", null),
                            new QuerySpec("PING", 1, "read", null))), 10);

            PacedLoop.LoopResult result;
            java.util.Map<String, com.lockstep.stats.BucketSeries> breakdown;
            try (RedisRunner runner = RedisRunner.create(config, 1)) {
                result = runner.run(RunContext.startingNow(4 * SECOND, 500 * MS, 0, 1));
                breakdown = runner.commandBreakdown();
            }

            var sleepEntry = breakdown.entrySet().stream()
                    .filter(entry -> entry.getKey().toUpperCase().contains("DEBUG"))
                    .findFirst().orElseThrow(() -> new AssertionError(
                            "DEBUG SLEEP must appear in the breakdown; got " + breakdown.keySet()));
            var pingEntry = breakdown.entrySet().stream()
                    .filter(entry -> entry.getKey().toUpperCase().contains("PING"))
                    .findFirst().orElseThrow(() -> new AssertionError(
                            "PING must appear in the breakdown; got " + breakdown.keySet()));

            long sleepCount = sleepEntry.getValue().totalCount();
            long pingCount = pingEntry.getValue().totalCount();
            long sleepP50 = sleepEntry.getValue().mergedServiceTime().getValueAtPercentile(50);
            long pingP50 = pingEntry.getValue().mergedServiceTime().getValueAtPercentile(50);

            record("commands in breakdown", 2, breakdown.size(), breakdown.size() == 2);
            record("counts sum to executed", result.executedCount(), sleepCount + pingCount,
                    sleepCount + pingCount == result.executedCount());
            record("DEBUG SLEEP service p50", "~100ms", "%.1fms".formatted(sleepP50 / (double) MS),
                    sleepP50 >= 95 * MS);
            record("PING service p50", "< 20ms", "%.1fms".formatted(pingP50 / (double) MS),
                    pingP50 < 20 * MS);
            printLedger("redis command breakdown, one slow command and one fast");

            assertThat(sleepCount + pingCount)
                    .withFailMessage("""
                            every executed operation belongs to exactly one command, so the \
                            per-command counts must sum to the total: %d + %d against %d""",
                            sleepCount, pingCount, result.executedCount())
                    .isEqualTo(result.executedCount());
            assertThat(sleepP50)
                    .withFailMessage("""
                            DEBUG SLEEP 0.1 blocks for 100ms, so its own service time must show \
                            that; got %.2fms""", sleepP50 / (double) MS)
                    .isGreaterThanOrEqualTo(95 * MS);
            assertThat(pingP50)
                    .withFailMessage("""
                            PING does no work, so attributing the sleep to it would mean the \
                            per-command split is wrong; got %.2fms""", pingP50 / (double) MS)
                    .isLessThan(20 * MS);
        }
    }

    // --- the slowlog, and its watermark -----------------------------------------------------------

    @Test
    void theSlowlogReportsThisRunsEntriesAndNotWhatWasThereBefore() throws Exception {
        assumeTrue(DockerClientFactory.instance().isDockerAvailable(), "Docker unavailable");

        try (GenericContainer<?> redis = redis()) {
            redis.start();

            // Log anything over 50ms, then put an entry in the slowlog BEFORE the run. That entry
            // belongs to someone else and must not be attributed to this run.
            cli(redis, "CONFIG", "SET", "slowlog-log-slower-than", "50000");
            cli(redis, "DEBUG", "SLEEP", "0.3");
            long entriesBefore = Long.parseLong(cli(redis, "SLOWLOG", "LEN"));

            RedisConfig config = new RedisConfig(new RedisConfig.Target(addr(redis), 0, "",
                    List.of(new QuerySpec("DEBUG SLEEP 0.12", 1, "read", null))), 4);

            List<RedisRunner.SlowlogEntry> captured;
            long executed;
            try (RedisRunner runner = RedisRunner.create(config, 1)) {
                executed = runner.run(RunContext.startingNow(3 * SECOND, 500 * MS, 0, 1))
                        .executedCount();
                runner.captureSlowlog(50 * MS);
                captured = runner.slowlog();
            }

            long entriesAfter = Long.parseLong(cli(redis, "SLOWLOG", "LEN"));
            long addedByRun = entriesAfter - entriesBefore;

            boolean noneTooFast = captured.stream().allMatch(entry -> entry.durationMicros() >= 50_000);
            boolean noPreexisting = captured.stream()
                    .noneMatch(entry -> entry.durationMicros() >= 250_000);

            record("entries present before run", ">= 1", entriesBefore, entriesBefore >= 1);
            record("entries added by run", ">= " + executed, addedByRun, addedByRun >= executed);
            record("captured entries", "> 0", captured.size(), !captured.isEmpty());
            record("all captured above threshold", "yes", noneTooFast ? "yes" : "no", noneTooFast);
            record("pre-run 300ms entry excluded", "yes", noPreexisting ? "yes" : "no",
                    noPreexisting);
            record("slowlog not reset", entriesAfter + " kept", entriesAfter,
                    entriesAfter > entriesBefore);
            printLedger("redis slowlog, one pre-existing entry and a watermark");

            assertThat(entriesBefore)
                    .withFailMessage("the fixture needs a pre-existing entry to test exclusion")
                    .isGreaterThanOrEqualTo(1);
            assertThat(captured)
                    .withFailMessage("the run issued %d commands of 120ms against a 50ms "
                            + "threshold, so the slowlog must have entries to report", executed)
                    .isNotEmpty();
            assertThat(noneTooFast)
                    .withFailMessage("every captured entry must be above the server's own "
                            + "threshold: %s", captured)
                    .isTrue();
            assertThat(noPreexisting)
                    .withFailMessage("""
                            the 300ms entry logged before the run belongs to whoever was using \
                            the server beforehand. Reporting it as this run's would attribute \
                            someone else's slow command to us: %s""", captured)
                    .isTrue();
            assertThat(entriesAfter)
                    .withFailMessage("""
                            reading the slowlog must not destroy it — SLOWLOG RESET would throw \
                            away evidence belonging to anything else watching this server""")
                    .isGreaterThan(entriesBefore);
        }
    }

    @Test
    void anEmptySlowlogSaysTheWaitWasNotSpentExecuting() throws Exception {
        assumeTrue(DockerClientFactory.instance().isDockerAvailable(), "Docker unavailable");

        try (GenericContainer<?> redis = redis()) {
            redis.start();
            // Threshold far above anything the run will do, so nothing is logged even though the
            // commands are slow by Lockstep's reckoning.
            cli(redis, "CONFIG", "SET", "slowlog-log-slower-than", "5000000");

            RedisConfig config = new RedisConfig(new RedisConfig.Target(addr(redis), 0, "",
                    List.of(new QuerySpec("DEBUG SLEEP 0.1", 1, "read", null))), 4);

            List<RedisRunner.SlowlogEntry> captured;
            String note;
            try (RedisRunner runner = RedisRunner.create(config, 1)) {
                runner.run(RunContext.startingNow(2 * SECOND, 500 * MS, 0, 1));
                runner.captureSlowlog(10 * MS);
                captured = runner.slowlog();
                note = runner.slowlogNote();
            }

            record("captured entries", 0, captured.size(), captured.isEmpty());
            record("explanation present", "yes", note == null ? "no" : "yes", note != null);
            record("names the server threshold", "yes",
                    note != null && note.contains("5000000") ? "yes" : "no",
                    note != null && note.contains("5000000"));
            printLedger("redis slowlog, server threshold above the run's own");

            assertThat(captured).isEmpty();
            assertThat(note)
                    .withFailMessage("""
                            the commands took 100ms and Lockstep's threshold was 10ms, but the \
                            server only logs above 5s. An empty slowlog here means the server \
                            chose not to record, not that the commands were fast, and the report \
                            has to distinguish those""")
                    .isNotNull()
                    .contains("5000000");
        }
    }
}
