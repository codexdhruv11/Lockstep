package com.lockstep.verify;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.lockstep.analysis.Bottleneck;
import com.lockstep.analysis.GrowthCurve;
import com.lockstep.config.DbConfig;
import com.lockstep.config.QuerySpec;
import com.lockstep.core.RunContext;
import com.lockstep.runner.db.BottleneckSampler;
import com.lockstep.runner.db.DbRunner;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Forces the database into specific states so that conclusions which have only ever been produced
 * from hand-built inputs get produced by a real server.
 *
 * <p>Two of Lockstep's verdicts had never fired outside a unit test: lock contention, and a buffer
 * cache that stops holding the working set. A branch that has only been exercised with constructed
 * data is a branch whose trigger condition is unverified — the logic may be right and the
 * threshold it keys on may never be reached, or reached by something else.
 *
 * <p>So: hold a row lock and drive updates at the locked row, and shrink {@code shared_buffers}
 * until a growing table demonstrably outgrows it.
 */
final class PostgresStateOracleTest {
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

    // --- lock contention, produced rather than constructed --------------------------------------

    @Test
    void aHeldRowLockIsReportedAsLockContention() throws Exception {
        assumeTrue(DockerClientFactory.instance().isDockerAvailable(), "Docker unavailable");

        try (PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine")) {
            pg.start();
            try (Connection connection = open(pg);
                    Statement statement = connection.createStatement()) {
                statement.execute("CREATE TABLE fixture (id int PRIMARY KEY, n int)");
                statement.execute("INSERT INTO fixture VALUES (1, 0)");
            }

            Bottleneck bottleneck;
            // One session takes an exclusive row lock and holds it, uncommitted, for the whole
            // run. Every update the load issues against that row must therefore wait on it.
            try (Connection blocker = open(pg)) {
                blocker.setAutoCommit(false);
                try (Statement statement = blocker.createStatement()) {
                    statement.execute("SELECT * FROM fixture WHERE id = 1 FOR UPDATE");
                }

                DbConfig config = new DbConfig(new DbConfig.Target(conn(pg), "postgres",
                        List.of(new QuerySpec("UPDATE fixture SET n = n + 1 WHERE id = 1",
                                1, "write", null)), 4), 20);

                try (BottleneckSampler sampler = BottleneckSampler.open(conn(pg), "postgres")) {
                    sampler.start();
                    try (DbRunner runner = DbRunner.create(config, 4)) {
                        runner.run(RunContext.startingNow(4 * SECOND, 500 * MS, 0, 4));
                    }
                    bottleneck = sampler.stop();
                }
                blocker.rollback();
            }

            double lockShare = bottleneck.shareOfWaits("Lock");

            record("samples", "> 5", bottleneck.samples(), bottleneck.samples() > 5);
            record("dominant wait type", "Lock", bottleneck.dominantWaitType(),
                    "Lock".equals(bottleneck.dominantWaitType()));
            record("Lock share of waits", "> 40%", "%.1f%%".formatted(lockShare * 100),
                    lockShare > 0.4);
            record("verdict", Bottleneck.Verdict.LOCK_CONTENTION, bottleneck.verdict(),
                    bottleneck.verdict() == Bottleneck.Verdict.LOCK_CONTENTION);
            printLedger("lock contention, a row lock held for the whole run");

            assertThat(bottleneck.available()).isTrue();
            assertThat(bottleneck.dominantWaitType())
                    .withFailMessage("""
                            every update targeted a row held under an uncommitted FOR UPDATE, so \
                            the backends can only have been waiting on a lock. Waits seen: %s""",
                            bottleneck.waitsByType())
                    .isEqualTo("Lock");
            assertThat(bottleneck.verdict())
                    .withFailMessage("""
                            the LOCK_CONTENTION verdict had never been produced by a real server \
                            before this test. Waits: %s, plateau %d held %.0f%% of %d samples""",
                            bottleneck.waitsByType(), bottleneck.plateauBackends(),
                            bottleneck.plateauShare() * 100, bottleneck.samples())
                    .isEqualTo(Bottleneck.Verdict.LOCK_CONTENTION);
        }
    }

    // --- a real cache cliff ----------------------------------------------------------------------

