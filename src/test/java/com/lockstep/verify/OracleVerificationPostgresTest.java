package com.lockstep.verify;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.lockstep.analysis.GrowthCurve;
import com.lockstep.analysis.ResourceAccounting;
import com.lockstep.analysis.TargetQueries;
import com.lockstep.analysis.WriteAmplification;
import com.lockstep.config.DbConfig;
import com.lockstep.config.HttpConfig;
import com.lockstep.config.QuerySpec;
import com.lockstep.core.RunContext;
import com.lockstep.runner.db.DbRunner;
import com.lockstep.runner.db.TargetObserver;
import com.lockstep.runner.http.HttpRunner;
import com.sun.net.httpserver.HttpServer;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Checks Lockstep's reported numbers against a database whose every relevant property is known
 * exactly and derived independently.
 *
 * <p>The other integration tests ask whether a figure is plausible — is it above a threshold, does
 * it move in the right direction. That catches a lot and it did not catch everything: several
 * defects this codebase has carried were in figures that looked entirely reasonable. So these
 * fixtures are built so that the true answer can be computed from a different source than the one
 * Lockstep uses, and the two compared.
 *
 * <p>Independent sources used here: {@code count(*)} for row counts rather than any statistics
 * view; {@code pg_relation_size} divided by the block size for block counts; the buffer counts
 * printed by {@code EXPLAIN (ANALYZE, BUFFERS)} for per-query work; {@code pg_index} for index
 * counts; and, for statements per request, the number written into the test target by hand.
 *
 * <p>Each check prints expected against reported, so a failure shows the discrepancy rather than
 * only that one existed.
 */
final class OracleVerificationPostgresTest {
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

    private static String conn(PostgreSQLContainer<?> pg) {
        return "postgres://%s:%s@%s:%d/%s".formatted(pg.getUsername(), pg.getPassword(),
                pg.getHost(), pg.getFirstMappedPort(), pg.getDatabaseName());
    }

    private static Connection open(PostgreSQLContainer<?> pg) throws Exception {
        return DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
    }

    private static long scalar(Connection connection, String sql) throws Exception {
        try (Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery(sql)) {
            return rows.next() ? rows.getLong(1) : -1;
        }
    }

    /**
     * Buffers a single execution of this query touches, from the planner's own report.
     *
     * <p>Takes the <strong>first</strong> Buffers line only. A parent plan node's buffer counts
     * are cumulative over its children, so summing every line double-counts: an Aggregate over a
     * Seq Scan of 18 blocks reports 18 at each of the two nodes, and adding them gives 36. That
     * mistake made this oracle disagree with a correct measurement.
     */
    private static long buffersFromExplain(Connection connection, String query) throws Exception {
        try (Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery(
                        "EXPLAIN (ANALYZE, BUFFERS) " + query)) {
            while (rows.next()) {
                String line = rows.getString(1);
                if (!line.contains("Buffers:")) {
                    continue;
                }
                var matcher = java.util.regex.Pattern
                        .compile("shared hit=(\\d+)(?: read=(\\d+))?").matcher(line);
                if (matcher.find()) {
                    long total = Long.parseLong(matcher.group(1));
                    if (matcher.group(2) != null) {
                        total += Long.parseLong(matcher.group(2));
                    }
                    return total;
                }
            }
        }
        return -1;
    }

    // --- fixture 1: exact rows, exact size, exact blocks per request ----------------------------

