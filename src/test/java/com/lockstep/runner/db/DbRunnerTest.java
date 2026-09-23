package com.lockstep.runner.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.lockstep.config.DbConfig;
import com.lockstep.config.QuerySpec;
import com.lockstep.core.PacedLoop;
import com.lockstep.core.RunContext;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class DbRunnerTest {
    private static final long MS = 1_000_000L;

    @TempDir
    Path tempDir;

    private DbConfig sqliteConfig(List<QuerySpec> queries, int rate) {
        String file = tempDir.resolve("load.db").toString();
        return new DbConfig(new DbConfig.Target(file, "sqlite", queries), rate);
    }

    private void createTable(String file) throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + file);
                Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE orders (id INTEGER PRIMARY KEY, customer TEXT, amount INTEGER)");
            statement.execute("INSERT INTO orders (customer, amount) VALUES ('seed', 1)");
        }
    }

    private long rowCount(String file) throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + file);
                Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery("SELECT count(*) FROM orders")) {
            rows.next();
            return rows.getLong(1);
        }
    }

    @Test
    void runsAMixedReadWriteWorkloadAndRecordsRealLatencies() throws Exception {
        String file = tempDir.resolve("load.db").toString();
        createTable(file);

        DbConfig config = sqliteConfig(List.of(
                new QuerySpec("SELECT count(*) FROM orders", 50, "read", null),
                new QuerySpec("INSERT INTO orders (customer, amount) VALUES ('load', 1)", 50, "write", null)),
                20);

        try (DbRunner runner = DbRunner.create(config, 1)) {
            PacedLoop.LoopResult result = runner.run(
                    RunContext.startingNow(SECOND(), 200 * MS, 0, 1));

            assertThat(result.executedCount()).isGreaterThan(3);
            assertThat(result.executedCount() + result.shedCount()).isEqualTo(result.scheduledCount());
            assertThat(result.series().errorCount()).isZero();

            assertThat(rowCount(file)).isGreaterThan(1);

            assertThat(result.series().summarize("db", SECOND()).p99Nanos()).isGreaterThan(0);
        }
    }

    @Test
    void writesGoThroughExecuteUpdateAndReadsThroughExecuteQuery() throws Exception {
        String file = tempDir.resolve("load.db").toString();
        createTable(file);
        long before = rowCount(file);

        DbConfig config = sqliteConfig(List.of(
                new QuerySpec("INSERT INTO orders (customer, amount) VALUES ('w', 2)", 1, "write", null)),
                40);

        try (DbRunner runner = DbRunner.create(config, 1)) {
            PacedLoop.LoopResult result = runner.run(RunContext.startingNow(500 * MS, 100 * MS, 0, 1));
            assertThat(result.series().errorCount()).isZero();
            assertThat(rowCount(file)).isGreaterThan(before);

            assertThat(rowCount(file) - before).isEqualTo(result.executedCount());
        }
    }

    @Test
    void theTypeFieldOverridesTheHeuristic() throws Exception {
        String file = tempDir.resolve("load.db").toString();
        createTable(file);

        DbConfig config = sqliteConfig(List.of(
                new QuerySpec("SELECT count(*) FROM orders", 1, "write", null)), 20);

        try (DbRunner runner = DbRunner.create(config, 2)) {
            PacedLoop.LoopResult result = runner.run(RunContext.startingNow(400 * MS, 100 * MS, 0, 2));
            assertThat(result.executedCount()).isGreaterThan(0);
            assertThat(result.series().errorCount()).isEqualTo(result.executedCount());
        }
    }

    @Test
    void aBrokenQueryIsRecordedAsAFailedOperationNotAnException() throws Exception {
        String file = tempDir.resolve("load.db").toString();
        createTable(file);

        DbConfig config = sqliteConfig(List.of(
                new QuerySpec("SELECT * FROM table_that_does_not_exist", 1, "read", null)), 20);

        try (DbRunner runner = DbRunner.create(config, 2)) {
            PacedLoop.LoopResult result = runner.run(RunContext.startingNow(400 * MS, 100 * MS, 0, 2));
            assertThat(result.executedCount()).isGreaterThan(0);
            assertThat(result.series().errorCount()).isEqualTo(result.executedCount());

            assertThat(result.drainedCleanly()).isTrue();
        }
    }

    @Test
    void concurrentReadsShareThePoolWithoutErrors() throws Exception {
        String file = tempDir.resolve("load.db").toString();
        createTable(file);

        DbConfig config = sqliteConfig(List.of(
                new QuerySpec("SELECT count(*) FROM orders", 1, "read", null)), 200);

        try (DbRunner runner = DbRunner.create(config, 8)) {
            PacedLoop.LoopResult result = runner.run(RunContext.startingNow(SECOND(), 200 * MS, 0, 8));

            assertThat(result.executedCount()).isGreaterThan(100);
            assertThat(result.series().errorCount()).isZero();

            assertThat(runner.maxConnectionWaitNanos()).isLessThan(500 * MS);
        }
    }

    @Test
    void boundArgumentsReachTheDatabase() throws Exception {
        String file = tempDir.resolve("load.db").toString();
        createTable(file);

        DbConfig config = sqliteConfig(List.of(
                new QuerySpec("INSERT INTO orders (customer, amount) VALUES (?, ?)", 1, "write",
                        List.of("bound", 42))), 20);

        try (DbRunner runner = DbRunner.create(config, 1)) {
            runner.run(RunContext.startingNow(400 * MS, 100 * MS, 0, 1));
        }

        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + file);
                Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery(
                        "SELECT count(*) FROM orders WHERE customer = 'bound' AND amount = 42")) {
            rows.next();
            assertThat(rows.getLong(1)).isGreaterThan(0);
        }
    }

    @Test
    void connectionWaitIsMeasuredSoPoolExhaustionIsDistinguishableFromASlowDatabase() throws Exception {
        String file = tempDir.resolve("load.db").toString();
        createTable(file);

        DbConfig config = sqliteConfig(List.of(
                new QuerySpec("SELECT count(*) FROM orders", 1, "read", null)), 40);

        try (DbRunner runner = DbRunner.create(config, 2)) {
            PacedLoop.LoopResult result = runner.run(RunContext.startingNow(500 * MS, 100 * MS, 0, 2));
            assertThat(result.executedCount()).isGreaterThan(0);

            assertThat(runner.maxConnectionWaitNanos()).isGreaterThanOrEqualTo(0);
            assertThat(runner.meanConnectionWaitNanos(result.executedCount()))
                    .isLessThan(result.series().summarize("db", 500 * MS).p99Nanos() + 1);
        }
    }

    @Test
    void anUnreachableTargetFailsAtCreationNotAfterAFullRunOfErrors() {
        DbConfig config = new DbConfig(new DbConfig.Target(
                "postgres://nobody:nothing@127.0.0.1:1/none", "postgres",
                List.of(new QuerySpec("SELECT 1", 1, "read", null))), 10);

        assertThatThrownBy(() -> DbRunner.create(config, 2))
                .isInstanceOf(RuntimeException.class);
    }

    private static long SECOND() {
        return 1_000_000_000L;
    }

    @Test
    void weightedPickingFollowsTheConfiguredWeights() {
        QueryPicker picker = new QueryPicker(List.of(
                new QuerySpec("A", 70, "read", null),
                new QuerySpec("B", 20, "read", null),
                new QuerySpec("C", 10, "read", null)));

        Map<String, Integer> picks = new HashMap<>();
        for (int i = 0; i < 100_000; i++) {
            picks.merge(picker.pick().query(), 1, Integer::sum);
        }

        assertThat(picks.get("A") / 1000.0).isBetween(68.0, 72.0);
        assertThat(picks.get("B") / 1000.0).isBetween(18.0, 22.0);
        assertThat(picks.get("C") / 1000.0).isBetween(8.0, 12.0);
    }

    @Test
    void zeroWeightQueriesAreNeverPicked() {
        QueryPicker picker = new QueryPicker(List.of(
                new QuerySpec("never", 0, "read", null),
                new QuerySpec("always", 5, "read", null)));

        for (int i = 0; i < 5_000; i++) {
            assertThat(picker.pick().query()).isEqualTo("always");
        }
    }

    @Test
    void singleQueryIsAlwaysPicked() {
        QueryPicker picker = new QueryPicker(List.of(new QuerySpec("only", 3, "read", null)));
        assertThat(picker.pick().query()).isEqualTo("only");
        assertThat(picker.totalWeight()).isEqualTo(3);
    }
}
