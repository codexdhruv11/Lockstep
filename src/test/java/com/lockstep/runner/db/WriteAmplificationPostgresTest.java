package com.lockstep.runner.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.lockstep.analysis.WriteAmplification;
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
import org.junit.jupiter.api.Tag;

@Tag("container")
final class WriteAmplificationPostgresTest {
    private static final long MS = 1_000_000L;
    private static final long SECOND = 1_000_000_000L;

    private static String connectionString(PostgreSQLContainer<?> postgres) {
        return "postgres://%s:%s@%s:%d/%s".formatted(
                postgres.getUsername(), postgres.getPassword(),
                postgres.getHost(), postgres.getFirstMappedPort(), postgres.getDatabaseName());
    }

    private static void createTables(PostgreSQLContainer<?> postgres) throws Exception {
        try (Connection connection = DriverManager.getConnection(
                    postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
                Statement statement = connection.createStatement()) {
            for (String name : List.of("light", "heavy")) {
                statement.execute("CREATE TABLE " + name + " (id SERIAL PRIMARY KEY, "
                        + "a INT, b INT, c INT, d INT, e INT, f INT, payload TEXT)");
            }
            // `light` keeps only its primary key. `heavy` gets twelve more, which is the shape of
            // a table that has accumulated an index per query over time.
            for (String column : List.of("a", "b", "c", "d", "e", "f")) {
                statement.execute("CREATE INDEX heavy_" + column + " ON heavy (" + column + ")");
            }
            statement.execute("CREATE INDEX heavy_ab ON heavy (a, b)");
            statement.execute("CREATE INDEX heavy_bc ON heavy (b, c)");
            statement.execute("CREATE INDEX heavy_cd ON heavy (c, d)");
            statement.execute("CREATE INDEX heavy_de ON heavy (d, e)");
            statement.execute("CREATE INDEX heavy_payload ON heavy (payload)");
            statement.execute("CREATE INDEX heavy_abc ON heavy (a, b, c)");
            statement.execute("ANALYZE light");
            statement.execute("ANALYZE heavy");
        }
    }

    private static final String INSERT_TEMPLATE = """
            INSERT INTO %s (a, b, c, d, e, f, payload)
            SELECT floor(random() * 100000)::int, floor(random() * 100000)::int,
                   floor(random() * 100000)::int, floor(random() * 100000)::int,
                   floor(random() * 100000)::int, floor(random() * 100000)::int,
                   repeat('x', 200)
            """;

    private static WriteAmplification insertInto(PostgreSQLContainer<?> postgres, String table) {
        DbConfig config = new DbConfig(new DbConfig.Target(connectionString(postgres), "postgres",
                List.of(new QuerySpec(INSERT_TEMPLATE.formatted(table), 1, "write", null))), 200);

        try (DbRunner runner = DbRunner.create(config, 4)) {
            runner.run(RunContext.startingNow(3 * SECOND, 500 * MS, 0, 4));
            return runner.writeAmplification();
        }
    }

    @Test
    void measuresWhatOneInsertActuallyCosts() throws Exception {
        assumeTrue(DockerClientFactory.instance().isDockerAvailable(),
                "Docker is not available — write amplification test skipped");

        try (PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")) {
            postgres.start();
            createTables(postgres);

            WriteAmplification amplification = insertInto(postgres, "heavy");

            assertThat(amplification).isNotNull();
            assertThat(amplification.available())
                    .withFailMessage("pg_stat_wal is available on a stock postgres:16: %s",
                            amplification.unavailableReason())
                    .isTrue();
            assertThat(amplification.hasWrites())
                    .withFailMessage("the run did nothing but insert, so writes must be counted")
                    .isTrue();

            var heavy = amplification.byWritesDescending().stream()
                    .filter(relation -> relation.name().equals("heavy"))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError(
                            "inserts into `heavy` must show up in its tuple counters; got "
                            + amplification.relations()));

            assertThat(heavy.inserts()).isGreaterThan(50);
            assertThat(heavy.updates()).isZero();
            assertThat(heavy.indexCount())
                    .withFailMessage("heavy has a primary key plus twelve indexes")
                    .isEqualTo(13);
            assertThat(heavy.partialIndexCount()).isZero();

            assertThat(amplification.walBytes())
                    .withFailMessage("inserting rows must produce write-ahead log bytes")
                    .isGreaterThan(0);
            assertThat(amplification.walBytesPerWrite()).isGreaterThan(0);
            assertThat(amplification.walRecordsPerWrite())
                    .withFailMessage("one insert into a thirteen-index table cannot be one WAL "
                            + "record; got %.2f", amplification.walRecordsPerWrite())
                    .isGreaterThan(1.0);
            assertThat(amplification.amplificationFactor())
                    .withFailMessage("the log must carry more than the row's own bytes when "
                            + "thirteen indexes are maintained; factor was %.2f",
                            amplification.amplificationFactor())
                    .isGreaterThan(1.0);
        }
    }