    @Test
    void resourceAccountingMatchesIndependentlyDerivedTruth() throws Exception {
        assumeTrue(DockerClientFactory.instance().isDockerAvailable(), "Docker unavailable");

        final int rows = 1_000;
        try (PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine")) {
            pg.start();

            long trueRows;
            long trueHeapBlocks;
            long trueBytesPerRow;
            long trueBuffersPerQuery;
            String query = "SELECT sum(length(pad)) FROM fixture";

            try (Connection connection = open(pg)) {
                try (Statement statement = connection.createStatement()) {
                    // char(100) is fixed width, so every row is identical in size.
                    statement.execute("CREATE TABLE fixture (id int PRIMARY KEY, pad char(100))");
                    statement.execute("INSERT INTO fixture "
                            + "SELECT g, repeat('x', 100) FROM generate_series(1, " + rows + ") g");
                    statement.execute("VACUUM ANALYZE fixture");
                }
                trueRows = scalar(connection, "SELECT count(*) FROM fixture");
                trueHeapBlocks = scalar(connection,
                        "SELECT pg_relation_size('fixture') / current_setting('block_size')::int");
                trueBytesPerRow = scalar(connection,
                        "SELECT pg_table_size('fixture') / count(*) FROM fixture");
                buffersFromExplain(connection, query);   // warm the cache first
                trueBuffersPerQuery = buffersFromExplain(connection, query);
            }

            DbConfig config = new DbConfig(new DbConfig.Target(conn(pg), "postgres",
                    List.of(new QuerySpec(query, 1, "read", null)), 4), 40);

            ResourceAccounting accounting;
            try (DbRunner runner = DbRunner.create(config, 4)) {
                runner.run(RunContext.startingNow(3 * SECOND, 500 * MS, 0, 4));
                accounting = runner.resourceAccounting();
            }

            var fixture = accounting.relations().stream()
                    .filter(relation -> relation.name().equals("fixture"))
                    .findFirst().orElseThrow();
            long reportedBlocks = accounting.blocksPerRequest(fixture);

            record("rows", trueRows, fixture.liveRows(), fixture.liveRows() == trueRows);
            record("bytes per row", trueBytesPerRow, fixture.bytesPerRow(),
                    fixture.bytesPerRow() == trueBytesPerRow);
            record("blocks per request (vs EXPLAIN)", trueBuffersPerQuery, reportedBlocks,
                    Math.abs(reportedBlocks - trueBuffersPerQuery) <= 2);
            record("heap blocks (independent)", trueHeapBlocks, reportedBlocks,
                    Math.abs(reportedBlocks - trueHeapBlocks) <= 2);
            printLedger("resource accounting, 1,000 fixed-width rows");

            assertThat(fixture.liveRows())
                    .withFailMessage("count(*) says %d rows; the report says %d",
                            trueRows, fixture.liveRows())
                    .isEqualTo(trueRows);
            assertThat(fixture.bytesPerRow())
                    .withFailMessage("pg_table_size/count(*) is %d bytes a row; the report says %d",
                            trueBytesPerRow, fixture.bytesPerRow())
                    .isEqualTo(trueBytesPerRow);
            assertThat(reportedBlocks)
                    .withFailMessage("""
                            EXPLAIN (ANALYZE, BUFFERS) says one execution touches %d buffers; \
                            the report says %d per request""",
                            trueBuffersPerQuery, reportedBlocks)
                    .isBetween(trueBuffersPerQuery - 2, trueBuffersPerQuery + 2);
        }
    }

    // --- fixture 2: exact index count, exact insert count ---------------------------------------

