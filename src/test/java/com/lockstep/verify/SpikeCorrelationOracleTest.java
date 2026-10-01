package com.lockstep.verify;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.lockstep.analysis.SpikeCorrelator;
import com.lockstep.config.Config;
import com.lockstep.config.DbConfig;
import com.lockstep.config.HttpConfig;
import com.lockstep.config.QuerySpec;
import com.lockstep.core.RunContext;
import com.lockstep.core.RunCoordinator;
import com.sun.net.httpserver.HttpServer;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Checks spike correlation against a fault whose timing and size are decided in advance.
 *
 * <p>"Is the cause the app, the database, or the cache?" is the claim this project leads with, and
 * until now it rested on 13 unit tests over hand-built buckets. Hand-built buckets cannot catch a
 * verdict that is right about constructed input and wrong about a real one - that is exactly how
 * the bottleneck verdict came to report a full connection pool while the target was spending all
 * of its time waiting on locks.
 *
 * <p>So the fault here is injected into a real database for a known span of wall clock. A gate
 * table holds two timestamps and the query the db runner issues sleeps only between them:
 *
 * <pre>
 *   SELECT CASE WHEN clock_timestamp() BETWEEN g.slow_from AND g.slow_until
 *               THEN pg_sleep(0.25) ELSE 'fast' END FROM gate g
 * </pre>
 *
 * <p>Outside the window that query returns in about 1.5ms and inside it takes about 260ms, both
 * measured directly against a container before this test was written. Lockstep's buckets are
 * indexed by scheduled time, so which buckets must show the fault follows from arithmetic.
 *
 * <p>Two clocks are involved and they are not the same clock: {@code clock_timestamp()} is the
 * container's and the run's offsets are the JVM's. The offset between them is measured rather than
 * assumed, and the expected buckets are then derived from the run's actual start and the gate's
 * actual values - both read back after the fact - rather than from what was planned. A bucket only
 * partly covered by the window may or may not show the fault depending on where its requests fell,
 * so the assertions are made on the buckets wholly inside the window and the buckets wholly
 * outside it.
 */
@Tag("container")
final class SpikeCorrelationOracleTest {

    private static final long MS = 1_000_000L;
    private static final long SECOND = 1_000_000_000L;

    private static final long SLEEP_MILLIS = 250;
    private static final long BUCKET_WIDTH = SECOND;
    private static final long RUN_NANOS = 11 * SECOND;

    /** Where the fault sits inside the run, as offsets from the run's start. */
    private static final long FAULT_FROM_NANOS = 2 * SECOND;
    private static final long FAULT_UNTIL_NANOS = 7 * SECOND;

    /** Covers the gate INSERT and the runners being built before the run clock is set. */
    private static final long WRITE_ALLOWANCE_NANOS = 150 * MS;

    private static final String GATED_QUERY = """
            SELECT CASE WHEN clock_timestamp() >= g.slow_from
                         AND clock_timestamp() <  g.slow_until
                        THEN pg_sleep(%s)::text ELSE 'fast' END AS r
            FROM gate g""".formatted(SLEEP_MILLIS / 1000.0);

    private static final List<String> LEDGER = new ArrayList<>();

