package com.lockstep.verify;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.lockstep.config.DbConfig;
import com.lockstep.config.QuerySpec;
import com.lockstep.core.RunContext;
import com.lockstep.runner.db.DbRunner;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Verifies the per-driver {@code EXPLAIN} forms against real servers, and one safety property that
 * matters more than the plan text: explaining a write must not perform it.
 *
 * <p>Plan capture runs a second statement derived from the configured one. For a read that is
 * harmless and the point — {@code EXPLAIN (ANALYZE, BUFFERS)} executes the query to report real
 * buffer counts. For a write it must not: a load test that silently inserted an extra row for
 * every statement it explained would corrupt the thing it was measuring, and the corruption would
 * be invisible in the report.
 *
 * <p>MySQL is <strong>not covered here</strong>. Its dialect is exercised only at the string level
 * by {@code RoutingAndConnectionStringTest}; no MySQL server has ever run these statements. See
 * the note at the foot of this class.
 */
final class ExplainDialectOracleTest {
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

    private static long scalar(Connection connection, String sql) throws Exception {
        try (Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery(sql)) {
            return rows.next() ? rows.getLong(1) : -1;
        }
    }

    // --- PostgreSQL: the read form, and the write safety property -------------------------------

    @Test
    void postgresUsesTheBuffersFormForReads() throws Exception {
        assumeTrue(DockerClientFactory.instance().isDockerAvailable(), "Docker unavailable");

        try (PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine")) {
            pg.start();
            try (Connection connection = DriverManager.getConnection(
                        pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
                    Statement statement = connection.createStatement()) {
                statement.execute("CREATE TABLE fixture (id serial PRIMARY KEY, pad char(100))");
                statement.execute("INSERT INTO fixture (pad) SELECT repeat('x', 100) "
                        + "FROM generate_series(1, 5000)");
                statement.execute("VACUUM ANALYZE fixture");
            }

            String conn = "postgres://%s:%s@%s:%d/%s".formatted(pg.getUsername(), pg.getPassword(),
                    pg.getHost(), pg.getFirstMappedPort(), pg.getDatabaseName());
            DbConfig config = new DbConfig(new DbConfig.Target(conn, "postgres",
                    List.of(new QuerySpec("SELECT sum(length(pad)) FROM fixture", 1,
                            "read", null)), 4), 20);

            java.util.Map<String, DbRunner.QueryPlan> plans;
            try (DbRunner runner = DbRunner.create(config, 4)) {
                runner.run(RunContext.startingNow(2 * SECOND, 500 * MS, 0, 4));
                runner.capturePlans(1);   // threshold of 1ns: capture regardless of speed
                plans = runner.plans();
            }

            var plan = plans.values().stream().findFirst()
                    .orElseThrow(() -> new AssertionError("a plan must be captured; got " + plans));

            record("plan captured", "yes", plans.isEmpty() ? "no" : "yes", !plans.isEmpty());
            record("no failure", "none", plan.failure() == null ? "none" : plan.failure(),
                    plan.failure() == null);
            record("statement label", "EXPLAIN (ANALYZE, BUFFERS)", plan.statement(),
                    "EXPLAIN (ANALYZE, BUFFERS)".equals(plan.statement()));
            record("marked as executing", true, plan.executed(), plan.executed());
            record("plan names the table", "yes",
                    plan.plan() != null && plan.plan().contains("fixture") ? "yes" : "no",
                    plan.plan() != null && plan.plan().contains("fixture"));
            record("plan reports buffers", "yes",
                    plan.plan() != null && plan.plan().contains("Buffers") ? "yes" : "no",
                    plan.plan() != null && plan.plan().contains("Buffers"));
            printLedger("postgres EXPLAIN dialect, read");

            assertThat(plan.failure()).isNull();
            assertThat(plan.statement()).isEqualTo("EXPLAIN (ANALYZE, BUFFERS)");
            assertThat(plan.executed())
                    .withFailMessage("ANALYZE runs the query, and the report says so")
                    .isTrue();
            assertThat(plan.plan())
                    .withFailMessage("the captured text must be a real plan for this query")
                    .contains("fixture");
            assertThat(plan.plan())
                    .withFailMessage("""
                            BUFFERS is the reason this form is used rather than plain EXPLAIN; \
                            without it the plan cannot say how much work the query did. Captured:
                            %s""", plan.plan())
                    .contains("Buffers");
        }
    }

    @Test
    void explainingAWriteDoesNotPerformIt() throws Exception {
        assumeTrue(DockerClientFactory.instance().isDockerAvailable(), "Docker unavailable");

        try (PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine")) {
            pg.start();
            try (Connection connection = DriverManager.getConnection(
                        pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
                    Statement statement = connection.createStatement()) {
                statement.execute("CREATE TABLE fixture (id serial PRIMARY KEY, n int)");
            }

            String conn = "postgres://%s:%s@%s:%d/%s".formatted(pg.getUsername(), pg.getPassword(),
                    pg.getHost(), pg.getFirstMappedPort(), pg.getDatabaseName());
            DbConfig config = new DbConfig(new DbConfig.Target(conn, "postgres",
                    List.of(new QuerySpec("INSERT INTO fixture (n) VALUES (1)", 1,
                            "write", null)), 4), 40);

            long afterRun;
            long afterExplain;
            java.util.Map<String, DbRunner.QueryPlan> plans;
            try (DbRunner runner = DbRunner.create(config, 4)) {
                runner.run(RunContext.startingNow(2 * SECOND, 500 * MS, 0, 4));

                try (Connection connection = DriverManager.getConnection(
                        pg.getJdbcUrl(), pg.getUsername(), pg.getPassword())) {
                    afterRun = scalar(connection, "SELECT count(*) FROM fixture");
                }

                runner.capturePlans(1);
                plans = runner.plans();

                try (Connection connection = DriverManager.getConnection(
                        pg.getJdbcUrl(), pg.getUsername(), pg.getPassword())) {
                    afterExplain = scalar(connection, "SELECT count(*) FROM fixture");
                }
            }

            var plan = plans.values().stream().findFirst().orElseThrow();

            record("rows after the run", "> 0", afterRun, afterRun > 0);
            record("rows after explaining", afterRun, afterExplain, afterExplain == afterRun);
            record("statement label", "plain EXPLAIN", plan.statement(),
                    plan.statement() != null && !plan.statement().contains("ANALYZE"));
            record("marked as NOT executing", false, plan.executed(), !plan.executed());
            record("plan still captured", "yes",
                    plan.plan() != null && !plan.plan().isBlank() ? "yes" : "no",
                    plan.plan() != null && !plan.plan().isBlank());
            printLedger("postgres EXPLAIN dialect, write must not execute");

            assertThat(afterRun).isPositive();
            assertThat(afterExplain)
                    .withFailMessage("""
                            explaining an INSERT must not insert. The table held %d rows after \
                            the run and %d after plan capture: a load test that writes extra rows \
                            while explaining corrupts the data it is measuring, invisibly.""",
                            afterRun, afterExplain)
                    .isEqualTo(afterRun);
            assertThat(plan.executed())
                    .withFailMessage("a write's plan is an estimate, and the report must say so "
                            + "rather than implying measured timings")
                    .isFalse();
            assertThat(plan.statement()).doesNotContain("ANALYZE");
            assertThat(plan.plan()).isNotBlank();
        }
    }

    // --- SQLite: a different dialect entirely ----------------------------------------------------

    @Test
    void sqliteUsesQueryPlanAndNeverExecutes(@TempDir Path directory) throws Exception {
        Path file = directory.resolve("fixture.db");
        String url = "jdbc:sqlite:" + file;

        try (Connection connection = DriverManager.getConnection(url);
                Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE fixture (id INTEGER PRIMARY KEY, pad TEXT)");
            // One multi-row insert: SQLite fsyncs per autocommit statement.
            StringBuilder insert = new StringBuilder("INSERT INTO fixture (pad) VALUES ");
            for (int i = 0; i < 500; i++) {
                insert.append(i == 0 ? "" : ",").append("('").append("x".repeat(50)).append("')");
            }
            statement.execute(insert.toString());
        }

        DbConfig config = new DbConfig(new DbConfig.Target(url, "sqlite",
                List.of(new QuerySpec("SELECT count(*) FROM fixture WHERE pad LIKE '%x%'", 1,
                        "read", null)), 2), 20);

        java.util.Map<String, DbRunner.QueryPlan> plans;
        try (DbRunner runner = DbRunner.create(config, 2)) {
            runner.run(RunContext.startingNow(2 * SECOND, 500 * MS, 0, 2));
            runner.capturePlans(1);
            plans = runner.plans();
        }

        var plan = plans.values().stream().findFirst()
                .orElseThrow(() -> new AssertionError("a plan must be captured; got " + plans));

        boolean namesScan = plan.plan() != null
                && (plan.plan().contains("SCAN") || plan.plan().contains("SEARCH"));

        record("no failure", "none", plan.failure() == null ? "none" : plan.failure(),
                plan.failure() == null);
        record("statement label", "EXPLAIN QUERY PLAN (estimated)", plan.statement(),
                plan.statement() != null && plan.statement().contains("QUERY PLAN"));
        record("marked as NOT executing", false, plan.executed(), !plan.executed());
        record("plan describes a scan", "SCAN or SEARCH", namesScan ? "yes" : "no", namesScan);
        printLedger("sqlite EXPLAIN dialect");

        assertThat(plan.failure())
                .withFailMessage("SQLite's plan form must work on a real SQLite database")
                .isNull();
        assertThat(plan.statement()).contains("QUERY PLAN");
        assertThat(plan.executed())
                .withFailMessage("EXPLAIN QUERY PLAN does not run the query, and reporting it as "
                        + "executed would imply the timings are measured")
                .isFalse();
        assertThat(namesScan)
                .withFailMessage("""
                            a full-table LIKE must produce a plan mentioning SCAN or SEARCH; \
                            captured text was:
                            %s""", plan.plan())
                .isTrue();
    }

    /**
     * MySQL's dialect is unverified against a real server.
     *
     * <p>{@code ExplainDialect} emits {@code EXPLAIN ANALYZE} for MySQL reads, which needs 8.0.18
     * or later, and plain {@code EXPLAIN} for writes. Neither statement has ever been sent to a
     * MySQL server by this project: the only coverage is a string comparison in
     * {@code RoutingAndConnectionStringTest}.
     *
     * <p>Not done here because this environment has neither the Testcontainers MySQL module in its
     * local Maven repository nor the ability to pull a MySQL image. Recording it as a known gap
     * rather than leaving it to look covered: a dialect that has only been compared to an expected
     * string is a dialect whose syntax, permissions and version requirements are all untested.
     */
    @Test
    void mysqlDialectIsAKnownGap() {
        System.out.println("\n=== oracle verification \u00b7 mysql EXPLAIN dialect ===");
        System.out.println("  SKIPPED \u2014 no MySQL server available in this environment:");
        System.out.println("  the Testcontainers MySQL module is not in the local Maven");
        System.out.println("  repository and a MySQL image cannot be pulled.");
        System.out.println("  EXPLAIN ANALYZE (reads) and EXPLAIN (writes) are pinned as strings");
        System.out.println("  by QueryPlanCaptureTest and have never reached a running MySQL.");
        System.out.println("  Syntax, permissions and the 8.0.18 version floor are all untested.");
    }
}