    @Test
    void aTableOutgrowingSharedBuffersIsReportedAsACliff() throws Exception {
        assumeTrue(DockerClientFactory.instance().isDockerAvailable(), "Docker unavailable");

        // 4MB of buffers: 10,000 rows of ~130 bytes fit comfortably, 90,000 cannot. A block read
        // from the operating system's cache still counts as a read rather than a hit, so this
        // works regardless of how much the host is caching underneath.
        try (PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine")
                .withCommand("postgres", "-c", "shared_buffers=4MB")) {
            pg.start();
            try (Connection connection = open(pg);
                    Statement statement = connection.createStatement()) {
                statement.execute("CREATE TABLE fixture (id serial PRIMARY KEY, pad char(100))");
            }

            String query = "SELECT sum(length(pad)) FROM fixture";
            List<GrowthCurve.Point> points = new ArrayList<>();
            List<Double> hitRatios = new ArrayList<>();
            long sharedBuffers = 0;
            List<Long> tableBytes = new ArrayList<>();

            for (int rows : new int[] {10_000, 30_000, 90_000}) {
                try (Connection connection = open(pg);
                        Statement statement = connection.createStatement()) {
                    long present = scalar(connection, "SELECT count(*) FROM fixture");
                    statement.execute("INSERT INTO fixture (pad) SELECT repeat('x', 100) "
                            + "FROM generate_series(1, " + (rows - present) + ")");
                    statement.execute("VACUUM ANALYZE fixture");
                    tableBytes.add(scalar(connection, "SELECT pg_table_size('fixture')"));
                }

                DbConfig config = new DbConfig(new DbConfig.Target(conn(pg), "postgres",
                        List.of(new QuerySpec(query, 1, "read", null)), 4), 15);
                try (DbRunner runner = DbRunner.create(config, 4)) {
                    runner.run(RunContext.startingNow(2 * SECOND, 500 * MS, 0, 4));   // warm
                    var loop = runner.run(RunContext.startingNow(3 * SECOND, 500 * MS, 0, 4));
                    var summary = loop.series().summarize("db", 3 * SECOND);
                    var accounting = runner.resourceAccounting();
                    sharedBuffers = accounting.sharedBuffersBytes();
                    var relation = accounting.relations().stream()
                            .filter(r -> r.name().equals("fixture")).findFirst().orElseThrow();
                    hitRatios.add(relation.hitRatio());
                    points.add(new GrowthCurve.Point(relation.liveRows(),
                            summary.serviceP99Nanos(), summary.p99Nanos(),
                            accounting.bytesPerRequest(relation), relation.hitRatio(),
                            summary.achievedRatePerSecond()));
                }
            }

            GrowthCurve curve = GrowthCurve.fit(points);
            boolean smallestFits = tableBytes.get(0) < sharedBuffers;
            boolean largestDoesNot = tableBytes.get(2) > sharedBuffers;

            record("shared_buffers", "4MB",
                    com.lockstep.util.Numbers.bytes(sharedBuffers), sharedBuffers > 0);
            record("smallest table fits buffers", "yes", smallestFits ? "yes" : "no", smallestFits);
            record("largest table exceeds buffers", "yes", largestDoesNot ? "yes" : "no",
                    largestDoesNot);
            record("hit ratio 10k rows", "high", "%.3f".formatted(hitRatios.get(0)), true);
            record("hit ratio 90k rows", "< 10k's", "%.3f".formatted(hitRatios.get(2)),
                    hitRatios.get(2) < hitRatios.get(0));
            record("cliff detected", "yes", curve.spansCacheCliff() ? "yes" : "no",
                    curve.spansCacheCliff());
            record("extrapolation suppressed", "yes", curve.extrapolatable() ? "no" : "yes",
                    !curve.extrapolatable());
            printLedger("cache cliff, a table grown past 4MB of shared_buffers");

            assertThat(smallestFits)
                    .withFailMessage("the fixture is invalid unless the first step fits: table "
                            + "%d bytes, buffers %d", tableBytes.get(0), sharedBuffers)
                    .isTrue();
            assertThat(largestDoesNot)
                    .withFailMessage("the fixture is invalid unless the last step does not fit: "
                            + "table %d bytes, buffers %d", tableBytes.get(2), sharedBuffers)
                    .isTrue();
            assertThat(hitRatios.get(2))
                    .withFailMessage("""
                            the table grew from inside %d bytes of buffers to outside them, so \
                            the hit ratio must fall. Ratios by step: %s""",
                            sharedBuffers, hitRatios)
                    .isLessThan(hitRatios.get(0));
            assertThat(curve.spansCacheCliff())
                    .withFailMessage("""
                            the cliff branch had never been reached by a real server before this \
                            test. Hit ratios %s across rows %s""", hitRatios,
                            points.stream().map(GrowthCurve.Point::rows).toList())
                    .isTrue();
            assertThat(curve.extrapolatable())
                    .withFailMessage("a fit spanning a cliff must not be extrapolated from")
                    .isFalse();
        }
    }
}