    @Test
    void writeAmplificationMatchesTheIndexesAndInsertsThatActuallyExist() throws Exception {
        assumeTrue(DockerClientFactory.instance().isDockerAvailable(), "Docker unavailable");

        final int extraIndexes = 10;
        try (PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine")) {
            pg.start();

            long trueIndexes;
            long rowsBefore;
            try (Connection connection = open(pg)) {
                try (Statement statement = connection.createStatement()) {
                    statement.execute("CREATE TABLE fixture (id serial PRIMARY KEY, "
                            + "a int, b int, c int, d int, e int, pad char(50))");
                    for (int i = 1; i <= extraIndexes; i++) {
                        String column = List.of("a", "b", "c", "d", "e").get(i % 5);
                        statement.execute("CREATE INDEX fixture_ix_" + i
                                + " ON fixture (" + column + ", id)");
                    }
                    statement.execute("VACUUM ANALYZE fixture");
                }
                trueIndexes = scalar(connection, """
                        SELECT count(*) FROM pg_index i
                        JOIN pg_class c ON c.oid = i.indrelid
                        WHERE c.relname = 'fixture'
                        """);
                rowsBefore = scalar(connection, "SELECT count(*) FROM fixture");
            }

            DbConfig config = new DbConfig(new DbConfig.Target(conn(pg), "postgres",
                    List.of(new QuerySpec("INSERT INTO fixture (a,b,c,d,e,pad) "
                            + "VALUES (1,2,3,4,5, repeat('y',50))", 1, "write", null)), 4), 100);

            WriteAmplification amplification;
            long executed;
            try (DbRunner runner = DbRunner.create(config, 4)) {
                executed = runner.run(RunContext.startingNow(3 * SECOND, 500 * MS, 0, 4))
                        .executedCount();
                amplification = runner.writeAmplification();
            }

            long trueInserts;
            try (Connection connection = open(pg)) {
                trueInserts = scalar(connection, "SELECT count(*) FROM fixture") - rowsBefore;
            }

            var fixture = amplification.byWritesDescending().get(0);

            record("index count (vs pg_index)", trueIndexes, fixture.indexCount(),
                    fixture.indexCount() == trueIndexes);
            record("inserts (vs count(*) delta)", trueInserts, fixture.inserts(),
                    fixture.inserts() == trueInserts);
            record("inserts (vs operations executed)", executed, fixture.inserts(),
                    fixture.inserts() == executed);
            record("WAL records per insert", ">= " + trueIndexes,
                    "%.1f".formatted(amplification.walRecordsPerWrite()),
                    amplification.walRecordsPerWrite() >= trueIndexes);
            printLedger("write amplification, " + trueIndexes + " indexes");

            assertThat(fixture.indexCount())
                    .withFailMessage("pg_index counts %d indexes; the report says %d",
                            trueIndexes, fixture.indexCount())
                    .isEqualTo((int) trueIndexes);
            assertThat(fixture.inserts())
                    .withFailMessage("""
                            count(*) grew by %d and the loop executed %d operations; the report \
                            says %d inserts""", trueInserts, executed, fixture.inserts())
                    .isEqualTo(trueInserts)
                    .isEqualTo(executed);
            assertThat(amplification.walRecordsPerWrite())
                    .withFailMessage("""
                            every one of the %d indexes must be maintained per insert, so WAL \
                            records per insert cannot be below that; got %.2f""",
                            trueIndexes, amplification.walRecordsPerWrite())
                    .isGreaterThanOrEqualTo(trueIndexes);
        }
    }

    // --- fixture 3: an endpoint whose statement count is known by construction -------------------

