package com.lockstep.verify;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.lockstep.analysis.Survey;
import com.lockstep.report.CliTables;
import com.lockstep.runner.db.SurveyProbe;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Builds a database in which every condition the survey claims to detect has been created
 * deliberately, then checks that it detects those and only those.
 *
 * <p>The fixture holds: a large table queried without an index so it must be scanned; a small
 * table scanned constantly, where scanning is the correct plan; an index nothing reads; a unique
 * index nothing reads, which must <em>not</em> be recommended for dropping; and a statement run
 * many times whose total cost is therefore the largest even though each call is quick.
 */
@Tag("container")
final class SurveyOracleTest {

    private static final List<String> LEDGER = new ArrayList<>();

    private static void record(String claim, Object expected, Object reported, boolean ok) {
        LEDGER.add("  %-40s expected %-14s reported %-14s %s".formatted(
                claim, expected, reported, ok ? "MATCH" : "MISMATCH"));
    }

    private static void printLedger(String fixture) {
        System.out.println("\n=== oracle verification · " + fixture + " ===");
        LEDGER.forEach(System.out::println);
        LEDGER.clear();
    }

    private static PostgreSQLContainer<?> postgres() {
        return new PostgreSQLContainer<>("postgres:16-alpine")
                .withCommand("postgres", "-c", "shared_preload_libraries=pg_stat_statements");
    }

    private static String conn(PostgreSQLContainer<?> pg) {
        return "postgres://%s:%s@%s:%d/%s".formatted(pg.getUsername(), pg.getPassword(),
                pg.getHost(), pg.getFirstMappedPort(), pg.getDatabaseName());
    }

    /** Creates the fixture and generates the traffic that makes its counters meaningful. */
    private static void build(PostgreSQLContainer<?> pg) throws Exception {
        try (Connection connection = DriverManager.getConnection(
                    pg.getJdbcUrl(), pg.getUsername(), pg.getPassword())) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("CREATE EXTENSION pg_stat_statements");

                // Large, and deliberately without an index on the column it is filtered by.
                statement.execute("CREATE TABLE big (id serial PRIMARY KEY, owner int, "
                        + "pad char(200))");
                statement.execute("INSERT INTO big (owner, pad) "
                        + "SELECT g % 500, repeat('x', 200) FROM generate_series(1, 60000) g");

                // Small: scanning it is the right plan and must not be flagged.
                statement.execute("CREATE TABLE small (id serial PRIMARY KEY, label text)");
                statement.execute("INSERT INTO small (label) "
                        + "SELECT 'l' || g FROM generate_series(1, 50) g");

                // An ordinary index nothing will read, and a unique one nothing will read.
                statement.execute("CREATE TABLE idx_fixture (id serial PRIMARY KEY, a int, b int, "
                        + "pad char(100))");
                statement.execute("INSERT INTO idx_fixture (a, b, pad) "
                        + "SELECT g, g, repeat('y', 100) FROM generate_series(1, 20000) g");
                statement.execute("CREATE INDEX never_read ON idx_fixture (a)");
                statement.execute("CREATE UNIQUE INDEX never_read_unique ON idx_fixture (b)");

                statement.execute("VACUUM ANALYZE");

                // Reset the statement counters now, so what follows is traffic rather than
                // setup. This is what a real database looks like: migrations ran months ago and
                // their share of recorded time is negligible beside months of queries.
                //
                // The PROBE must never do this — resetting would destroy counters belonging to
                // every other tool watching the same database. A test that owns its container
                // may, and the distinction is the point.
                statement.execute("SELECT pg_stat_statements_reset()");
            }

