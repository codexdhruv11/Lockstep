package com.lockstep.runner.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.lockstep.analysis.ResourceAccounting;
import com.lockstep.config.DbConfig;
import com.lockstep.config.QuerySpec;
import com.lockstep.core.RunContext;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;

final class ResourceProbePostgresTest {
    private static final long MS = 1_000_000L;
    private static final long SECOND = 1_000_000_000L;

    private static String connectionString(PostgreSQLContainer<?> postgres) {
        return "postgres://%s:%s@%s:%d/%s".formatted(
                postgres.getUsername(), postgres.getPassword(),
                postgres.getHost(), postgres.getFirstMappedPort(), postgres.getDatabaseName());
    }

    @Test
    void measuresARealTablesSizeAndTheBlocksEachRequestReads() throws Exception {
        assumeTrue(DockerClientFactory.instance().isDockerAvailable(),
                "Docker is not available — Postgres resource accounting test skipped");

        try (PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")) {
            postgres.start();

            try (Connection connection = DriverManager.getConnection(
                        postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
                    Statement statement = connection.createStatement()) {
                statement.execute("CREATE TABLE signals (id SERIAL PRIMARY KEY, payload TEXT)");
                // One multi-row insert: 20k rows of ~200 bytes, so the table is a few MB and
                // spans many blocks. A row-at-a-time loop would fsync per statement and take
                // minutes.
                statement.execute("""
                        INSERT INTO signals (payload)
                        SELECT repeat('x', 200) FROM generate_series(1, 20000)
                        """);
                statement.execute("ANALYZE signals");
            }

            // Deliberately aggregates an UNINDEXED column. `SELECT count(*)` would be wrong
            // here: once the visibility map is all-visible, Postgres serves it from an
            // index-only scan of the primary key and touches a fraction of the blocks — so the
            // test would assert a plan it had not pinned and fail whenever autovacuum happened
            // to run first. Summing `payload` has to visit every heap block, always.
            DbConfig config = new DbConfig(new DbConfig.Target(connectionString(postgres),
                    "postgres", List.of(
                            new QuerySpec("SELECT sum(length(payload)) FROM signals", 1,
                                    "read", null))),
                    40);

            ResourceAccounting accounting;
            try (DbRunner runner = DbRunner.create(config, 4)) {
                assertThat(runner.resourceAccounting())
                        .withFailMessage("there is nothing to account for before a run")
                        .isNull();

                var loop = runner.run(RunContext.startingNow(2 * SECOND, 200 * MS, 0, 4));
                accounting = runner.resourceAccounting();

                // The block arithmetic divides by executed operations, so a run that shed load
                // or failed queries would make blocks-per-request meaningless. Assert the run
                // was clean before trusting anything derived from it.
                assertThat(loop.series().errorCount())
                        .withFailMessage("failed queries read no blocks but still count in the "
                                + "denominator: %s", loop.errorCounts())
                        .isZero();
                assertThat(loop.shedCount()).isZero();
                assertThat(loop.abandonedCount()).isZero();
                assertThat(accounting.requests()).isEqualTo(loop.executedCount());
            }

            assertThat(accounting).isNotNull();
            assertThat(accounting.available())
                    .withFailMessage("the probe should work against a stock postgres:16: %s",
                            accounting.unavailableReason())
                    .isTrue();

            assertThat(accounting.blockSizeBytes()).isEqualTo(8192);
            assertThat(accounting.sharedBuffersBytes())
                    .withFailMessage("shared_buffers must come back in bytes, not as a block "
                            + "count — a block count would read as 16KB of cache")
                    .isGreaterThan(8 * 1024 * 1024);

            var signals = accounting.relations().stream()
                    .filter(relation -> relation.name().equals("signals"))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError(
                            "the run read `signals` every request, so its block counters must "
                            + "have moved; got " + accounting.relations()));

            assertThat(signals.liveRows())
                    .withFailMessage("20,000 rows were inserted and ANALYZE ran, but the row "
                            + "count came back as %d (table %d bytes)",
                            signals.liveRows(), signals.tableBytes())
                    .isBetween(19_000L, 21_000L);
            assertThat(signals.tableBytes())
                    .withFailMessage("20,000 rows of ~200 bytes cannot fit in under 1MB")
                    .isGreaterThan(1024 * 1024);
            assertThat(signals.bytesPerRow()).isBetween(150L, 1000L);

            long blocksPerRequest = accounting.blocksPerRequest(signals);
            assertThat(blocksPerRequest)
                    .withFailMessage("aggregating an unindexed column must visit every heap "
                            + "block; got %d blocks per request against a %d byte table",
                            blocksPerRequest, signals.tableBytes())
                    .isGreaterThan(100);
            assertThat(accounting.readsWholeTablePerRequest(signals))
                    .withFailMessage("each request read %d bytes against a %d byte table "
                            + "[blocksTouched=%d requests=%d blocksPerReq=%d blockSize=%d]",
                            accounting.bytesPerRequest(signals), signals.tableBytes(),
                            signals.blocksTouched(), accounting.requests(),
                            accounting.blocksPerRequest(signals), accounting.blockSizeBytes())
                    .isTrue();
            assertThat(accounting.bufferBytesPerSecond(signals))
                    .withFailMessage("bandwidth must follow from a non-zero rate")
                    .isGreaterThan(0);
        }
    }

