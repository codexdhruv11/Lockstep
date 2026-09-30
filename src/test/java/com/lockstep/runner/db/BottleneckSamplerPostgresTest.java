package com.lockstep.runner.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.lockstep.analysis.Bottleneck;
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

final class BottleneckSamplerPostgresTest {
    private static final long MS = 1_000_000L;
    private static final long SECOND = 1_000_000_000L;

    private static String conn(PostgreSQLContainer<?> postgres) {
        return "postgres://%s:%s@%s:%d/%s".formatted(
                postgres.getUsername(), postgres.getPassword(),
                postgres.getHost(), postgres.getFirstMappedPort(), postgres.getDatabaseName());
    }

    private static void seed(PostgreSQLContainer<?> postgres, int rows) throws Exception {
        try (Connection connection = DriverManager.getConnection(
                    postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
                Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE signals (id SERIAL PRIMARY KEY, payload TEXT)");
            statement.execute("INSERT INTO signals (payload) SELECT repeat('x', 200) "
                    + "FROM generate_series(1, " + rows + ")");
            statement.execute("ANALYZE signals");
        }
    }

    /**
     * Runs a load with a deliberately small pool, so the pool — not the database — is the limit.
     * The sampler should see busy backends pinned at the pool size.
     */
    private static Bottleneck sampleWhileLoading(PostgreSQLContainer<?> postgres, int poolSize,
            String query, long durationNanos) throws Exception {
        DbConfig config = new DbConfig(new DbConfig.Target(conn(postgres), "postgres",
                List.of(new QuerySpec(query, 1, "read", null)), poolSize), 400);

        try (BottleneckSampler sampler = BottleneckSampler.open(conn(postgres), "postgres")) {
            sampler.start();
            try (DbRunner runner = DbRunner.create(config, poolSize)) {
                runner.run(RunContext.startingNow(durationNanos, 500 * MS, 0, poolSize));
            }
            return sampler.stop();
        }
    }

    @Test
    void seesBusyBackendsPinnedAtTheClientsPoolSize() throws Exception {
        assumeTrue(DockerClientFactory.instance().isDockerAvailable(),
                "Docker is not available — bottleneck sampling test skipped");

        try (PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")) {
            postgres.start();
            seed(postgres, 60_000);

            // A pool of 4 against work that takes far longer than the request interval: every
            // connection should be busy essentially all the time.
            Bottleneck bottleneck = sampleWhileLoading(postgres, 4,
                    "SELECT sum(length(payload)) FROM signals", 4 * SECOND);

            assertThat(bottleneck.available())
                    .withFailMessage("sampling failed: %s", bottleneck.unavailableReason())
                    .isTrue();
            assertThat(bottleneck.samples())
                    .withFailMessage("a 4 second run sampled every %dms should give several "
                            + "samples; got %d",
                            Bottleneck.SAMPLE_INTERVAL_MILLIS, bottleneck.samples())
                    .isGreaterThan(5);

            assertThat(bottleneck.maxActiveBackends())
                    .withFailMessage("the pool holds 4 connections, so no more than 4 of its "
                            + "backends can be active at once; saw %d",
                            bottleneck.maxActiveBackends())
                    .isBetween(1, 4);
            assertThat(bottleneck.plateauBackends())
                    .withFailMessage("""
                            with every connection saturated the busy count should sit at the pool \
                            size: plateau %d held for %.0f%% of %d samples, mean %.2f""",
                            bottleneck.plateauBackends(), bottleneck.plateauShare() * 100,
                            bottleneck.samples(), bottleneck.meanActiveBackends())
                    .isBetween(2, 4);
            assertThat(bottleneck.serverMaxConnections())
                    .withFailMessage("max_connections should be read from the server")
                    .isGreaterThan(4);
            assertThat(bottleneck.verdict()).isNotEqualTo(Bottleneck.Verdict.UNKNOWN);
        }
    }

    @Test
    void excludesItsOwnBackendFromEveryCount() throws Exception {
        assumeTrue(DockerClientFactory.instance().isDockerAvailable(), "Docker unavailable");

        try (PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")) {
            postgres.start();
            seed(postgres, 100);

            // Nothing else is connected. If the sampler counted itself it would report a busy
            // backend in every sample and a permanent Client wait.
            Bottleneck bottleneck;
            try (BottleneckSampler sampler = BottleneckSampler.open(conn(postgres), "postgres")) {
                sampler.start();
                Thread.sleep(1_500);
                bottleneck = sampler.stop();
            }

            assertThat(bottleneck.available()).isTrue();
            assertThat(bottleneck.samples()).isGreaterThan(2);
            assertThat(bottleneck.maxActiveBackends())
                    .withFailMessage("""
                            an otherwise idle database must show no busy backends; seeing %d means \
                            the sampler is counting itself""", bottleneck.maxActiveBackends())
                    .isZero();
            assertThat(bottleneck.verdict())
                    .isEqualTo(Bottleneck.Verdict.NOT_SATURATED);
        }
    }

    @Test
    void aRunShorterThanTheSamplingIntervalSaysSoRatherThanInventingAVerdict() throws Exception {
        assumeTrue(DockerClientFactory.instance().isDockerAvailable(), "Docker unavailable");

        try (PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")) {
            postgres.start();
            seed(postgres, 100);

            BottleneckSampler sampler = BottleneckSampler.open(conn(postgres), "postgres");
            // Never started, so nothing was sampled.
            Bottleneck bottleneck = sampler.stop();
            sampler.close();

            assertThat(bottleneck.available()).isFalse();
            assertThat(bottleneck.unavailableReason()).contains("no samples");
            assertThat(bottleneck.verdict()).isEqualTo(Bottleneck.Verdict.UNKNOWN);
        }
    }

    @Test
    void aNonPostgresTargetIsRefused() {
        assertThat(org.assertj.core.api.Assertions.catchThrowable(
                () -> BottleneckSampler.open("jdbc:sqlite::memory:", "sqlite")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("pg_stat_activity");
    }
}
