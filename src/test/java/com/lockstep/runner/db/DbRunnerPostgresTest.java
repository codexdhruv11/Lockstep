package com.lockstep.runner.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.lockstep.config.DbConfig;
import com.lockstep.config.QuerySpec;
import com.lockstep.core.PacedLoop;
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
final class DbRunnerPostgresTest {
    private static final long MS = 1_000_000L;
    private static final long SECOND = 1_000_000_000L;

    @Test
    void runsAConcurrentReadWriteMixAgainstPostgres() throws Exception {
        assumeTrue(DockerClientFactory.instance().isDockerAvailable(),
                "Docker is not available — Postgres integration test skipped");

        try (PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")) {
            postgres.start();

            try (Connection connection = DriverManager.getConnection(
                        postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
                    Statement statement = connection.createStatement()) {
                statement.execute("CREATE TABLE orders (id SERIAL PRIMARY KEY, customer TEXT, amount INT)");
                statement.execute("INSERT INTO orders (customer, amount) VALUES ('seed', 1)");
            }

            String conn = "postgres://%s:%s@%s:%d/%s".formatted(
                    postgres.getUsername(), postgres.getPassword(),
                    postgres.getHost(), postgres.getFirstMappedPort(), postgres.getDatabaseName());

            DbConfig config = new DbConfig(new DbConfig.Target(conn, "postgres", List.of(
                    new QuerySpec("SELECT count(*) FROM orders", 60, "read", null),
                    new QuerySpec("INSERT INTO orders (customer, amount) VALUES ('load', 1)", 40, "write", null))),
                    100);

            try (DbRunner runner = DbRunner.create(config, 8)) {
                PacedLoop.LoopResult result = runner.run(
                        RunContext.startingNow(2 * SECOND, 200 * MS, 0, 8));

                assertThat(result.executedCount()).isGreaterThan(50);
                assertThat(result.series().errorCount()).isZero();
                assertThat(result.series().nonEmptyBuckets()).hasSizeGreaterThan(5);
                assertThat(result.series().summarize("db", 2 * SECOND).p99Nanos()).isGreaterThan(0);

                assertThat(runner.maxConnectionWaitNanos()).isLessThan(SECOND);
            }
        }
    }
}
