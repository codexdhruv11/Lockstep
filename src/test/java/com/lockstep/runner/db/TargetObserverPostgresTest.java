package com.lockstep.runner.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.lockstep.analysis.TargetQueries;
import com.lockstep.config.HttpConfig;
import com.lockstep.core.RunContext;
import com.lockstep.runner.http.HttpRunner;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Drives a deliberately N+1 HTTP endpoint and asserts the observer names it.
 *
 * <p>The test server opens a connection per request, so its backends exit and flush their
 * statistics unconditionally. That makes the counts exact here and is the reason this test is
 * about the arithmetic rather than about the lower-bound behaviour a pooled target would show.
 */
final class TargetObserverPostgresTest {
    private static final long MS = 1_000_000L;
    private static final long SECOND = 1_000_000_000L;

    /** Orders per page, and therefore the number of extra queries the N+1 endpoint makes. */
    private static final int PAGE_SIZE = 10;

    private record Target(HttpServer server, int port) implements AutoCloseable {
        @Override
        public void close() {
            server.stop(0);
        }
    }

    private static Target startTarget(PostgreSQLContainer<?> postgres) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());

        // One statement, one scan of each table.
        server.createContext("/joined", exchange -> serve(exchange, postgres, connection -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT o.id, c.name FROM orders o
                    JOIN customers c ON c.id = o.customer_id
                    ORDER BY o.id LIMIT ?
                    """)) {
                statement.setInt(1, PAGE_SIZE);
                drain(statement);
            }
        }));

        // The same page, fetched the wrong way: one query for the orders and then one per order.
        server.createContext("/nplusone", exchange -> serve(exchange, postgres, connection -> {
            List<Integer> customerIds = new java.util.ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT customer_id FROM orders ORDER BY id LIMIT ?")) {
                statement.setInt(1, PAGE_SIZE);
                try (ResultSet rows = statement.executeQuery()) {
                    while (rows.next()) {
                        customerIds.add(rows.getInt(1));
                    }
                }
            }
            for (int customerId : customerIds) {
                try (PreparedStatement statement = connection.prepareStatement(
                        "SELECT name FROM customers WHERE id = ?")) {
                    statement.setInt(1, customerId);
                    drain(statement);
                }
            }
        }));

        server.start();
        return new Target(server, server.getAddress().getPort());
    }

    private interface Work {
        void run(Connection connection) throws Exception;
    }

    private static void serve(HttpExchange exchange, PostgreSQLContainer<?> postgres, Work work)
            throws IOException {
        int status = 200;
        try (Connection connection = DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())) {
            work.run(connection);
        } catch (Exception e) {
            status = 500;
        }
        byte[] body = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("content-type", "application/json");
        exchange.sendResponseHeaders(status, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    private static void drain(PreparedStatement statement) throws Exception {
        try (ResultSet rows = statement.executeQuery()) {
            while (rows.next()) {
                rows.getObject(1);
            }
        }
    }

    /**
     * pg_stat_statements has to be in shared_preload_libraries, which means a server flag — it
     * cannot be turned on by CREATE EXTENSION alone.
     */
    private static PostgreSQLContainer<?> postgresWithStatementStats() {
        return new PostgreSQLContainer<>("postgres:16-alpine")
                .withCommand("postgres", "-c", "shared_preload_libraries=pg_stat_statements");
    }

    private static void seed(PostgreSQLContainer<?> postgres) throws Exception {
        try (Connection connection = DriverManager.getConnection(
                    postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
                Statement statement = connection.createStatement()) {
            statement.execute("CREATE EXTENSION IF NOT EXISTS pg_stat_statements");
            statement.execute("CREATE TABLE customers (id INT PRIMARY KEY, name TEXT)");
            statement.execute("CREATE TABLE orders (id SERIAL PRIMARY KEY, customer_id INT)");
            statement.execute(
                    "INSERT INTO customers SELECT g, 'name-' || g FROM generate_series(1, 200) g");
            statement.execute("INSERT INTO orders (customer_id) "
                    + "SELECT (g % 200) + 1 FROM generate_series(1, 500) g");
            statement.execute("ANALYZE customers");
            statement.execute("ANALYZE orders");
        }
    }

    private static String conn(PostgreSQLContainer<?> postgres) {
        return "postgres://%s:%s@%s:%d/%s".formatted(
                postgres.getUsername(), postgres.getPassword(),
                postgres.getHost(), postgres.getFirstMappedPort(), postgres.getDatabaseName());
    }

    private static TargetQueries drive(PostgreSQLContainer<?> postgres, int port, String path) {
        HttpConfig config = new HttpConfig(
                new HttpConfig.Target("GET", "http://localhost:" + port + path, null,
                        Map.of(), 1), 20);

        try (TargetObserver observer = TargetObserver.open(conn(postgres), "postgres")) {
            observer.before();
            long executed;
            try (HttpRunner runner = HttpRunner.create(config, 4)) {
                executed = runner.run(RunContext.startingNow(2 * SECOND, 500 * MS, 0, 4))
                        .executedCount();
            }
            return observer.after(executed);
        }
    }

    @Test
    void countsTheStatementsAnNPlusOneEndpointSends() throws Exception {
        assumeTrue(DockerClientFactory.instance().isDockerAvailable(),
                "Docker is not available — target observation test skipped");

        try (PostgreSQLContainer<?> postgres = postgresWithStatementStats()) {
            postgres.start();
            seed(postgres);

            try (Target target = startTarget(postgres)) {
                TargetQueries queries = drive(postgres, target.port(), "/nplusone");

                assertThat(queries.available())
                        .withFailMessage("observation failed: %s", queries.unavailableReason())
                        .isTrue();
                assertThat(queries.requests()).isGreaterThan(10);
                assertThat(queries.canJudgeStatementCount())
                        .withFailMessage("pg_stat_statements was preloaded and created, so "
                                + "statement counts must be available")
                        .isTrue();

                // One query for the page of orders, then one per order.
                double perRequest = queries.statementsPerRequest();
                assertThat(perRequest)
                        .withFailMessage("""
                                the endpoint sends 1 + %d statements per request; got %.2f over \
                                %d requests""", PAGE_SIZE, perRequest, queries.requests())
                        .isBetween((PAGE_SIZE + 1) * 0.7, (PAGE_SIZE + 1) * 1.4);
                assertThat(queries.manyStatementsPerRequest()).isTrue();
            }
        }
    }

    @Test
    void doesNotAccuseTheSameEndpointWrittenAsAJoin() throws Exception {
        assumeTrue(DockerClientFactory.instance().isDockerAvailable(), "Docker unavailable");

        try (PostgreSQLContainer<?> postgres = postgresWithStatementStats()) {
            postgres.start();
            seed(postgres);

            try (Target target = startTarget(postgres)) {
                TargetQueries queries = drive(postgres, target.port(), "/joined");

                assertThat(queries.hasObservations()).isTrue();
                assertThat(queries.statementsPerRequest())
                        .withFailMessage("the joined endpoint sends one statement a request; "
                                + "got %.2f", queries.statementsPerRequest())
                        .isLessThan(2.5);
                assertThat(queries.manyStatementsPerRequest())
                        .withFailMessage("one statement a request must never be flagged, however "
                                + "many plan-node executions it causes")
                        .isFalse();
            }
        }
    }

    @Test
    void statementCountsSeparateTheTwoShapesAndScanCountsDoNot() throws Exception {
        assumeTrue(DockerClientFactory.instance().isDockerAvailable(), "Docker unavailable");

        try (PostgreSQLContainer<?> postgres = postgresWithStatementStats()) {
            postgres.start();
            seed(postgres);

            try (Target target = startTarget(postgres)) {
                TargetQueries joined = drive(postgres, target.port(), "/joined");
                TargetQueries nplusone = drive(postgres, target.port(), "/nplusone");

                // The signal that works.
                assertThat(nplusone.statementsPerRequest())
                        .withFailMessage("""
                                the two endpoints return the same page and would look alike on \
                                latency alone; statements per request is what separates them:
                                  joined:   %.2f statements/request
                                  nplusone: %.2f statements/request""",
                                joined.statementsPerRequest(), nplusone.statementsPerRequest())
                        .isGreaterThan(joined.statementsPerRequest() * 3);

                // The signal that does not, locked in so it is not reintroduced. Postgres plans
                // the join as a nested loop and scans the inner table once per outer row, so its
                // scan count is the same order as the hand-written loop's — and can be higher.
                double joinedScans = joined.totalScansPerRequest();
                double loopScans = nplusone.totalScansPerRequest();
                assertThat(joinedScans)
                        .withFailMessage("""
                                if a join's scan count were much lower than a loop's, scan counts \
                                would be a usable N+1 signal and this feature could be simpler:
                                  joined:   %.2f scans/request
                                  nplusone: %.2f scans/request""", joinedScans, loopScans)
                        .isGreaterThan(loopScans * 0.5);
            }
        }
    }

    @Test
    void aNonPostgresTargetIsRefusedRatherThanSilentlyIgnored() {
        assertThat(org.assertj.core.api.Assertions.catchThrowable(
                () -> TargetObserver.open("jdbc:sqlite::memory:", "sqlite")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("statistics views");
    }
}
