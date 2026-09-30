package com.lockstep.runner.db;

import com.lockstep.analysis.TargetQueries;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Watches the target's own database while Lockstep drives its endpoints, so a run can report how
 * many queries the target ran per request.
 *
 * <p>Read-only. It issues nothing but SELECTs against the statistics views and never touches
 * application data. It is deliberately a separate connection from anything the load uses: the
 * point is to observe a target, not to be part of it.
 */
public final class TargetObserver implements AutoCloseable {

    private static final long SETTLE_POLL_MILLIS = 500;
    private static final int SETTLE_STABLE_READS = 3;
    private static final long SETTLE_BUDGET_MILLIS = 10_000;

    /**
     * {@code seq_tup_read} and {@code idx_tup_fetch} are the per-table row counters.
     * {@code n_tup_returned} and {@code n_tup_fetched} look right and are not — they belong to
     * pg_stat_database, and asking pg_stat_user_tables for them is an error, not a null.
     */
    private static final String SCANS = """
            SELECT relname, seq_scan, idx_scan, seq_tup_read, idx_tup_fetch
            FROM pg_stat_user_tables
            """;

    private final Connection connection;
    private final String describedTarget;

    private Map<String, long[]> before;
    private long statementCallsBefore = -1;
    private String beforeFailure;

    private TargetObserver(Connection connection, String describedTarget) {
        this.connection = connection;
        this.describedTarget = describedTarget;
    }

    /** Opens a read-only observation connection, or returns null with the reason recorded. */
    public static TargetObserver open(String conn, String driver) {
        ConnectionStrings.JdbcTarget target = ConnectionStrings.toJdbc(conn, driver);
        if (!"postgresql".equals(ConnectionStrings.normalizeDriver(driver))) {
            throw new IllegalArgumentException(
                    "observing a target needs PostgreSQL's statistics views; got driver \""
                    + driver + "\"");
        }
        try {
            Connection connection =
                    DriverManager.getConnection(target.url(), target.username(), target.password());
            connection.setReadOnly(true);
            return new TargetObserver(connection, target.url());
        } catch (Exception e) {
            throw new IllegalArgumentException(
                    "could not connect to the target's database to observe it: " + describe(e), e);
        }
    }

    public String describedTarget() {
        return describedTarget;
    }

    public void before() {
        try {
            before = readScans();
            statementCallsBefore = readStatementCalls();
            beforeFailure = null;
        } catch (Exception e) {
            before = null;
            // Keep the cause. Reporting only that it "failed" makes the feature undebuggable,
            // which is the opposite of the point.
            beforeFailure = describe(e);
        }
    }

    public TargetQueries after(long requests) {
        if (before == null) {
            return TargetQueries.unavailable(
                    "could not read the target's statistics views before the run: "
                    + (beforeFailure == null ? "no snapshot was taken" : beforeFailure));
        }
        try {
            Map<String, long[]> after = readSettledScans();
            long callsAfter = readStatementCalls();

            List<TargetQueries.Relation> relations = new ArrayList<>();
            long rowsRead = 0;
            for (Map.Entry<String, long[]> entry : after.entrySet()) {
                long[] start = before.get(entry.getKey());
                long[] end = entry.getValue();
                long seq = end[0] - (start == null ? 0 : start[0]);
                long idx = end[1] - (start == null ? 0 : start[1]);
                long idxFetched = end[2] - (start == null ? 0 : start[2]);
                long seqRead = end[3] - (start == null ? 0 : start[3]);
                long rows = Math.max(0, idxFetched) + Math.max(0, seqRead);
                rowsRead += rows;
                if (seq + idx <= 0) {
                    continue;
                }
                relations.add(new TargetQueries.Relation(entry.getKey(), seq, idx, rows));
            }

            boolean callsAvailable = statementCallsBefore >= 0 && callsAfter >= 0;
            long calls = callsAvailable ? Math.max(0, callsAfter - statementCallsBefore) : 0;
            return new TargetQueries(true, null, requests, calls, callsAvailable, rowsRead,
                    relations);
        } catch (Exception e) {
            return TargetQueries.unavailable(
                    "could not read the target's statistics views: " + describe(e));
        }
    }

    private Map<String, long[]> readScans() throws Exception {
        Map<String, long[]> scans = new LinkedHashMap<>();
        try (Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery(SCANS)) {
            while (rows.next()) {
                scans.put(rows.getString("relname"), new long[] {
                    rows.getLong("seq_scan"), rows.getLong("idx_scan"),
                    rows.getLong("idx_tup_fetch"), rows.getLong("seq_tup_read"),
                });
            }
        }
        return scans;
    }

    /**
     * Waits for the counters to stop moving.
     *
     * <p>This cannot be made exact. {@code pg_stat_force_next_flush()} only affects the calling
     * backend, and the backends that served the target's queries belong to the target's own
     * connection pool — unreachable from here. Once the load stops those connections go idle with
     * up to a second of counters unreported, and nothing outside the target can make them report.
     * So: wait until three consecutive reads agree, and describe the result as a lower bound.
     */
    private Map<String, long[]> readSettledScans() throws Exception {
        Map<String, long[]> previous = readScans();
        int stable = 1;
        long deadline = System.nanoTime() + SETTLE_BUDGET_MILLIS * 1_000_000L;
        while (System.nanoTime() < deadline) {
            try {
                Thread.sleep(SETTLE_POLL_MILLIS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return previous;
            }
            Map<String, long[]> current = readScans();
            stable = totalScans(current) == totalScans(previous) ? stable + 1 : 1;
            previous = current;
            if (stable >= SETTLE_STABLE_READS) {
                return current;
            }
        }
        return previous;
    }

    private static long totalScans(Map<String, long[]> scans) {
        long total = 0;
        for (long[] value : scans.values()) {
            total += value[0] + value[1];
        }
        return total;
    }

    /** -1 when pg_stat_statements is not installed, which is the common case. */
    private long readStatementCalls() {
        try (Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery(
                        // Exclude this observer's own reads of the statistics views, which
                        // would otherwise be counted as statements the target ran.
                        "SELECT COALESCE(sum(calls), 0)::bigint FROM pg_stat_statements "
                        + "WHERE query NOT LIKE '%pg_stat%'")) {
            return rows.next() ? rows.getLong(1) : -1;
        } catch (Exception e) {
            return -1;
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
            // closing a read-only observation connection cannot fail a completed run
        }
    }
}