    @Test
    void anIndexedLookupDoesNotLookLikeATableScan() throws Exception {
        assumeTrue(DockerClientFactory.instance().isDockerAvailable(),
                "Docker is not available — Postgres resource accounting test skipped");

        try (PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")) {
            postgres.start();

            try (Connection connection = DriverManager.getConnection(
                        postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
                    Statement statement = connection.createStatement()) {
                statement.execute("CREATE TABLE signals (id SERIAL PRIMARY KEY, payload TEXT)");
                statement.execute("""
                        INSERT INTO signals (payload)
                        SELECT repeat('x', 200) FROM generate_series(1, 20000)
                        """);
                statement.execute("ANALYZE signals");
            }

            DbConfig config = new DbConfig(new DbConfig.Target(connectionString(postgres),
                    "postgres", List.of(
                            new QuerySpec("SELECT payload FROM signals WHERE id = 500", 1,
                                    "read", null))),
                    40);

            ResourceAccounting accounting;
            try (DbRunner runner = DbRunner.create(config, 4)) {
                runner.run(RunContext.startingNow(2 * SECOND, 200 * MS, 0, 4));
                accounting = runner.resourceAccounting();
            }

            var signals = accounting.relations().stream()
                    .filter(relation -> relation.name().equals("signals"))
                    .findFirst()
                    .orElseThrow();

            assertThat(accounting.blocksPerRequest(signals))
                    .withFailMessage("a primary-key lookup should touch a handful of blocks, not "
                            + "the whole table — if this is large the measurement is wrong, not "
                            + "the query")
                    .isLessThan(20);
            assertThat(accounting.readsWholeTablePerRequest(signals))
                    .withFailMessage("flagging an index lookup as a table walk would make the "
                            + "warning worthless")
                    .isFalse();
        }
    }

    @Test
    void aDriverWithoutStatisticsViewsSaysSoRatherThanReportingZeroes() {
        DbConfig config = new DbConfig(new DbConfig.Target(
                "jdbc:sqlite::memory:", "sqlite",
                List.of(new QuerySpec("SELECT 1", 1, "read", null))), 20);

        ResourceAccounting accounting;
        try (DbRunner runner = DbRunner.create(config, 2)) {
            runner.run(RunContext.startingNow(SECOND, 200 * MS, 0, 2));
            accounting = runner.resourceAccounting();
        }

        assertThat(accounting.available()).isFalse();
        assertThat(accounting.unavailableReason()).contains("sqlite");
        assertThat(accounting.hasRelations()).isFalse();
    }
}
