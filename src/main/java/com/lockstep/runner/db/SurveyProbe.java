package com.lockstep.runner.db;

import com.lockstep.analysis.PrometheusScrape;
import com.lockstep.analysis.Survey;
import com.lockstep.analysis.TargetMetrics;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads the target's accumulated counters. Read-only, and generates no load.
 *
 * <p>Nothing here resets a counter. {@code pg_stat_statements_reset()} and
 * {@code pg_stat_reset()} would give cleaner numbers for one caller by destroying them for every
 * other tool watching the same database, which is not a trade this is entitled to make.
 */
public final class SurveyProbe implements AutoCloseable {

    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(10);

    /**
     * Statements that are maintenance or schema changes rather than application traffic.
     *
     * <p>Without this filter the ranking is nonsense on any database whose counters span a
     * migration: measured on a freshly built fixture, {@code VACUUM ANALYZE} took 54.5% of all
     * recorded time and {@code CREATE DATABASE} another 8.3%, burying the application query that
     * the survey exists to find. These run once and cost a great deal, so ranking them beside
     * queries served thousands of times answers the wrong question.
     *
     * <p>They are excluded rather than merely deprioritised: nobody surveying a service for slow
     * queries wants to be told its schema migration was expensive.
     */
    private static final String UTILITY_FILTER = """
            AND query !~* '^\\s*(VACUUM|ANALYZE|CREATE|ALTER|DROP|TRUNCATE|REINDEX|CLUSTER\
            |GRANT|REVOKE|COMMENT|SET|RESET|BEGIN|COMMIT|ROLLBACK|CHECKPOINT|COPY|DISCARD\
            |LISTEN|NOTIFY|PREPARE|DEALLOCATE|EXPLAIN|SHOW|REFRESH)\\M'
            """;

    /** PostgreSQL 13 renamed total_time to total_exec_time, so the column is detected. */
    private static final String STATEMENTS_13 = """
            SELECT query, calls, total_exec_time AS total_ms, mean_exec_time AS mean_ms
            FROM pg_stat_statements
            WHERE query NOT LIKE '%pg_stat%' AND query NOT LIKE '%pg_class%'
            """ + UTILITY_FILTER + """
            ORDER BY total_exec_time DESC
            LIMIT 40
            """;

    private static final String STATEMENTS_LEGACY = """
            SELECT query, calls, total_time AS total_ms, mean_time AS mean_ms
            FROM pg_stat_statements
            WHERE query NOT LIKE '%pg_stat%' AND query NOT LIKE '%pg_class%'
            """ + UTILITY_FILTER + """
            ORDER BY total_time DESC
            LIMIT 40
            """;

    private static final String RELATIONS = """
            SELECT s.relname, s.seq_scan, s.seq_tup_read, COALESCE(s.idx_scan, 0) AS idx_scan,
                   pg_table_size(s.relid) AS table_bytes,
                   GREATEST(c.reltuples, 0)::bigint AS est_rows
            FROM pg_stat_user_tables s
            JOIN pg_class c ON c.oid = s.relid
            """;

    /**
     * Unique and primary-key flags are fetched because they decide whether an unused index may be
     * recommended for dropping. One that enforces a constraint may not, however unused.
     */
    private static final String INDEXES = """
            SELECT i.indexrelname, i.relname AS table_name, COALESCE(i.idx_scan, 0) AS scans,
                   pg_relation_size(i.indexrelid) AS index_bytes,
                   ix.indisunique, ix.indisprimary
            FROM pg_stat_user_indexes i
            JOIN pg_index ix ON ix.indexrelid = i.indexrelid
            """;

    private final Connection connection;
    private final String database;

    private SurveyProbe(Connection connection, String database) {
        this.connection = connection;
        this.database = database;
    }

    public static SurveyProbe open(String conn, String driver) {
        if (!"postgresql".equals(ConnectionStrings.normalizeDriver(driver))) {
            throw new IllegalArgumentException(
                    "survey reads PostgreSQL's statistics views; got driver \"" + driver + "\"");
        }
        ConnectionStrings.JdbcTarget target = ConnectionStrings.toJdbc(conn, driver);
        try {
            Connection connection =
                    DriverManager.getConnection(target.url(), target.username(), target.password());
            connection.setReadOnly(true);
            return new SurveyProbe(connection, target.url());
        } catch (Exception e) {
            throw new IllegalArgumentException(
                    "could not connect to " + target.url() + ": " + describe(e), e);
        }
    }