    @Test
    void anIndexHeavyTableCostsMorePerInsertThanAnIndexLightOne() throws Exception {
        assumeTrue(DockerClientFactory.instance().isDockerAvailable(),
                "Docker is not available — write amplification test skipped");

        try (PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")) {
            postgres.start();
            createTables(postgres);

            // Run order alternates so that neither table is systematically favoured by a
            // checkpoint landing in one of the windows.
            WriteAmplification light = insertInto(postgres, "light");
            WriteAmplification heavy = insertInto(postgres, "heavy");

            long lightPerWrite = light.walBytesPerWrite();
            long heavyPerWrite = heavy.walBytesPerWrite();

            assertThat(lightPerWrite).isGreaterThan(0);
            assertThat(heavyPerWrite)
                    .withFailMessage("""
                            thirteen indexes must cost more WAL per insert than one:
                              light (1 index):  %d bytes/write over %d writes
                              heavy (13 index): %d bytes/write over %d writes""",
                            lightPerWrite, light.totalLogicalWrites(),
                            heavyPerWrite, heavy.totalLogicalWrites())
                    .isGreaterThan(lightPerWrite);

            assertThat(light.byWritesDescending().get(0).indexCount()).isEqualTo(1);
            assertThat(heavy.byWritesDescending().get(0).indexCount()).isEqualTo(13);
        }
    }

    @Test
    void aReadOnlyRunReportsNoWritesRatherThanZeroCost() throws Exception {
        assumeTrue(DockerClientFactory.instance().isDockerAvailable(), "Docker unavailable");

        try (PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")) {
            postgres.start();
            createTables(postgres);

            DbConfig config = new DbConfig(new DbConfig.Target(connectionString(postgres),
                    "postgres", List.of(
                            new QuerySpec("SELECT count(*) FROM heavy", 1, "read", null))), 40);

            WriteAmplification amplification;
            try (DbRunner runner = DbRunner.create(config, 4)) {
                runner.run(RunContext.startingNow(SECOND, 250 * MS, 0, 4));
                amplification = runner.writeAmplification();
            }

            assertThat(amplification.available()).isTrue();
            assertThat(amplification.hasWrites())
                    .withFailMessage("a read-only run wrote nothing, and reporting a write cost "
                            + "of zero would be a different claim: %s",
                            amplification.relations())
                    .isFalse();
            assertThat(amplification.amplificationFactor()).isNegative();
        }
    }

    @Test
    void aDriverWithoutTheStatisticsViewsSaysSo() {
        DbConfig config = new DbConfig(new DbConfig.Target("jdbc:sqlite::memory:", "sqlite",
                List.of(new QuerySpec("SELECT 1", 1, "read", null))), 20);

        WriteAmplification amplification;
        try (DbRunner runner = DbRunner.create(config, 2)) {
            runner.run(RunContext.startingNow(SECOND, 250 * MS, 0, 2));
            amplification = runner.writeAmplification();
        }

        assertThat(amplification.available()).isFalse();
        assertThat(amplification.unavailableReason()).contains("sqlite");
    }
}