    private static void record(String claim, Object expected, Object reported, boolean ok) {
        LEDGER.add("  %-42s expected %-20s reported %-20s %s".formatted(
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

    private static String ms(long nanos) {
        return "%.0fms".formatted(nanos / 1_000_000.0);
    }

    // --- the two clocks -------------------------------------------------------------------------

    /**
     * How far the database's clock sits from this JVM's, measured by bracketing one
     * {@code clock_timestamp()} between two {@code Instant.now()} calls. Add it to a database
     * timestamp to get a JVM instant.
     */
    private static Duration clockOffset(Connection db) throws Exception {
        Duration best = null;
        Duration bestWidth = null;
        for (int attempt = 0; attempt < 5; attempt++) {
            Instant before = Instant.now();
            Timestamp dbNow;
            try (Statement statement = db.createStatement();
                    ResultSet rows = statement.executeQuery("SELECT clock_timestamp()")) {
                rows.next();
                dbNow = rows.getTimestamp(1);
            }
            Instant after = Instant.now();
            Duration width = Duration.between(before, after);
            if (bestWidth == null || width.compareTo(bestWidth) < 0) {
                // The database read that instant somewhere inside the bracket; its midpoint is
                // the best estimate, and the narrowest bracket is the best measurement.
                Instant midpoint = before.plusNanos(width.toNanos() / 2);
                best = Duration.between(dbNow.toInstant(), midpoint);
                bestWidth = width;
            }
        }
        return best;
    }

    private record Window(long fromOffsetNanos, long untilOffsetNanos) {
        /** Buckets the fault covered end to end: these must show it. */
        Set<Integer> fullyInside(int bucketCount, long width) {
            Set<Integer> inside = new LinkedHashSet<>();
            for (int i = 0; i < bucketCount; i++) {
                if ((long) i * width >= fromOffsetNanos
                        && (long) (i + 1) * width <= untilOffsetNanos) {
                    inside.add(i);
                }
            }
            return inside;
        }

        /** Buckets the fault never touched: these must not show it. */
        Set<Integer> fullyOutside(int bucketCount, long width) {
            Set<Integer> outside = new LinkedHashSet<>();
            for (int i = 0; i < bucketCount; i++) {
                if ((long) (i + 1) * width <= fromOffsetNanos
                        || (long) i * width >= untilOffsetNanos) {
                    outside.add(i);
                }
            }
            return outside;
        }
    }

    /** Writes the gate, then reports where the fault really landed relative to the run's start. */
    private static Window installGate(Connection db, Duration offset, Instant plannedStart)
            throws Exception {
        Instant fromJvm = plannedStart.plusNanos(FAULT_FROM_NANOS);
        Instant untilJvm = plannedStart.plusNanos(FAULT_UNTIL_NANOS);
        try (Statement statement = db.createStatement()) {
            statement.execute("DROP TABLE IF EXISTS gate");
            statement.execute("CREATE TABLE gate(slow_from timestamptz, slow_until timestamptz)");
        }
        try (var insert = db.prepareStatement("INSERT INTO gate VALUES (?, ?)")) {
            insert.setTimestamp(1, Timestamp.from(fromJvm.minus(offset)));
            insert.setTimestamp(2, Timestamp.from(untilJvm.minus(offset)));
            insert.executeUpdate();
        }
        return null;
    }

    /** Reads the gate back and expresses it as offsets from the run's actual start. */
    private static Window measuredWindow(Connection db, Duration offset, Instant actualStart)
            throws Exception {
        try (Statement statement = db.createStatement();
                ResultSet rows = statement.executeQuery(
                        "SELECT slow_from, slow_until FROM gate")) {
            rows.next();
            Instant from = rows.getTimestamp(1).toInstant().plus(offset);
            Instant until = rows.getTimestamp(2).toInstant().plus(offset);
            return new Window(Duration.between(actualStart, from).toNanos(),
                    Duration.between(actualStart, until).toNanos());
        }
    }

    // --- targets --------------------------------------------------------------------------------

    /** An HTTP target that does nothing, so the app tier is demonstrably innocent. */
    private static HttpServer fastServer() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/ping", exchange -> {
            byte[] body = "ok".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
        return server;
    }

    /**
     * An HTTP target whose handler runs the same gated query, so the app is slow only because the
     * database is. Connections are opened up front: opening one per request would add tens of
     * milliseconds of its own and change what the fixture is testing.
     */
    private static HttpServer dbBackedServer(PostgreSQLContainer<?> pg, int connections)
            throws Exception {
        return dbBackedServer(pg, connections, 0);
    }

    private static HttpServer dbBackedServer(PostgreSQLContainer<?> pg, int connections,
            long ownWorkMillis) throws Exception {
        BlockingQueue<Connection> pool = new ArrayBlockingQueue<>(connections);
        for (int i = 0; i < connections; i++) {
            pool.add(open(pg));
        }
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/query", exchange -> {
            int status = 200;
            Connection borrowed = null;
            try {
                if (ownWorkMillis > 0) {
                    Thread.sleep(ownWorkMillis);
                }
                borrowed = pool.take();
                try (Statement statement = borrowed.createStatement();
                        ResultSet rows = statement.executeQuery(GATED_QUERY)) {
                    rows.next();
                }
            } catch (Exception e) {
                status = 500;
            } finally {
                if (borrowed != null) {
                    pool.offer(borrowed);
                }
            }
            byte[] body = "ok".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
        return server;
    }

    private static Config configFor(String httpUrl, PostgreSQLContainer<?> pg, int rate) {
        return new Config(RUN_NANOS, BUCKET_WIDTH, 0, 8,
                new HttpConfig(new HttpConfig.Target("GET", httpUrl, null, Map.of(), 1), rate),
                new DbConfig(new DbConfig.Target(conn(pg), "postgres",
                        List.of(new QuerySpec(GATED_QUERY, 1, "read", null)), 8), rate),
                null, List.of());
    }

    // --- a storage fault the app never felt ----------------------------------------------------

    /**
     * The headline case. The database is made slow for a known five seconds while the HTTP target
     * is a server that does nothing at all, so the correct answer is fixed in advance: a db spike
     * in those buckets, no spike anywhere else, and every one of them masked, because a user of
     * the app would not have noticed.
     */
    @Test
    void aDatabaseFaultIsReportedInTheSecondsItHappenedAndAttributedToTheDatabase()
            throws Exception {
        assumeTrue(DockerClientFactory.instance().isDockerAvailable(), "Docker unavailable");

        try (PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine")) {
            pg.start();
            HttpServer app = fastServer();
            try (Connection db = open(pg)) {
                Duration offset = clockOffset(db);
                Instant plannedStart = Instant.now()
                        .plusNanos(RunContext.SETUP_GRACE_NANOS + WRITE_ALLOWANCE_NANOS);
                installGate(db, offset, plannedStart);

                Config config = configFor(
                        "http://localhost:" + app.getAddress().getPort() + "/ping", pg, 20);
                RunCoordinator.RunResult result = RunCoordinator.execute(config, null, 0, 0);

                Instant actualStart = result.context().startWallClock();
                Window window = measuredWindow(db, offset, actualStart);
                int buckets = (int) (RUN_NANOS / BUCKET_WIDTH);
                Set<Integer> mustSpike = window.fullyInside(buckets, BUCKET_WIDTH);
                Set<Integer> mustNotSpike = window.fullyOutside(buckets, BUCKET_WIDTH);

                var correlation = SpikeCorrelator.correlate(result, SpikeCorrelator.Thresholds.defaults());

                Set<Integer> reported = new LinkedHashSet<>();
                correlation.spikes().forEach(spike -> reported.add(spike.bucketIndex()));

                long drift = Duration.between(plannedStart, actualStart).toMillis();
                System.out.println("  clock offset db->jvm: " + offset.toMillis()
                        + "ms, start prediction off by " + drift + "ms, fault at "
                        + ms(window.fromOffsetNanos()) + ".." + ms(window.untilOffsetNanos()));

                record("buckets inside the fault window", mustSpike,
                        reported.stream().filter(mustSpike::contains).toList(),
                        reported.containsAll(mustSpike));
                record("buckets outside it", "none of " + mustNotSpike,
                        reported.stream().filter(mustNotSpike::contains).toList(),
                        mustNotSpike.stream().noneMatch(reported::contains));
                record("tier blamed", "db",
                        correlation.spikes().stream()
                                .map(SpikeCorrelator.Spike::storageRunner).distinct().toList(),
                        correlation.spikes().stream()
                                .allMatch(spike -> "db".equals(spike.storageRunner())));
                record("verdict", SpikeCorrelator.Verdict.DB,
                        correlation.spikes().stream()
                                .map(SpikeCorrelator.Spike::verdict).distinct().toList(),
                        correlation.spikes().stream()
                                .allMatch(spike -> spike.verdict() == SpikeCorrelator.Verdict.DB));
                record("app unaffected, so masked", true,
                        correlation.masked().size() + " of " + correlation.spikes().size(),
                        correlation.masked().size() == correlation.spikes().size());
                long worst = correlation.spikes().stream()
                        .mapToLong(SpikeCorrelator.Spike::storageP99Nanos).max().orElse(0);
                record("size of the fault", "~" + SLEEP_MILLIS + "ms", ms(worst),
                        worst >= SLEEP_MILLIS * MS && worst <= 2 * SLEEP_MILLIS * MS);
                printLedger("a %ds database fault in an %ds run"
                        .formatted((FAULT_UNTIL_NANOS - FAULT_FROM_NANOS) / SECOND,
                                RUN_NANOS / SECOND));

                assertThat(mustSpike)
                        .withFailMessage("the fault window covered no bucket end to end; the "
                                + "fixture, not the code, is at fault")
                        .isNotEmpty();

                assertThat(reported)
                        .withFailMessage("""
                                the database was slow for the whole of buckets %s and the \
                                correlator reported spikes in %s. A fault that is not reported in \
                                the second it happened cannot be matched against anything else in \
                                the report.""", mustSpike, reported)
                        .containsAll(mustSpike);

                assertThat(reported)
                        .withFailMessage("""
                                buckets %s were outside the fault window entirely and the \
                                correlator reported %s. Blaming a quiet second for a spike sends \
                                a reader looking at the wrong part of their own logs.""",
                                mustNotSpike, reported)
                        .doesNotContainAnyElementsOf(mustNotSpike);

                for (var spike : correlation.spikes()) {
                    assertThat(spike.storageRunner())
                            .withFailMessage("only the db runner was made slow")
                            .isEqualTo("db");
                    assertThat(spike.verdict())
                            .withFailMessage("""
                                    the app was a server that returns a constant and the database \
                                    was sleeping %dms a query, so the database is the answer. \
                                    Bucket %d reported %s with app p99 %s against db p99 %s.""",
                                    SLEEP_MILLIS, spike.bucketIndex(), spike.verdict(),
                                    ms(spike.appP99Nanos()), ms(spike.storageP99Nanos()))
                            .isEqualTo(SpikeCorrelator.Verdict.DB);
                    assertThat(spike.masked())
                            .withFailMessage("""
                                    the app's p99 in bucket %d was %s, far under the %s threshold \
                                    - a user of the app would not have noticed this fault, which \
                                    is what masked means. Reporting it as user-visible would send \
                                    someone chasing an outage that did not happen.""",
                                    spike.bucketIndex(), ms(spike.appP99Nanos()),
                                    ms(SpikeCorrelator.Thresholds.defaults().httpNanos()))
                            .isTrue();
                    assertThat(spike.storageP99Nanos())
                            .withFailMessage("a %dms sleep should show as about that, not %s",
                                    SLEEP_MILLIS, ms(spike.storageP99Nanos()))
                            .isBetween(SLEEP_MILLIS * MS, 2 * SLEEP_MILLIS * MS);
                }

                assertThat(correlation.correlated())
                        .withFailMessage("nothing reached the app, so nothing was correlated")
                        .isEmpty();
                assertThat(correlation.masked()).isNotEmpty();
                assertThat(correlation.appReferencePresent()).isTrue();
            } finally {
                app.stop(0);
            }
        }
    }

    // --- a storage fault the app did feel ------------------------------------------------------

    /**
     * The other half of the claim. Here the HTTP endpoint runs the gated query itself, so the app
     * is slow for exactly one reason: the database is. The spike must not be masked - a user felt
     * this one - and the database must not be let off.
     */
    @Test
    void whenTheAppIsSlowBecauseTheDatabaseIsTheSpikeIsNotMasked() throws Exception {
        assumeTrue(DockerClientFactory.instance().isDockerAvailable(), "Docker unavailable");

        try (PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine")) {
            pg.start();
            try (Connection db = open(pg)) {
                Duration offset = clockOffset(db);
                Instant plannedStart = Instant.now()
                        .plusNanos(RunContext.SETUP_GRACE_NANOS + WRITE_ALLOWANCE_NANOS);
                installGate(db, offset, plannedStart);
                HttpServer app = dbBackedServer(pg, 12);
                try {
                    Config config = configFor(
                            "http://localhost:" + app.getAddress().getPort() + "/query", pg, 15);
                    RunCoordinator.RunResult result =
                            RunCoordinator.execute(config, null, 0, 0);

                    Window window = measuredWindow(db, offset, result.context().startWallClock());
                    int buckets = (int) (RUN_NANOS / BUCKET_WIDTH);
                    Set<Integer> mustSpike = window.fullyInside(buckets, BUCKET_WIDTH);

                    var correlation = SpikeCorrelator.correlate(
                            result, SpikeCorrelator.Thresholds.defaults());
                    Set<Integer> reported = new LinkedHashSet<>();
                    correlation.spikes().forEach(s -> reported.add(s.bucketIndex()));

                    List<SpikeCorrelator.Spike> inFault = correlation.spikes().stream()
                            .filter(s -> mustSpike.contains(s.bucketIndex())).toList();

                    record("buckets inside the fault window", mustSpike,
                            reported.stream().filter(mustSpike::contains).toList(),
                            reported.containsAll(mustSpike));
                    record("app felt it, so not masked", "none masked",
                            inFault.stream().filter(SpikeCorrelator.Spike::masked).count()
                                    + " of " + inFault.size() + " masked",
                            inFault.stream().noneMatch(SpikeCorrelator.Spike::masked));
                    record("verdict blames storage, not the app", "DB or EVEN",
                            inFault.stream().map(SpikeCorrelator.Spike::verdict).distinct().toList(),
                            inFault.stream().noneMatch(
                                    s -> s.verdict() == SpikeCorrelator.Verdict.HTTP));
                    var worst = inFault.stream()
                            .max((a, b) -> Long.compare(a.appP99Nanos(), b.appP99Nanos()));
                    record("app p99 tracks the db p99",
                            "both about " + SLEEP_MILLIS + "ms",
                            worst.map(s -> "app " + ms(s.appP99Nanos())
                                    + " / db " + ms(s.storageP99Nanos())).orElse("none"),
                            worst.isPresent());
                    printLedger("the endpoint itself waits on the slow query");

                    assertThat(mustSpike).isNotEmpty();
                    assertThat(reported).containsAll(mustSpike);
                    assertThat(inFault).isNotEmpty();

                    assertThat(inFault)
                            .withFailMessage("""
                                    every request to the endpoint waited on the slow query, so \
                                    the app's own p99 was over the threshold too. Calling that \
                                    masked would tell a reader no user noticed, when every one \
                                    of them did. Spikes were %s""", inFault)
                            .noneMatch(SpikeCorrelator.Spike::masked);

                    assertThat(inFault)
                            .withFailMessage("""
                                    the app's only work was waiting on the database, so the \
                                    database is the answer or the two are even. Blaming the app \
                                    for time it spent blocked on storage is the wrong tier, and \
                                    it is the whole question this feature exists to answer. \
                                    Spikes were %s""", inFault)
                            .noneMatch(spike -> spike.verdict() == SpikeCorrelator.Verdict.HTTP);
                } finally {
                    app.stop(0);
                }
            }
        }
    }


    /**
     * The case a bare comparison of the two latencies cannot get right, and the reason the
     * verdict is computed from each tier's growth over its own baseline.
     *
     * <p>The endpoint here always costs 200ms of its own before it touches the database. During
     * the fault it costs about 450ms while the database costs about 255ms. Compared directly the
     * app is the larger number and would be blamed. Compared as growth - 250ms of app against
     * 250ms of database, over baselines of 200ms and 2ms - the database accounts for all of it,
     * which is the truth: the 200ms was there the whole time and did not cause the spike.
     */
    @Test
    void anAppWithAFixedCostOfItsOwnDoesNotGetBlamedForTheDatabasesFault() throws Exception {
        assumeTrue(DockerClientFactory.instance().isDockerAvailable(), "Docker unavailable");

        try (PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine")) {
            pg.start();
            try (Connection db = open(pg)) {
                Duration offset = clockOffset(db);
                Instant plannedStart = Instant.now()
                        .plusNanos(RunContext.SETUP_GRACE_NANOS + WRITE_ALLOWANCE_NANOS);
                installGate(db, offset, plannedStart);
                HttpServer app = dbBackedServer(pg, 12, 200);
                try {
                    Config config = configFor(
                            "http://localhost:" + app.getAddress().getPort() + "/query", pg, 10);
                    RunCoordinator.RunResult result =
                            RunCoordinator.execute(config, null, 0, 0);

                    Window window = measuredWindow(db, offset, result.context().startWallClock());
                    int buckets = (int) (RUN_NANOS / BUCKET_WIDTH);
                    Set<Integer> mustSpike = window.fullyInside(buckets, BUCKET_WIDTH);

                    var httpBuckets = result.byRunner().get("http").series().buckets();
                    var dbBuckets = result.byRunner().get("db").series().buckets();
                    long appBaseline = SpikeCorrelator.baselineOf(httpBuckets, 0);
                    long dbBaseline = SpikeCorrelator.baselineOf(dbBuckets, 0);

                    var correlation = SpikeCorrelator.correlate(
                            result, SpikeCorrelator.Thresholds.defaults());
                    List<SpikeCorrelator.Spike> inFault = correlation.spikes().stream()
                            .filter(s -> mustSpike.contains(s.bucketIndex())).toList();

                    record("app baseline (its own fixed cost)", "~200ms", ms(appBaseline),
                            appBaseline >= 150 * MS && appBaseline <= 300 * MS);
                    record("db baseline", "a few ms", ms(dbBaseline), dbBaseline < 50 * MS);
                    var worst = inFault.stream()
                            .max((a, b) -> Long.compare(a.appP99Nanos(), b.appP99Nanos()));
                    record("absolute p99s would blame the app",
                            "app > db",
                            worst.map(sp -> "app " + ms(sp.appP99Nanos())
                                    + " / db " + ms(sp.storageP99Nanos())).orElse("none"),
                            worst.isPresent()
                                    && worst.get().appP99Nanos() > worst.get().storageP99Nanos());
                    record("growth over baseline is even",
                            "both ~" + SLEEP_MILLIS + "ms",
                            worst.map(sp -> "app "
                                    + ms(sp.appP99Nanos() - appBaseline) + " / db "
                                    + ms(sp.storageP99Nanos() - dbBaseline)).orElse("none"),
                            worst.isPresent());
                    record("verdict", SpikeCorrelator.Verdict.DB,
                            inFault.stream().map(SpikeCorrelator.Spike::verdict)
                                    .distinct().toList(),
                            inFault.stream().allMatch(
                                    sp -> sp.verdict() == SpikeCorrelator.Verdict.DB));
                    printLedger("an endpoint with 200ms of its own work, plus the database fault");

                    assertThat(inFault).isNotEmpty();

                    // The premise: without a baseline this is the app's fault on the numbers.
                    assertThat(worst.orElseThrow().appP99Nanos())
                            .withFailMessage("the fixture is meant to make the app the larger "
                                    + "number; if it is not, it proves nothing")
                            .isGreaterThan(worst.get().storageP99Nanos());

                    assertThat(inFault)
                            .withFailMessage("""
                                    the endpoint's 200ms of own work was there for the whole run                                     and did not cause the spike - the database's jump from %s to                                     about %dms did. Blaming the app sends someone profiling an                                     endpoint whose own cost never changed. Spikes were %s""",
                                    ms(dbBaseline), SLEEP_MILLIS, inFault)
                            .allMatch(sp -> sp.verdict() == SpikeCorrelator.Verdict.DB);
                } finally {
                    app.stop(0);
                }
            }
        }
    }

    // --- an app fault with healthy storage -----------------------------------------------------

    /**
     * The case the feature cannot answer, pinned down so it stays a known limitation rather than a
     * surprise. The correlator only ever starts from a storage bucket that crossed its threshold,
     * so when the app is slow and storage is fine there is nothing for it to report - and it
     * reports nothing, which reads as "no problem found" rather than "the app is the problem".
     */
    @Test
    void anAppOnlyFaultProducesNoSpikeAtAllWhichIsAStatedLimitation() throws Exception {
        assumeTrue(DockerClientFactory.instance().isDockerAvailable(), "Docker unavailable");

        try (PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine")) {
            pg.start();
            try (Connection db = open(pg)) {
                // A gate window far past the end of the run: the database stays fast throughout.
                Duration offset = clockOffset(db);
                installGate(db, offset, Instant.now().plusSeconds(600));

                HttpServer slowApp = HttpServer.create(new InetSocketAddress(0), 0);
                slowApp.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
                slowApp.createContext("/slow", exchange -> {
                    try {
                        Thread.sleep(300);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    byte[] body = "ok".getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(200, body.length);
                    try (OutputStream out = exchange.getResponseBody()) {
                        out.write(body);
                    }
                });
                slowApp.start();
                try {
                    Config config = new Config(5 * SECOND, BUCKET_WIDTH, 0, 8,
                            new HttpConfig(new HttpConfig.Target("GET",
                                    "http://localhost:" + slowApp.getAddress().getPort() + "/slow",
                                    null, Map.of(), 1), 10),
                            new DbConfig(new DbConfig.Target(conn(pg), "postgres",
                                    List.of(new QuerySpec(GATED_QUERY, 1, "read", null)), 4), 10),
                            null, List.of());
                    RunCoordinator.RunResult result =
                            RunCoordinator.execute(config, null, 0, 0);

                    var correlation = SpikeCorrelator.correlate(
                            result, SpikeCorrelator.Thresholds.defaults());
                    long appP99 = result.byRunner().get("http").series()
                            .summarize("http", 5 * SECOND).p99Nanos();
                    long dbP99 = result.byRunner().get("db").series()
                            .summarize("db", 5 * SECOND).p99Nanos();

                    record("app p99", "~300ms", ms(appP99),
                            appP99 >= 250 * MS);
                    record("db p99", "a few ms", ms(dbP99), dbP99 < 100 * MS);
                    record("spikes reported", "none - storage was healthy",
                            correlation.spikes().size(), correlation.spikes().isEmpty());
                    printLedger("the app is slow and storage is innocent");

                    assertThat(appP99).isGreaterThanOrEqualTo(250 * MS);
                    assertThat(dbP99)
                            .withFailMessage("the gate window was 10 minutes away; the database "
                                    + "should have been fast throughout")
                            .isLessThan(100 * MS);
                    assertThat(correlation.spikes())
                            .withFailMessage("""
                                    correlation starts from a storage bucket over the threshold. \
                                    Storage was healthy, so there is nothing to correlate and \
                                    nothing is reported. Documented so that the silence is not \
                                    mistaken for a clean bill of health: the app tier's own \
                                    latency is in the main table, and the capacity and bottleneck \
                                    sections are what speak to it. Got %s""",
                                    correlation.spikes())
                            .isEmpty();
                } finally {
                    slowApp.stop(0);
                }
            }
        }
    }
}