    public Survey read(String metricsUrl) {
        try {
            String since = null;
            long ageDays = 0;
            try (Statement statement = connection.createStatement();
                    ResultSet rows = statement.executeQuery(
                            "SELECT stats_reset FROM pg_stat_database WHERE datname = current_database()")) {
                if (rows.next()) {
                    var timestamp = rows.getTimestamp(1);
                    if (timestamp != null) {
                        Instant reset = timestamp.toInstant();
                        since = reset.toString();
                        ageDays = ChronoUnit.DAYS.between(reset, Instant.now());
                    }
                }
            }

            List<Survey.Statement> statements = new ArrayList<>();
            String statementsProblem = null;
            boolean statementsAvailable = false;
            try {
                statements.addAll(readStatements());
                statementsAvailable = true;
            } catch (Exception e) {
                statementsProblem = "pg_stat_statements is not available: " + describe(e)
                        + ". It needs shared_preload_libraries and CREATE EXTENSION, so enabling "
                        + "it means restarting the server. Without it the query ranking — the most "
                        + "useful part of this — cannot be produced.";
            }

            return new Survey(true, null, database, since, ageDays,
                    statementsAvailable, statementsProblem, statements,
                    readRelations(), readIndexes(), readEndpoints(metricsUrl));
        } catch (Exception e) {
            return Survey.unavailable(database,
                    "could not read the statistics views: " + describe(e));
        }
    }

    private List<Survey.Statement> readStatements() throws Exception {
        List<Survey.Statement> statements = new ArrayList<>();
        String sql = hasColumn("pg_stat_statements", "total_exec_time")
                ? STATEMENTS_13 : STATEMENTS_LEGACY;
        try (Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery(sql)) {
            while (rows.next()) {
                statements.add(new Survey.Statement(rows.getString("query"),
                        rows.getLong("calls"), rows.getDouble("total_ms"),
                        rows.getDouble("mean_ms")));
            }
        }
        return statements;
    }

    private boolean hasColumn(String table, String column) throws Exception {
        try (var statement = connection.prepareStatement(
                "SELECT 1 FROM information_schema.columns WHERE table_name = ? AND column_name = ?")) {
            statement.setString(1, table);
            statement.setString(2, column);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next();
            }
        }
    }

    private List<Survey.Relation> readRelations() throws Exception {
        List<Survey.Relation> relations = new ArrayList<>();
        try (Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery(RELATIONS)) {
            while (rows.next()) {
                relations.add(new Survey.Relation(rows.getString("relname"),
                        rows.getLong("seq_scan"), rows.getLong("seq_tup_read"),
                        rows.getLong("idx_scan"), rows.getLong("table_bytes"),
                        rows.getLong("est_rows")));
            }
        }
        return relations;
    }

    private List<Survey.Index> readIndexes() throws Exception {
        List<Survey.Index> indexes = new ArrayList<>();
        try (Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery(INDEXES)) {
            while (rows.next()) {
                indexes.add(new Survey.Index(rows.getString("indexrelname"),
                        rows.getString("table_name"), rows.getLong("scans"),
                        rows.getLong("index_bytes"), rows.getBoolean("indisunique"),
                        rows.getBoolean("indisprimary")));
            }
        }
        return indexes;
    }

    /** Endpoints from the target's metrics endpoint, if one was given. */
    private List<Survey.Endpoint> readEndpoints(String metricsUrl) {
        if (metricsUrl == null) {
            return List.of();
        }
        try (HttpClient client = HttpClient.newBuilder().connectTimeout(HTTP_TIMEOUT).build()) {
            HttpResponse<String> response = client.send(
                    HttpRequest.newBuilder(URI.create(metricsUrl)).timeout(HTTP_TIMEOUT).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                return List.of();
            }
            PrometheusScrape scrape = PrometheusScrape.parse(response.body());
            String countName = scrape.firstPresent(
                    TargetMetrics.REQUEST_COUNT_NAMES.toArray(String[]::new));
            String sumName = scrape.firstPresent(
                    TargetMetrics.REQUEST_SECONDS_NAMES.toArray(String[]::new));
            if (countName == null) {
                return List.of();
            }

            // Pair count and sum by their uri/method labels, so each endpoint gets both.
            List<Survey.Endpoint> endpoints = new ArrayList<>();
            for (PrometheusScrape.Sample count : scrape.named(countName)) {
                String uri = count.label("uri") != null ? count.label("uri")
                        : count.label("http_route") != null ? count.label("http_route")
                        : count.label("path");
                if (uri == null) {
                    continue;
                }
                String method = count.label("method") != null ? count.label("method")
                        : count.label("http_request_method");
                double seconds = 0;
                if (sumName != null) {
                    for (PrometheusScrape.Sample sum : scrape.named(sumName)) {
                        if (uri.equals(sum.label("uri")) || uri.equals(sum.label("http_route"))
                                || uri.equals(sum.label("path"))) {
                            if (method == null || method.equals(sum.label("method"))
                                    || method.equals(sum.label("http_request_method"))) {
                                seconds += sum.value();
                            }
                        }
                    }
                }
                endpoints.add(new Survey.Endpoint(uri, method, (long) count.value(), seconds));
            }
            return endpoints;
        } catch (Exception e) {
            return List.of();
        }
    }

    private static String describe(Exception e) {
        String message = e.getMessage();
        return e.getClass().getSimpleName() + (message == null ? "" : ": " + message);
    }

    @Override
    public void close() {
        try {
            connection.close();
        } catch (Exception ignored) {
            // closing a read-only connection cannot fail a completed survey
        }
    }
}