    @Test
    void statementsPerRequestMatchesTheNumberWrittenIntoTheTarget() throws Exception {
        assumeTrue(DockerClientFactory.instance().isDockerAvailable(), "Docker unavailable");

        final int statementsPerRequest = 5;
        try (PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine")
                .withCommand("postgres", "-c", "shared_preload_libraries=pg_stat_statements")) {
            pg.start();
            try (Connection connection = open(pg);
                    Statement statement = connection.createStatement()) {
                statement.execute("CREATE EXTENSION pg_stat_statements");
                statement.execute("CREATE TABLE fixture (id int PRIMARY KEY, pad char(20))");
                statement.execute("INSERT INTO fixture "
                        + "SELECT g, 'x' FROM generate_series(1, 100) g");
                statement.execute("VACUUM ANALYZE fixture");
            }

            HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
            server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
            // Exactly `statementsPerRequest` statements, every request, by construction.
            server.createContext("/fixed", exchange -> {
                try (Connection connection = open(pg)) {
                    for (int i = 1; i <= statementsPerRequest; i++) {
                        try (PreparedStatement statement = connection.prepareStatement(
                                "SELECT pad FROM fixture WHERE id = ?")) {
                            statement.setInt(1, i);
                            try (ResultSet rows = statement.executeQuery()) {
                                while (rows.next()) {
                                    rows.getString(1);
                                }
                            }
                        }
                    }
                } catch (Exception e) {
                    // fall through to a 200: the count is what is under test
                }
                byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, body.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(body);
                }
            });
            server.start();

            try {
                HttpConfig config = new HttpConfig(new HttpConfig.Target("GET",
                        "http://localhost:" + server.getAddress().getPort() + "/fixed",
                        null, Map.of(), 1), 20);

                TargetQueries queries;
                try (TargetObserver observer = TargetObserver.open(conn(pg), "postgres")) {
                    observer.before();
                    long executed;
                    try (HttpRunner runner = HttpRunner.create(config, 4)) {
                        executed = runner.run(RunContext.startingNow(3 * SECOND, 500 * MS, 0, 4))
                                .executedCount();
                    }
                    queries = observer.after(executed);
                }

                double reported = queries.statementsPerRequest();
                boolean ok = Math.abs(reported - statementsPerRequest) <= 0.5;

                record("requests", "> 20", queries.requests(), queries.requests() > 20);
                record("statements per request", statementsPerRequest,
                        "%.2f".formatted(reported), ok);
                printLedger("statements per request, " + statementsPerRequest + " by construction");

                assertThat(queries.canJudgeStatementCount()).isTrue();
                assertThat(reported)
                        .withFailMessage("""
                                the endpoint sends exactly %d statements per request by \
                                construction; the report says %.3f over %d requests""",
                                statementsPerRequest, reported, queries.requests())
                        .isCloseTo(statementsPerRequest,
                                org.assertj.core.data.Offset.offset(0.5));
            } finally {
                server.stop(0);
            }
        }
    }

    // --- fixture 4: a growth curve whose exponent is known from first principles -----------------

    @Test
    void aPureTableScanFitsAnExponentOfOne() throws Exception {
        assumeTrue(DockerClientFactory.instance().isDockerAvailable(), "Docker unavailable");

        try (PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine")) {
            pg.start();
            try (Connection connection = open(pg);
                    Statement statement = connection.createStatement()) {
                statement.execute("CREATE TABLE fixture (id serial PRIMARY KEY, pad char(100))");
            }

            String query = "SELECT sum(length(pad)) FROM fixture";
            List<GrowthCurve.Point> points = new ArrayList<>();
            List<Long> trueBlocks = new ArrayList<>();

            // A sequential scan reads every block, so doubling the rows must double the work.
            for (int rows : new int[] {20_000, 40_000, 80_000}) {
                try (Connection connection = open(pg);
                        Statement statement = connection.createStatement()) {
                    long present = scalar(connection, "SELECT count(*) FROM fixture");
                    statement.execute("INSERT INTO fixture (pad) SELECT repeat('x', 100) "
                            + "FROM generate_series(1, " + (rows - present) + ")");
                    statement.execute("VACUUM ANALYZE fixture");
                    trueBlocks.add(scalar(connection,
                            "SELECT pg_relation_size('fixture') / current_setting('block_size')::int"));
                }

                DbConfig config = new DbConfig(new DbConfig.Target(conn(pg), "postgres",
                        List.of(new QuerySpec(query, 1, "read", null)), 4), 20);
                try (DbRunner runner = DbRunner.create(config, 4)) {
                    runner.run(RunContext.startingNow(2 * SECOND, 500 * MS, 0, 4));  // warm
                    var loop = runner.run(RunContext.startingNow(3 * SECOND, 500 * MS, 0, 4));
                    var summary = loop.series().summarize("db", 3 * SECOND);
                    var accounting = runner.resourceAccounting();
                    var relation = accounting.relations().stream()
                            .filter(r -> r.name().equals("fixture")).findFirst().orElseThrow();
                    points.add(new GrowthCurve.Point(relation.liveRows(),
                            summary.serviceP99Nanos(), summary.p99Nanos(),
                            accounting.bytesPerRequest(relation), relation.hitRatio(),
                            summary.achievedRatePerSecond()));
                }
            }

            GrowthCurve curve = GrowthCurve.fit(points);

            // Ground truth: a sequential scan reads every block, so bytes per request must scale
            // exactly with the row count. That is the WORK exponent, and it must be 1.
            //
            // The TIME exponent is not 1, and should not be expected to be. Service time carries
            // a fixed per-query cost — pool acquisition, parse, plan, round trip — that does not
            // grow with the table, which makes the truth affine rather than a power law. Over
            // this range that fixed cost is comparable to the variable cost, so the fitted time
            // exponent lands near 0.66. Asserting 1.0 here was wrong, and the feature now reports
            // the gap and estimates the fixed cost instead of leaving the exponent to mislead.
            double blockRatio = (double) trueBlocks.get(2) / trueBlocks.get(0);
            record("heap blocks 20k -> 80k", "~4.0x", "%.2fx".formatted(blockRatio),
                    blockRatio > 3.5 && blockRatio < 4.5);
            record("work exponent (bytes/req)", "~1.0", "%.2f".formatted(curve.workExponent()),
                    curve.workExponent() > 0.9 && curve.workExponent() < 1.1);
            // Whether fixed cost is visible depends on how the machine happened to be loaded:
            // across runs of this same fixture the time exponent has been 0.66 (fixed cost about
            // half of a 10ms query) and 1.07 (fixed cost negligible). Both are correct. What must
            // hold either way is that any gap between work and time is EXPLAINED rather than left
            // for the exponent to misrepresent.
            boolean gap = curve.workExponent() - curve.exponent() >= 0.25;
            record("time exponent", "<= work + noise", "%.2f".formatted(curve.exponent()),
                    curve.exponent() <= curve.workExponent() + 0.3);
            record("gap between work and time", gap ? "present" : "none",
                    gap ? "present" : "none", true);
            record("gap explained by fixed cost", gap ? "required" : "not applicable",
                    curve.fixedCostMasksScaling()
                            ? com.lockstep.util.Numbers.latency(curve.fixedOverheadNanos())
                            : "not flagged",
                    !gap || curve.fixedCostMasksScaling());
            record("R squared", "> 0.90", "%.3f".formatted(curve.rSquared()),
                    curve.rSquared() > 0.90);
            printLedger("growth curve, pure sequential scan");

            assertThat(blockRatio)
                    .withFailMessage("the fixture must actually quadruple in size: %.3f", blockRatio)
                    .isBetween(3.5, 4.5);
            assertThat(curve.workExponent())
                    .withFailMessage("""
                            a sequential scan reads every block, so bytes per request must scale \
                            with the row count and the work exponent must be 1. Measured %.3f \
                            from points %s""", curve.workExponent(), points)
                    .isBetween(0.9, 1.1);
            assertThat(curve.exponent())
                    .withFailMessage("""
                            time cannot scale appreciably faster than the work it does: work \
                            %.2f, time %.2f""", curve.workExponent(), curve.exponent())
                    .isLessThanOrEqualTo(curve.workExponent() + 0.3);

            if (curve.workExponent() - curve.exponent() >= 0.25) {
                assertThat(curve.fixedCostMasksScaling())
                        .withFailMessage("""
                                the work scaled at %.2f and the time at only %.2f. That gap must \
                                be explained as fixed per-request cost, or the time exponent will \
                                be read as the scaling and understate it""",
                                curve.workExponent(), curve.exponent())
                        .isTrue();
                assertThat(curve.fixedOverheadNanos())
                        .withFailMessage("a flagged fixed cost must also be quantified")
                        .isPositive();
            }
        }
    }
}