            // Traffic. The scan of `big` is the expensive statement; the small-table select is
            // called far more often but is individually trivial.
            try (PreparedStatement scan = connection.prepareStatement(
                        "SELECT count(*) FROM big WHERE owner = ?");
                    PreparedStatement tiny = connection.prepareStatement(
                        "SELECT count(*) FROM small WHERE label = ?")) {
                for (int i = 0; i < 60; i++) {
                    scan.setInt(1, i);
                    drain(scan);
                }
                for (int i = 0; i < 1_200; i++) {
                    tiny.setString(1, "l" + (i % 50));
                    drain(tiny);
                }
            }
        }
    }

    private static void drain(PreparedStatement statement) throws Exception {
        try (ResultSet rows = statement.executeQuery()) {
            while (rows.next()) {
                rows.getObject(1);
            }
        }
    }

    @Test
    void theSurveyFindsWhatWasDeliberatelyCreatedAndNothingElse() throws Exception {
        assumeTrue(DockerClientFactory.instance().isDockerAvailable(), "Docker unavailable");

        try (PostgreSQLContainer<?> pg = postgres()) {
            pg.start();
            build(pg);

            Survey survey;
            try (SurveyProbe probe = SurveyProbe.open(conn(pg), "postgres")) {
                survey = probe.read(null);
            }

            assertThat(survey.available())
                    .withFailMessage("survey failed: %s", survey.unavailableReason())
                    .isTrue();

            // --- statements, ranked by TOTAL time rather than mean ---
            assertThat(survey.statementsAvailable())
                    .withFailMessage("pg_stat_statements was preloaded and created: %s",
                            survey.statementsUnavailableReason())
                    .isTrue();
            var top = survey.statementsByTotalTime(5);
            boolean scanIsTop = !top.isEmpty() && top.get(0).oneLine(200).contains("FROM big");

            boolean noUtility = survey.statements().stream().noneMatch(st -> {
                String q = st.oneLine(400).toUpperCase();
                return q.startsWith("VACUUM") || q.startsWith("CREATE") || q.startsWith("ANALYZE")
                        || q.startsWith("ALTER") || q.startsWith("DROP") || q.startsWith("SET ");
            });
            record("statements recorded", "> 1", survey.statements().size(),
                    survey.statements().size() > 1);
            record("no DDL or maintenance in ranking", "none",
                    noUtility ? "none" : "present", noUtility);
            record("costliest statement scans `big`", "yes", scanIsTop ? "yes" : "no", scanIsTop);

            // --- tables: the large scanned one is flagged, the small one is not ---
            var big = survey.relations().stream()
                    .filter(r -> r.name().equals("big")).findFirst().orElseThrow();
            var small = survey.relations().stream()
                    .filter(r -> r.name().equals("small")).findFirst().orElseThrow();

            record("big: sequential scans", ">= 60", big.sequentialScans(),
                    big.sequentialScans() >= 60);
            record("big: rows per scan", "~60,000", big.rowsPerScan(),
                    big.rowsPerScan() > 50_000);
            record("big: flagged as missing an index", "yes",
                    big.likelyMissingIndex() ? "yes" : "no", big.likelyMissingIndex());
            record("small: scanned often", "> 1,000", small.sequentialScans(),
                    small.sequentialScans() > 1_000);
            record("small: NOT flagged", "not flagged",
                    small.likelyMissingIndex() ? "flagged" : "not flagged",
                    !small.likelyMissingIndex());

            // --- indexes: the plain one is droppable, the unique one is not ---
            var plain = survey.indexes().stream()
                    .filter(i -> i.name().equals("never_read")).findFirst().orElseThrow();
            var unique = survey.indexes().stream()
                    .filter(i -> i.name().equals("never_read_unique")).findFirst().orElseThrow();

            record("never_read: scans", 0, plain.scans(), plain.scans() == 0);
            record("never_read: droppable", "yes", plain.droppable() ? "yes" : "no",
                    plain.droppable());
            record("never_read_unique: scans", 0, unique.scans(), unique.scans() == 0);
            record("never_read_unique: droppable", "NO",
                    unique.droppable() ? "yes" : "no", !unique.droppable());
            record("never_read_unique: enforces constraint", "yes",
                    unique.enforcesAConstraint() ? "yes" : "no", unique.enforcesAConstraint());

            boolean noPrimaryKeyDroppable = survey.indexes().stream()
                    .filter(Survey.Index::primaryKey)
                    .noneMatch(Survey.Index::droppable);
            record("no primary key marked droppable", "yes",
                    noPrimaryKeyDroppable ? "yes" : "no", noPrimaryKeyDroppable);
            record("database does not look idle", "not idle",
                    survey.looksIdle() ? "idle" : "not idle", !survey.looksIdle());
            printLedger("survey against a deliberately built database");

            assertThat(noUtility)
                    .withFailMessage("""
                            schema changes and VACUUM must not appear in a ranking of queries. \
                            Before they were filtered, VACUUM ANALYZE alone took 54.5%% of all \
                            recorded time on this fixture and buried the statement the survey \
                            exists to find. Statements: %s""",
                            survey.statements().stream().map(st -> st.oneLine(40)).toList())
                    .isTrue();
            assertThat(scanIsTop)
                    .withFailMessage("""
                            the scan of `big` ran 60 times and the trivial select ran 1,200, so \
                            ranking by MEAN would put the scan first by accident and ranking by \
                            CALLS would bury it. Total time is the ranking that finds it. Top \
                            statements were: %s""",
                            top.stream().map(st -> st.oneLine(50)).toList())
                    .isTrue();

            assertThat(big.likelyMissingIndex())
                    .withFailMessage("""
                            `big` is %d bytes and was scanned %d times at %d rows a scan, with no \
                            index on the filtered column. That is the shape this is for.""",
                            big.tableBytes(), big.sequentialScans(), big.rowsPerScan())
                    .isTrue();
            assertThat(small.likelyMissingIndex())
                    .withFailMessage("""
                            `small` holds 50 rows and was scanned %d times. Scanning it is the \
                            cheapest available plan, and flagging it would bury the real finding \
                            under noise.""", small.sequentialScans())
                    .isFalse();

            assertThat(plain.droppable())
                    .withFailMessage("an ordinary index read by nothing is dead weight")
                    .isTrue();
            assertThat(unique.droppable())
                    .withFailMessage("""
                            never_read_unique has never been used for a lookup, but it enforces a \
                            uniqueness constraint. Listing it as droppable would be advising \
                            someone to delete a correctness guarantee because it happened not to \
                            serve a query.""")
                    .isFalse();
            assertThat(noPrimaryKeyDroppable)
                    .withFailMessage("a primary key must never be recommended for dropping")
                    .isTrue();
        }
    }

    @Test
    void theReportNamesTheFindingsAndRefusesToRecommendDroppingConstraints() throws Exception {
        assumeTrue(DockerClientFactory.instance().isDockerAvailable(), "Docker unavailable");

        try (PostgreSQLContainer<?> pg = postgres()) {
            pg.start();
            build(pg);

            Survey survey;
            try (SurveyProbe probe = SurveyProbe.open(conn(pg), "postgres")) {
                survey = probe.read(null);
            }
            String report = CliTables.surveyReport(survey, 8);
            System.out.println("\n=== survey report ===\n" + report);

            assertThat(report).contains("queries by total time");
            assertThat(report).contains("tables read sequentially");
            assertThat(report).contains("indexes never read");
            assertThat(report).contains("an index is likely missing");
            assertThat(report).contains("small; scanning it is fine");
            assertThat(report)
                    .withFailMessage("the report must say why a unique index is not dead weight")
                    .contains("UNIQUE — enforces a constraint, do not drop");
            assertThat(report)
                    .withFailMessage("ranking by total rather than mean is the point, and the "
                            + "report should say so")
                    .contains("ranked by total time, not mean");
            assertThat(report)
                    .withFailMessage("a survey that does not say what to do next has not finished "
                            + "the job")
                    .contains("what to do with this");
        }
    }

    @Test
    void anUntouchedDatabaseSaysItsNumbersAreMeaningless() throws Exception {
        assumeTrue(DockerClientFactory.instance().isDockerAvailable(), "Docker unavailable");

        try (PostgreSQLContainer<?> pg = postgres()) {
            pg.start();
            try (Connection connection = DriverManager.getConnection(
                        pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
                    Statement statement = connection.createStatement()) {
                statement.execute("CREATE EXTENSION pg_stat_statements");
                statement.execute("CREATE TABLE untouched (id int PRIMARY KEY, pad text)");
                statement.execute("CREATE INDEX never_used ON untouched (pad)");
                statement.execute("SELECT pg_stat_statements_reset()");
            }

            Survey survey;
            try (SurveyProbe probe = SurveyProbe.open(conn(pg), "postgres")) {
                survey = probe.read(null);
            }
            String report = CliTables.surveyReport(survey, 8);

            record("looks idle", "yes", survey.looksIdle() ? "yes" : "no", survey.looksIdle());
            record("report warns", "yes",
                    report.contains("barely been queried") ? "yes" : "no",
                    report.contains("barely been queried"));
            printLedger("survey against a database with no real traffic");

            assertThat(survey.looksIdle())
                    .withFailMessage("""
                            nothing has queried this database, so idx_scan = 0 on every index \
                            means nothing at all. Reporting those as dead weight would be the \
                            worst possible advice.""")
                    .isTrue();
            assertThat(report).contains("barely been queried");
            assertThat(report).contains("Run this against production");
        }
    }

    /**
     * The endpoint ranking, which was the one part of the survey with no coverage at all.
     *
     * <p>Pairing a {@code _count} sample with its {@code _sum} sample by matching {@code uri} and
     * {@code method} labels is exactly the sort of code that silently returns nothing or pairs the
     * wrong rows, and neither failure looks like a failure.
     */
    @Test
    void endpointsAreRankedByTotalTimeFromTheTargetsOwnMetrics() throws Exception {
        assumeTrue(DockerClientFactory.instance().isDockerAvailable(), "Docker unavailable");

        // Three endpoints. /slow is called least and costs most; /chatty is called most and costs
        // least. Ranking by count or by mean would pick the wrong one in opposite directions.
        String payload = """
                # TYPE http_server_requests_seconds summary
                http_server_requests_seconds_count{method="GET",uri="/api/slow",status="200"} 1000.0
                http_server_requests_seconds_sum{method="GET",uri="/api/slow",status="200"} 400.0
                http_server_requests_seconds_count{method="GET",uri="/api/chatty",status="200"} 900000.0
                http_server_requests_seconds_sum{method="GET",uri="/api/chatty",status="200"} 90.0
                http_server_requests_seconds_count{method="POST",uri="/api/write",status="201"} 5000.0
                http_server_requests_seconds_sum{method="POST",uri="/api/write",status="201"} 150.0
                """;

        var server = com.sun.net.httpserver.HttpServer.create(
                new java.net.InetSocketAddress(0), 0);
        server.setExecutor(java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/metrics", exchange -> {
            byte[] body = payload.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (var out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();

        try (PostgreSQLContainer<?> pg = postgres()) {
            pg.start();
            try (Connection connection = DriverManager.getConnection(
                        pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
                    Statement statement = connection.createStatement()) {
                statement.execute("CREATE EXTENSION pg_stat_statements");
            }

            Survey survey;
            try (SurveyProbe probe = SurveyProbe.open(conn(pg), "postgres")) {
                survey = probe.read("http://localhost:" + server.getAddress().getPort()
                        + "/metrics");
            }

            var ranked = survey.endpointsByTotalTime(5);
            var slow = ranked.isEmpty() ? null : ranked.get(0);
            var chatty = survey.endpoints().stream()
                    .filter(e -> e.uri().equals("/api/chatty")).findFirst().orElse(null);

            record("endpoints found", 3, survey.endpoints().size(),
                    survey.endpoints().size() == 3);
            record("ranked first", "GET /api/slow",
                    slow == null ? "none" : slow.label(),
                    slow != null && slow.label().equals("GET /api/slow"));
            record("/api/slow total", "400s",
                    slow == null ? "-" : "%.0fs".formatted(slow.totalSeconds()),
                    slow != null && Math.abs(slow.totalSeconds() - 400) < 0.5);
            record("/api/slow mean", "400ms",
                    slow == null ? "-" : "%.0fms".formatted(slow.meanMillis()),
                    slow != null && Math.abs(slow.meanMillis() - 400) < 1);
            record("/api/chatty called most", "900,000",
                    chatty == null ? "-" : chatty.count(),
                    chatty != null && chatty.count() == 900_000);
            record("/api/chatty mean", "0.1ms",
                    chatty == null ? "-" : "%.1fms".formatted(chatty.meanMillis()),
                    chatty != null && Math.abs(chatty.meanMillis() - 0.1) < 0.01);
            record("method labels kept", "POST on /api/write",
                    survey.endpoints().stream()
                            .filter(e -> e.uri().equals("/api/write"))
                            .map(Survey.Endpoint::label).findFirst().orElse("none"),
                    survey.endpoints().stream()
                            .anyMatch(e -> "POST /api/write".equals(e.label())));
            printLedger("endpoint ranking from the target's metrics");

            assertThat(survey.endpoints())
                    .withFailMessage("three endpoints were exposed; pairing count with sum by "
                            + "label is where this silently returns nothing")
                    .hasSize(3);
            assertThat(slow).isNotNull();
            assertThat(slow.label())
                    .withFailMessage("""
                            /api/slow is called 1,000 times for 400s, /api/chatty 900,000 times \
                            for 90s. Ranking by count puts chatty first and ranking by mean also \
                            puts slow first but for the wrong reason — total time is the ranking \
                            that finds what the server actually spends its time on. Got: %s""",
                            ranked.stream().map(Survey.Endpoint::label).toList())
                    .isEqualTo("GET /api/slow");
            assertThat(slow.totalSeconds()).isCloseTo(400,
                    org.assertj.core.data.Offset.offset(0.5));
            assertThat(slow.meanMillis())
                    .withFailMessage("400s over 1,000 calls is a 400ms mean")
                    .isCloseTo(400, org.assertj.core.data.Offset.offset(1.0));
            assertThat(chatty).isNotNull();
            assertThat(chatty.meanMillis())
                    .withFailMessage("90s over 900,000 calls is 0.1ms — mis-pairing the sum with "
                            + "another endpoint's would show this as hundreds of ms")
                    .isCloseTo(0.1, org.assertj.core.data.Offset.offset(0.01));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void aNonPostgresTargetIsRefused() {
        assertThat(org.assertj.core.api.Assertions.catchThrowable(
                () -> SurveyProbe.open("jdbc:sqlite::memory:", "sqlite")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("statistics views");
    }
}
