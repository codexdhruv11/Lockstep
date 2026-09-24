package com.lockstep.runner.db;

import static org.assertj.core.api.Assertions.assertThat;

import com.lockstep.config.DbConfig;
import com.lockstep.config.QuerySpec;
import com.lockstep.core.PacedLoop;
import com.lockstep.core.RunContext;
import com.lockstep.runner.QueryLabels;
import com.lockstep.stats.BucketSeries;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class QueryBreakdownTest {
    private static final long MS = 1_000_000L;
    private static final long SECOND = 1_000_000_000L;

    @TempDir
    Path tempDir;

    private String database() throws Exception {
        String file = tempDir.resolve("load.db").toString();
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + file);
                Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE orders (id INTEGER PRIMARY KEY, customer TEXT, amount INTEGER)");
            statement.execute("INSERT INTO orders (customer, amount) VALUES ('seed', 1)");
        }
        return file;
    }

    private DbConfig config(String file, List<QuerySpec> queries, int rate) {
        return new DbConfig(new DbConfig.Target(file, "sqlite", queries), rate);
    }

    private static String expensiveSelect(int rows) {
        return "WITH RECURSIVE c(x) AS (SELECT 1 UNION ALL SELECT x+1 FROM c WHERE x < " + rows + ")"
                + " SELECT count(*) FROM c";
    }

    @Test
    void everyOperationIsAttributedToExactlyOneQuery() throws Exception {
        String file = database();
        DbConfig config = config(file, List.of(
                new QuerySpec("SELECT count(*) FROM orders", 50, "read", null),
                new QuerySpec("SELECT max(amount) FROM orders", 50, "read", null)), 200);

        try (DbRunner runner = DbRunner.create(config, 4)) {
            PacedLoop.LoopResult result = runner.run(RunContext.startingNow(SECOND, 200 * MS, 0, 4));

            Map<String, BucketSeries> breakdown = runner.queryBreakdown();
            assertThat(breakdown).hasSize(2);

            long attributed = breakdown.values().stream().mapToLong(BucketSeries::totalCount).sum();
            assertThat(attributed)
                    .withFailMessage("per-query counts must account for every recorded operation: "
                            + "runner saw %d, queries account for %d",
                            result.executedCount(), attributed)
                    .isEqualTo(result.executedCount());
        }
    }

    @Test
    void theExpensiveQueryIsIdentifiableEvenThoughTheRunnerRowIsOneNumber() throws Exception {
        String file = database();

        DbConfig config = config(file, List.of(
                new QuerySpec("SELECT count(*) FROM orders", 50, "read", null),
                new QuerySpec(expensiveSelect(120_000), 50, "read", null)), 60);

        try (DbRunner runner = DbRunner.create(config, 4)) {
            runner.run(RunContext.startingNow(2 * SECOND, 200 * MS, 0, 4));

            Map<String, BucketSeries> breakdown = runner.queryBreakdown();
            assertThat(breakdown).hasSize(2);

            BucketSeries cheap = breakdown.get(QueryLabels.of(0, "SELECT count(*) FROM orders"));
            BucketSeries expensive = breakdown.get(QueryLabels.of(1, expensiveSelect(120_000)));
            assertThat(cheap).isNotNull();
            assertThat(expensive).isNotNull();

            long cheapP50 = cheap.summarize("cheap", 2 * SECOND).p50Nanos();
            long expensiveP50 = expensive.summarize("expensive", 2 * SECOND).p50Nanos();
            assertThat(expensiveP50)
                    .withFailMessage("the recursive CTE must be visibly slower than a one-row "
                            + "count; got %dns against %dns", expensiveP50, cheapP50)
                    .isGreaterThan(cheapP50 * 5);
        }
    }

    @Test
    void aQueryThatIsNeverPickedProducesNoRow() throws Exception {
        String file = database();

        DbConfig config = config(file, List.of(
                new QuerySpec("SELECT count(*) FROM orders", 10, "read", null),
                new QuerySpec("SELECT max(amount) FROM orders", 0, "read", null)), 100);

        try (DbRunner runner = DbRunner.create(config, 2)) {
            runner.run(RunContext.startingNow(500 * MS, 100 * MS, 0, 2));

            assertThat(runner.queryBreakdown()).hasSize(1);
            assertThat(runner.queryBreakdown()).containsOnlyKeys(
                    QueryLabels.of(0, "SELECT count(*) FROM orders"));
        }
    }

    @Test
    void failuresAreAttributedToTheQueryThatFailed() throws Exception {
        String file = database();
        DbConfig config = config(file, List.of(
                new QuerySpec("SELECT count(*) FROM orders", 50, "read", null),
                new QuerySpec("SELECT * FROM table_that_does_not_exist", 50, "read", null)), 100);

        try (DbRunner runner = DbRunner.create(config, 2)) {
            PacedLoop.LoopResult result = runner.run(RunContext.startingNow(SECOND, 200 * MS, 0, 2));

            Map<String, BucketSeries> breakdown = runner.queryBreakdown();
            BucketSeries good = breakdown.get(QueryLabels.of(0, "SELECT count(*) FROM orders"));
            BucketSeries bad = breakdown.get(QueryLabels.of(1, "SELECT * FROM table_that_does_not_exist"));

            assertThat(good.errorCount()).isZero();
            assertThat(bad.errorCount()).isEqualTo(bad.totalCount());
            assertThat(good.errorCount() + bad.errorCount()).isEqualTo(result.series().errorCount());
        }
    }

    @Test
    void identicalStatementsAtDifferentWeightsStayTwoRows() throws Exception {
        String file = database();

        String sql = "SELECT count(*) FROM orders";
        DbConfig config = config(file, List.of(
                new QuerySpec(sql, 90, "read", null),
                new QuerySpec(sql, 10, "read", null)), 200);

        try (DbRunner runner = DbRunner.create(config, 4)) {
            runner.run(RunContext.startingNow(SECOND, 200 * MS, 0, 4));

            Map<String, BucketSeries> breakdown = runner.queryBreakdown();
            assertThat(breakdown).hasSize(2);
            BucketSeries heavy = breakdown.get(QueryLabels.of(0, sql));
            BucketSeries light = breakdown.get(QueryLabels.of(1, sql));
            assertThat(heavy.totalCount()).isGreaterThan(light.totalCount());
        }
    }

    @Test
    void thereIsNoBreakdownBeforeARunStarts() throws Exception {
        String file = database();
        DbConfig config = config(file, List.of(
                new QuerySpec("SELECT count(*) FROM orders", 1, "read", null)), 10);

        try (DbRunner runner = DbRunner.create(config, 1)) {
            assertThat(runner.queryBreakdown()).isEmpty();
        }
    }

    @Test
    void aSecondRunDoesNotCarryTheFirstRunsFigures() throws Exception {
        String file = database();
        DbConfig config = config(file, List.of(
                new QuerySpec("SELECT count(*) FROM orders", 50, "read", null),
                new QuerySpec("SELECT max(amount) FROM orders", 50, "read", null)), 200);

        try (DbRunner runner = DbRunner.create(config, 4)) {
            runner.run(RunContext.startingNow(SECOND, 200 * MS, 0, 4));
            long first = runner.queryBreakdown().values().stream()
                    .mapToLong(BucketSeries::totalCount).sum();

            PacedLoop.LoopResult second = runner.run(RunContext.startingNow(500 * MS, 100 * MS, 0, 4));
            long attributed = runner.queryBreakdown().values().stream()
                    .mapToLong(BucketSeries::totalCount).sum();

            assertThat(first).isGreaterThan(0);
            assertThat(attributed)
                    .withFailMessage("the second run's breakdown must describe the second run, "
                            + "not both: runner saw %d, queries account for %d",
                            second.executedCount(), attributed)
                    .isEqualTo(second.executedCount());
        }
    }
}
