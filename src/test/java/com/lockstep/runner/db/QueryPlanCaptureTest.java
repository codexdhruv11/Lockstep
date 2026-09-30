package com.lockstep.runner.db;

import static org.assertj.core.api.Assertions.assertThat;

import com.lockstep.config.DbConfig;
import com.lockstep.config.QuerySpec;
import com.lockstep.core.RunContext;
import com.lockstep.runner.QueryLabels;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class QueryPlanCaptureTest {
    private static final long MS = 1_000_000L;
    private static final long SECOND = 1_000_000_000L;

    @TempDir
    Path tempDir;

    private String database() throws Exception {
        String file = tempDir.resolve("load.db").toString();
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + file);
                Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE orders (id INTEGER PRIMARY KEY, customer TEXT, amount INTEGER)");

            statement.execute("INSERT INTO orders (customer, amount) VALUES "
                    + "('seed', 1), ('seed', 2), ('seed', 3), ('seed', 42)");
        }
        return file;
    }

    private DbConfig config(String file, List<QuerySpec> queries, int rate) {
        return new DbConfig(new DbConfig.Target(file, "sqlite", queries), rate);
    }

    private long rowCount(String file) throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + file);
                Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery("SELECT count(*) FROM orders")) {
            rows.next();
            return rows.getLong(1);
        }
    }

    private static String slowSelect() {
        return "WITH RECURSIVE c(x) AS (SELECT 1 UNION ALL SELECT x+1 FROM c WHERE x < 150000)"
                + " SELECT count(*) FROM c";
    }

    @Test
    void onlyTheQueriesThatCrossedTheThresholdGetAPlan() throws Exception {
        String file = database();
        DbConfig config = config(file, List.of(
                new QuerySpec("SELECT count(*) FROM orders", 50, "read", null),
                new QuerySpec(slowSelect(), 50, "read", null)), 40);

        try (DbRunner runner = DbRunner.create(config, 4)) {
            runner.run(RunContext.startingNow(2 * SECOND, 200 * MS, 0, 4));
            runner.capturePlans(20 * MS);

            Map<String, DbRunner.QueryPlan> plans = runner.plans();

            assertThat(plans).containsOnlyKeys(QueryLabels.of(1, slowSelect()));
            DbRunner.QueryPlan plan = plans.get(QueryLabels.of(1, slowSelect()));
            assertThat(plan.failure()).isNull();
            assertThat(plan.plan()).isNotBlank();
            assertThat(plan.statement()).contains("EXPLAIN QUERY PLAN");
        }
    }

    @Test
    void aWriteIsExplainedWithoutBeingRunAgain() throws Exception {
        String file = database();
        DbConfig config = config(file, List.of(
                new QuerySpec("INSERT INTO orders (customer, amount) VALUES ('load', 1)", 1, "write", null)),
                20);

        try (DbRunner runner = DbRunner.create(config, 1)) {
            runner.run(RunContext.startingNow(400 * MS, 100 * MS, 0, 1));
            long afterRun = rowCount(file);

            runner.capturePlans(1);
            assertThat(runner.plans()).hasSize(1);
            assertThat(runner.plans().values().iterator().next().executed())
                    .withFailMessage("a write's plan must be reported as estimated, never measured")
                    .isFalse();

            assertThat(rowCount(file))
                    .withFailMessage("explaining a write must not insert another row")
                    .isEqualTo(afterRun);
        }
    }

    @Test
    void aPlanThatCannotBeTakenIsRecordedNotThrown() throws Exception {
        String file = database();

        DbConfig config = config(file, List.of(
                new QuerySpec("SELECT * FROM table_that_does_not_exist", 1, "read", null)), 20);

        try (DbRunner runner = DbRunner.create(config, 2)) {
            var result = runner.run(RunContext.startingNow(400 * MS, 100 * MS, 0, 2));
            runner.capturePlans(1);

            DbRunner.QueryPlan plan = runner.plans().values().iterator().next();
            assertThat(plan.plan()).isNull();
            assertThat(plan.failure()).contains("table_that_does_not_exist");

            assertThat(result.executedCount()).isGreaterThan(0);
        }
    }

    @Test
    void boundArgumentsAreBoundForTheExplainToo() throws Exception {
        String file = database();

        DbConfig config = config(file, List.of(
                new QuerySpec("SELECT count(*) FROM orders WHERE amount > ? AND customer = ?",
                        1, "read", List.of(10, "seed"))), 20);

        try (DbRunner runner = DbRunner.create(config, 1)) {
            runner.run(RunContext.startingNow(400 * MS, 100 * MS, 0, 1));
            runner.capturePlans(1);

            DbRunner.QueryPlan plan = runner.plans().values().iterator().next();
            assertThat(plan.failure()).isNull();
            assertThat(plan.plan()).isNotBlank();
        }
    }

    @Test
    void noThresholdMeansNoPlansAndNoExtraWork() throws Exception {
        String file = database();
        DbConfig config = config(file, List.of(
                new QuerySpec("SELECT count(*) FROM orders", 1, "read", null)), 20);

        try (DbRunner runner = DbRunner.create(config, 1)) {
            runner.run(RunContext.startingNow(400 * MS, 100 * MS, 0, 1));
            runner.capturePlans(0);
            assertThat(runner.plans()).isEmpty();
        }
    }

    @Test
    void aSecondCaptureReplacesTheFirstRatherThanAccumulating() throws Exception {
        String file = database();
        DbConfig config = config(file, List.of(
                new QuerySpec("SELECT count(*) FROM orders", 50, "read", null),
                new QuerySpec(slowSelect(), 50, "read", null)), 40);

        try (DbRunner runner = DbRunner.create(config, 4)) {
            runner.run(RunContext.startingNow(2 * SECOND, 200 * MS, 0, 4));
            runner.capturePlans(1);
            assertThat(runner.plans()).hasSize(2);

            runner.capturePlans(20 * MS);
            assertThat(runner.plans()).hasSize(1);
        }
    }

    @Test
    void atMostFivePlansAreTakenAndTheyAreTheOnesOwningTheMostTime() throws Exception {
        String file = database();

        List<QuerySpec> queries = new java.util.ArrayList<>();
        queries.add(new QuerySpec(slowSelect(), 60, "read", null));
        for (int i = 0; i < 6; i++) {
            queries.add(new QuerySpec("SELECT count(*) FROM orders WHERE amount > " + i, 10, "read", null));
        }

        try (DbRunner runner = DbRunner.create(config(file, queries, 60), 4)) {
            runner.run(RunContext.startingNow(2 * SECOND, 200 * MS, 0, 4));
            runner.capturePlans(1);

            assertThat(runner.plans()).hasSize(5);
            assertThat(runner.plansSkipped()).isEqualTo(2);
            assertThat(runner.plans())
                    .withFailMessage("the cap must drop the cheapest queries, never the one that "
                            + "owns the database")
                    .containsKey(QueryLabels.of(0, slowSelect()));
        }
    }

    @Test
    void anUnknownDriverSaysSoInsteadOfGuessingASyntax() {
        assertThat(ExplainDialect.forQuery("oracle", "SELECT 1", true)).isNull();

        var postgresRead = ExplainDialect.forQuery("postgresql", "SELECT 1", true);
        assertThat(postgresRead.sql()).isEqualTo("EXPLAIN (ANALYZE, BUFFERS) SELECT 1");
        assertThat(postgresRead.executes()).isTrue();

        var postgresWrite = ExplainDialect.forQuery("postgresql", "INSERT INTO t VALUES (1)", false);
        assertThat(postgresWrite.sql()).isEqualTo("EXPLAIN INSERT INTO t VALUES (1)");
        assertThat(postgresWrite.executes()).isFalse();

        assertThat(ExplainDialect.forQuery("sqlite", "SELECT 1", true).executes()).isFalse();
    }

    @Test
    void theMysqlFormsAreWhatTheCodeIntendsToSend() {
        // MySQL had no coverage at all, not even this. It remains unverified against a running
        // MySQL server — see ExplainDialectOracleTest#mysqlDialectIsAKnownGap — so these
        // assertions pin the intent only, and say nothing about whether the syntax is accepted.
        var read = ExplainDialect.forQuery("mysql", "SELECT 1", true);
        assertThat(read.sql()).isEqualTo("EXPLAIN ANALYZE SELECT 1");
        assertThat(read.executes())
                .withFailMessage("EXPLAIN ANALYZE runs the query on MySQL as it does on Postgres")
                .isTrue();
        assertThat(read.label()).contains("8.0.18");

        var write = ExplainDialect.forQuery("mysql", "INSERT INTO t VALUES (1)", false);
        assertThat(write.sql()).isEqualTo("EXPLAIN INSERT INTO t VALUES (1)");
        assertThat(write.executes())
                .withFailMessage("a write must never be re-run to explain it")
                .isFalse();
    }
}
