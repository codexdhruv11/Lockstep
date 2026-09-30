package com.lockstep.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import picocli.CommandLine;
import org.junit.jupiter.api.Tag;

@Tag("container")
final class GrowthCurvePostgresTest {

    private record Run(int exitCode, String out, String err) {}

    private static Run invoke(String... args) {
        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();
        CommandLine cli = new CommandLine(new Cli());
        cli.setOut(new PrintWriter(out));
        cli.setErr(new PrintWriter(err));
        int code = cli.execute(args);
        return new Run(code, out.toString(), err.toString());
    }

    private static Path configFor(PostgreSQLContainer<?> postgres, Path directory, String query)
            throws Exception {
        String conn = "postgres://%s:%s@%s:%d/%s".formatted(
                postgres.getUsername(), postgres.getPassword(),
                postgres.getHost(), postgres.getFirstMappedPort(), postgres.getDatabaseName());
        Path config = directory.resolve("growth.yaml");
        Files.writeString(config, """
                duration: 2s
                bucket_width: 500ms
                concurrency: 4

                db:
                  rate: 30
                  target:
                    conn: %s
                    driver: postgres
                    queries:
                      - query: %s
                        weight: 1
                        type: read
                """.formatted(conn, query));
        return config;
    }

    private static void createTable(PostgreSQLContainer<?> postgres, long initialRows)
            throws Exception {
        try (Connection connection = DriverManager.getConnection(
                    postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
                Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE signals (id SERIAL PRIMARY KEY, payload TEXT)");
            if (initialRows > 0) {
                statement.execute(
                        "INSERT INTO signals (payload) SELECT repeat('x', 200) "
                        + "FROM generate_series(1, " + initialRows + ")");
            }
            statement.execute("ANALYZE signals");
        }
    }

    private static long rowCount(PostgreSQLContainer<?> postgres) throws Exception {
        try (Connection connection = DriverManager.getConnection(
                    postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
                Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery("SELECT count(*) FROM signals")) {
            return rows.next() ? rows.getLong(1) : -1;
        }
    }

    private static final String SEED =
            "INSERT INTO signals (payload) SELECT repeat('x', 200) FROM generate_series(1, {{n}})";

    // --- the safety gate ------------------------------------------------------------------------

    @Test
    void withoutAllowWritesNothingIsWritten(@TempDir Path directory) throws Exception {
        assumeTrue(DockerClientFactory.instance().isDockerAvailable(),
                "Docker is not available — growth-curve test skipped");

        try (PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")) {
            postgres.start();
            createTable(postgres, 1_000);
            Path config = configFor(postgres, directory, "SELECT count(*) FROM signals");

            Run run = invoke("growth-curve", "-c", config.toString(),
                    "--table", "signals", "--seed", SEED, "--steps", "2000,4000,8000");

            assertThat(run.exitCode()).isEqualTo(GrowthCurveCommand.EXIT_CONFIG_ERROR);
            assertThat(run.err()).contains("--allow-writes");
            assertThat(rowCount(postgres))
                    .withFailMessage("a command refused for want of --allow-writes must not have "
                            + "written anything first")
                    .isEqualTo(1_000);
        }
    }

    @Test
    void aSeedStatementWithoutThePlaceholderIsRejected(@TempDir Path directory) throws Exception {
        assumeTrue(DockerClientFactory.instance().isDockerAvailable(), "Docker unavailable");

        try (PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")) {
            postgres.start();
            createTable(postgres, 1_000);
            Path config = configFor(postgres, directory, "SELECT count(*) FROM signals");

            Run run = invoke("growth-curve", "-c", config.toString(), "--allow-writes",
                    "--table", "signals",
                    "--seed", "INSERT INTO signals (payload) VALUES ('one row only')",
                    "--steps", "2000,4000,8000");

            assertThat(run.exitCode()).isEqualTo(GrowthCurveCommand.EXIT_CONFIG_ERROR);
            assertThat(run.err()).contains("{{n}}");
            assertThat(rowCount(postgres)).isEqualTo(1_000);
        }
    }

    @Test
    void aTableNameThatIsNotAPlainIdentifierIsRejected(@TempDir Path directory) throws Exception {
        assumeTrue(DockerClientFactory.instance().isDockerAvailable(), "Docker unavailable");

        try (PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")) {
            postgres.start();
            createTable(postgres, 1_000);
            Path config = configFor(postgres, directory, "SELECT count(*) FROM signals");

            Run run = invoke("growth-curve", "-c", config.toString(), "--allow-writes",
                    "--table", "signals; DROP TABLE signals", "--seed", SEED,
                    "--steps", "2000,4000,8000");

            assertThat(run.exitCode()).isEqualTo(GrowthCurveCommand.EXIT_CONFIG_ERROR);
            assertThat(rowCount(postgres))
                    .withFailMessage("the table name is interpolated into ANALYZE and a count, so "
                            + "a rejected name must leave the database untouched")
                    .isEqualTo(1_000);
        }
    }

    @Test
    void twoStepsIsRefusedAsNotACurve(@TempDir Path directory) throws Exception {
        assumeTrue(DockerClientFactory.instance().isDockerAvailable(), "Docker unavailable");

        try (PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")) {
            postgres.start();
            createTable(postgres, 1_000);
            Path config = configFor(postgres, directory, "SELECT count(*) FROM signals");

            Run run = invoke("growth-curve", "-c", config.toString(), "--allow-writes",
                    "--table", "signals", "--seed", SEED, "--steps", "2000,4000");

            assertThat(run.exitCode()).isEqualTo(GrowthCurveCommand.EXIT_CONFIG_ERROR);
            assertThat(run.err()).contains("at least 3 steps");
        }
    }

    // --- the measurement ------------------------------------------------------------------------

    @Test
    void aScanGrowsWithTheTableAndTheCurveSaysSo(@TempDir Path directory) throws Exception {
        assumeTrue(DockerClientFactory.instance().isDockerAvailable(),
                "Docker is not available — growth-curve test skipped");

        try (PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")) {
            postgres.start();
            createTable(postgres, 0);
            // Aggregates an unindexed column, so every request must visit every heap block and
            // the cost is bound to the table's size rather than to an index.
            Path config = configFor(postgres, directory,
                    "SELECT sum(length(payload)) FROM signals");

            Run run = invoke("growth-curve", "-c", config.toString(), "--allow-writes",
                    "--table", "signals", "--seed", SEED,
                    "--steps", "25000,50000,100000", "--budget", "2s");

            assertThat(run.exitCode())
                    .withFailMessage("command failed: %s", run.err())
                    .isEqualTo(GrowthCurveCommand.EXIT_SUCCESS);

            assertThat(rowCount(postgres))
                    .withFailMessage("the last step should have grown the table to its target")
                    .isEqualTo(100_000);

            assertThat(run.out()).contains("growth curve");
            assertThat(run.out()).contains("25,000").contains("50,000").contains("100,000");
            assertThat(run.out())
                    .withFailMessage("bytes read per request must grow with a table scan, which "
                            + "is what separates a scan from an index lookup:%n%s", run.out())
                    .contains("the work is proportional to the data");

            // Asserting the WORK, not the time. Bytes per request is derived from block
            // counters and does not depend on timing, so a scan must show it growing with the
            // table however loaded the machine is. The time exponent does depend on load: under
            // a busy test suite this same fixture produced rows^-0.29 at R-squared 0.977, a
            // confident fit to contamination, while bytes per request still scaled exactly 4x.
            assertThat(run.out())
                    .withFailMessage("""
                            bytes read per request must grow with a table scan, which is what \
                            separates a scan from an index lookup:%n%s""", run.out())
                    .contains("the work is proportional to the data");
            assertThat(run.out())
                    .withFailMessage("a scan's cost is bound to the table, so the report must not "
                            + "claim an index is bounding the work:%n%s", run.out())
                    .doesNotContain("an index is bounding the work");
        }
    }

    @Test
    void anIndexedLookupIsReportedAsFlat(@TempDir Path directory) throws Exception {
        assumeTrue(DockerClientFactory.instance().isDockerAvailable(), "Docker unavailable");

        try (PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")) {
            postgres.start();
            createTable(postgres, 0);
            Path config = configFor(postgres, directory,
                    "SELECT payload FROM signals WHERE id = 100");

            Run run = invoke("growth-curve", "-c", config.toString(), "--allow-writes",
                    "--table", "signals", "--seed", SEED,
                    "--steps", "25000,50000,100000", "--budget", "500ms");

            assertThat(run.exitCode())
                    .withFailMessage("command failed: %s", run.err())
                    .isEqualTo(GrowthCurveCommand.EXIT_SUCCESS);

            // Deliberately NOT asserting the fitted exponent. A primary-key lookup takes a few
            // milliseconds, so on a loaded machine the differences between steps are noise and
            // the slope through them is whatever the noise happened to be — an exponent of 0.48
            // at R-squared 0.509 was observed. Asserting it would be asserting the machine's
            // load. What must hold is physical, and does regardless of scheduling:
            assertThat(run.out())
                    .withFailMessage("bytes read per request must NOT grow for an index lookup — "
                            + "that is what distinguishes it from a scan:%n%s", run.out())
                    .doesNotContain("the work is proportional to the data");
            assertThat(run.out())
                    .withFailMessage("a budget crossing must not be invented from a poor fit or a "
                            + "flat curve:%n%s", run.out())
                    .doesNotContain("reaches it at about");
        }
    }
}
