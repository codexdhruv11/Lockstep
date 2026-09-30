package com.lockstep.runner.db;

import com.lockstep.analysis.ResourceAccounting;
import com.lockstep.analysis.WriteAmplification;
import java.lang.management.ManagementFactory;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;

/**
 * Measures what the run cost in bytes: table and index size, blocks touched per request, and the
 * point at which a relation outgrows the buffer cache.
 *
 * <p>Which relations the run touched is <em>measured</em>, not parsed out of the configured SQL.
 * {@code pg_statio_user_tables} is snapshotted before and after; a relation whose block counters
 * moved is a relation the run read. Parsing SQL to guess at table names would be wrong for views,
 * CTEs, triggers and anything reached through a function.
 *
 * <p>The counters are per-database, not per-connection, so anything else querying the same
 * database during the run is counted too. The report says so rather than pretending otherwise.
 */
final class ResourceProbe {

    private static final String BLOCK_COUNTERS = """
            SELECT io.relid, s.relname,
                   io.heap_blks_hit, io.heap_blks_read,
                   COALESCE(io.idx_blks_hit, 0)  AS idx_blks_hit,
                   COALESCE(io.idx_blks_read, 0) AS idx_blks_read
            FROM pg_statio_user_tables io
            JOIN pg_stat_user_tables s ON s.relid = io.relid
            """;

    /**
     * Row counts come from {@code pg_class.reltuples} — the planner's own estimate, set by
     * ANALYZE and VACUUM — and not from {@code pg_stat_user_tables.n_live_tup}.
     *
     * <p>n_live_tup is a running counter maintained by the cumulative statistics system, and it
     * can double-count a bulk load: ANALYZE writes the true count, then inserts still pending in
     * a backend's unflushed statistics are added on top. Measured on postgres:16 — 20,000 rows
     * inserted then analysed reported n_live_tup 40,000, while the table's byte size
     * corresponded to exactly 20,000 and reltuples read 20,000. The same sequence with a longer
     * pause before reading reported 20,000, so this is a race against the statistics flush
     * rather than a fixed offset — which is precisely why it is not a number to report.
     *
     * <p>reltuples is -1 on a relation that has never been analysed, so n_live_tup is the
     * fallback for that case only.
     */
    private static final String WAL_STATS = """
            SELECT wal_records, wal_fpi, wal_bytes, wal_buffers_full, wal_sync, wal_sync_time
            FROM pg_stat_wal
            """;

    private static final String TUPLE_COUNTERS = """
            SELECT s.relid, s.relname, s.n_tup_ins, s.n_tup_upd, s.n_tup_del, s.n_tup_hot_upd
            FROM pg_stat_user_tables s
            """;

    /**
     * Index load per table. Partial indexes are counted separately because one only takes an
     * entry when the inserted row matches its predicate, so the total is an upper bound on
     * entries per insert rather than a measurement.
     */
    private static final String INDEX_COUNTS = """
            SELECT c.relname,
                   count(*) AS index_count,
                   count(*) FILTER (WHERE i.indpred IS NOT NULL) AS partial_count
            FROM pg_index i
            JOIN pg_class c ON c.oid = i.indrelid
            JOIN pg_namespace n ON n.oid = c.relnamespace
            WHERE n.nspname NOT IN ('pg_catalog', 'information_schema')
            GROUP BY c.relname
            """;

    private static final String SIZES = """
            SELECT s.relid, s.relname,
                   CASE WHEN c.reltuples >= 0 THEN c.reltuples::bigint
                        ELSE GREATEST(s.n_live_tup, 0) END AS est_rows,
                   pg_table_size(s.relid)   AS table_bytes,
                   pg_indexes_size(s.relid) AS index_bytes
            FROM pg_stat_user_tables s
            JOIN pg_class c ON c.oid = s.relid
            """;

    private record Counters(long heapHit, long heapRead, long idxHit, long idxRead) {
        long hit() {
            return heapHit + idxHit;
        }

        long read() {
            return heapRead + idxRead;
        }
    }

    private final DataSource dataSource;
    private final String normalizedDriver;
    private final int poolSize;

    private Map<Long, Counters> before;
    private long[] walBefore;
    private Map<Long, ResourceAccounting.Relation> sizesByRelid = new LinkedHashMap<>();
    private Map<Long, long[]> tuplesBefore;
    private Map<Long, ResourceAccounting.Relation> sizesBefore;
    private String unavailableReason;
    private long generatorAllocatedAtStart = -1;

    ResourceProbe(DataSource dataSource, String normalizedDriver, int poolSize) {
        this.dataSource = dataSource;
        this.normalizedDriver = normalizedDriver;
        this.poolSize = Math.max(1, poolSize);
    }

    boolean supported() {
        return "postgresql".equals(normalizedDriver);
    }

    void before() {
        before = null;
        unavailableReason = null;
        generatorAllocatedAtStart = generatorAllocatedBytes();
        if (!supported()) {
            unavailableReason = "resource accounting needs PostgreSQL's statistics views; "
                    + "this run used driver \"" + normalizedDriver + "\"";
            return;
        }
        try {
            before = readCounters();
            walBefore = readWalStats();
            tuplesBefore = readTupleCounters();
            // Needed to measure how much the tables grew: a write workload cannot get its row
            // size from reltuples, which is whatever the last ANALYZE saw.
            sizesBefore = readSizes();
        } catch (Exception e) {
            unavailableReason = "could not read pg_statio_user_tables before the run: " + describe(e);
        }
    }

    ResourceAccounting after(long requests, long runDurationNanos) {
        long allocated = generatorAllocatedAtStart < 0
                ? 0
                : Math.max(0, generatorAllocatedBytes() - generatorAllocatedAtStart);

        if (unavailableReason != null) {
            return ResourceAccounting.unavailable(unavailableReason);
        }
        if (before == null) {
            return ResourceAccounting.unavailable("the run did not record a starting snapshot");
        }
        try {
            flushPooledBackendStatistics();
            Map<Long, Counters> after = readSettledCounters();
            long[] settings = readSettings();
            long sharedBuffers = settings[0];
            long blockSize = settings[1];
            Map<Long, ResourceAccounting.Relation> sized = readSizes();
            this.sizesByRelid = sized;

            List<ResourceAccounting.Relation> touched = new ArrayList<>();
            for (Map.Entry<Long, Counters> entry : after.entrySet()) {
                Counters start = before.get(entry.getKey());
                Counters end = entry.getValue();
                long hit = end.hit() - (start == null ? 0 : start.hit());
                long read = end.read() - (start == null ? 0 : start.read());
                if (hit + read <= 0) {
                    continue;
                }
                ResourceAccounting.Relation size = sized.get(entry.getKey());
                if (size == null) {
                    continue;
                }
                touched.add(new ResourceAccounting.Relation(size.name(), size.liveRows(),
                        size.tableBytes(), size.indexBytes(), hit, read));
            }

            return new ResourceAccounting(true, null, touched, sharedBuffers, blockSize,
                    allocated, requests, runDurationNanos);
        } catch (Exception e) {
            return ResourceAccounting.unavailable(
                    "could not read the statistics views after the run: " + describe(e));
        }
    }

    /** How long to wait for the statistics system to catch up with the run before reading it. */
    private static final long SETTLE_BUDGET_MILLIS = 10_000;

    private static final long SETTLE_POLL_MILLIS = 500;

    /**
     * Consecutive identical reads required before the counters count as settled. Must span more
     * than Postgres's one-second minimum flush interval: each backend reports at transaction end
     * but no more often than once a second, so with a connection pool the flushes are staggered
     * and two reads 500ms apart can both land between them and look stable when they are not.
     * Three reads span a full second of quiet.
     */
    private static final int SETTLE_STABLE_READS = 3;

    /**
     * Reads the block counters once they have stopped moving.
     *
     * <p>A backend reports its I/O counters to the cumulative statistics system at transaction
     * end, but no more often than once a second. Reading immediately after the load stops
     * therefore misses whatever the last second's transactions have not yet flushed, and
     * <em>undercounts</em> blocks per request — measured on postgres:16, a query that must scan a
     * 4.86MB table reported 3.74MB and then 2.53MB per request on consecutive runs of the same
     * test, varying with how much had flushed by the time of the read.
     *
     * <p>So poll until two consecutive reads agree, or the budget runs out. Settling is normally
     * one extra poll; the budget only binds when something else is still writing to the database,
     * in which case the counters were never going to be exclusively ours anyway.
     */
    private Map<Long, Counters> readSettledCounters() throws Exception {
        Map<Long, Counters> previous = readCounters();
        int stable = 1;
        long deadline = System.nanoTime() + SETTLE_BUDGET_MILLIS * 1_000_000L;
        while (System.nanoTime() < deadline) {
            try {
                Thread.sleep(SETTLE_POLL_MILLIS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return previous;
            }
            Map<Long, Counters> current = readCounters();
            stable = totalBlocks(current) == totalBlocks(previous) ? stable + 1 : 1;
            previous = current;
            if (stable >= SETTLE_STABLE_READS) {
                return current;
            }
        }
        return previous;
    }

    /**
     * Makes every pooled backend report its pending I/O counters.
     *
     * <p>A backend flushes its statistics at transaction end, and then only if a second has
     * passed since its last flush. A connection sitting idle in a pool has no next transaction,
     * so whatever it accumulated in the final second of the run is never reported — reading
     * pg_statio_user_tables afterwards silently undercounts. Measured on postgres:16 with a
     * four-connection pool: 80 sequential-scan queries that each touch 589 blocks reported
     * 41,823 blocks, exactly 71 queries' worth, with no errors and nothing shed. The same
     * queries run as separate psql processes reported 590 per query, because process exit
     * flushes unconditionally.
     *
     * <p>Every pool connection is therefore borrowed at once — so that each distinct backend is
     * reached — and told to flush. Best effort: pg_stat_force_next_flush() arrived in
     * PostgreSQL 15, and on anything older the settle loop is all there is.
     */
    private void flushPooledBackendStatistics() {
        List<Connection> held = new ArrayList<>(poolSize);
        try {
            for (int i = 0; i < poolSize; i++) {
                Connection connection;
                try {
                    connection = dataSource.getConnection();
                } catch (Exception e) {
                    break;
                }
                held.add(connection);
                try (Statement statement = connection.createStatement()) {
                    statement.execute("SELECT pg_stat_force_next_flush()");
                } catch (Exception e) {
                    // Older than PostgreSQL 15: nothing to do but let the settle loop wait.
                    return;
                }
            }
        } finally {
            for (Connection connection : held) {
                try {
                    connection.close();
                } catch (Exception ignored) {
                    // returning a connection to the pool cannot fail the measurement
                }
            }
        }
    }

    private static long totalBlocks(Map<Long, Counters> counters) {
        long total = 0;
        for (Counters value : counters.values()) {
            total += value.hit() + value.read();
        }
        return total;
    }

    /** {wal_records, wal_fpi, wal_bytes, wal_buffers_full, wal_sync, wal_sync_time}. */
    private long[] readWalStats() throws Exception {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery(WAL_STATS)) {
            if (!rows.next()) {
                return new long[6];
            }
            return new long[] {
                rows.getLong("wal_records"), rows.getLong("wal_fpi"), rows.getLong("wal_bytes"),
                rows.getLong("wal_buffers_full"), rows.getLong("wal_sync"),
                (long) rows.getDouble("wal_sync_time"),
            };
        }
    }

    /** relid -> {n_tup_ins, n_tup_upd, n_tup_del, n_tup_hot_upd}. */
    private Map<Long, long[]> readTupleCounters() throws Exception {
        Map<Long, long[]> counters = new LinkedHashMap<>();
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(TUPLE_COUNTERS);
                ResultSet rows = statement.executeQuery()) {
            while (rows.next()) {
                counters.put(rows.getLong("relid"), new long[] {
                    rows.getLong("n_tup_ins"), rows.getLong("n_tup_upd"),
                    rows.getLong("n_tup_del"), rows.getLong("n_tup_hot_upd"),
                });
            }
        }
        return counters;
    }

    /** table name -> {index_count, partial_count}. */
    private Map<String, int[]> readIndexCounts() throws Exception {
        Map<String, int[]> counts = new LinkedHashMap<>();
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery(INDEX_COUNTS)) {
            while (rows.next()) {
                counts.put(rows.getString("relname"), new int[] {
                    rows.getInt("index_count"), rows.getInt("partial_count"),
                });
            }
        }
        return counts;
    }

    private boolean walTimingTracked() {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery(
                        "SELECT setting FROM pg_settings WHERE name = 'track_wal_io_timing'")) {
            return rows.next() && "on".equalsIgnoreCase(rows.getString(1));
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * What the run's writes cost. Called after {@link #after}, so the forced flush and settle
     * have already happened — the WAL counters are reported through the same statistics system
     * and need the same wait.
     */
    WriteAmplification writeAmplification() {
        if (unavailableReason != null) {
            return WriteAmplification.unavailable(unavailableReason);
        }
        if (walBefore == null || tuplesBefore == null) {
            return WriteAmplification.unavailable("the run did not record a starting snapshot");
        }
        try {
            long[] walAfter = readWalStats();
            Map<Long, long[]> tuplesAfter = readTupleCounters();
            Map<String, int[]> indexes = readIndexCounts();

            List<WriteAmplification.Relation> relations = new ArrayList<>();
            for (Map.Entry<Long, long[]> entry : tuplesAfter.entrySet()) {
                long[] start = tuplesBefore.get(entry.getKey());
                long[] end = entry.getValue();
                long inserts = end[0] - (start == null ? 0 : start[0]);
                long updates = end[1] - (start == null ? 0 : start[1]);
                long deletes = end[2] - (start == null ? 0 : start[2]);
                long hot = end[3] - (start == null ? 0 : start[3]);
                if (inserts + updates + deletes <= 0) {
                    continue;
                }
                ResourceAccounting.Relation size = sizesByRelid.get(entry.getKey());
                String name = size == null ? "?" : size.name();
                int[] indexCount = indexes.getOrDefault(name, new int[] {0, 0});
                ResourceAccounting.Relation sizeBefore = sizesBefore == null
                        ? null : sizesBefore.get(entry.getKey());
                long tableGrown = size == null || sizeBefore == null
                        ? 0 : Math.max(0, size.tableBytes() - sizeBefore.tableBytes());
                long indexGrown = size == null || sizeBefore == null
                        ? 0 : Math.max(0, size.indexBytes() - sizeBefore.indexBytes());
                relations.add(new WriteAmplification.Relation(name, inserts, updates, deletes, hot,
                        indexCount[0], indexCount[1], size == null ? 0 : size.bytesPerRow(),
                        tableGrown, indexGrown));
            }

            return new WriteAmplification(true, null,
                    Math.max(0, walAfter[2] - walBefore[2]),
                    Math.max(0, walAfter[0] - walBefore[0]),
                    Math.max(0, walAfter[1] - walBefore[1]),
                    Math.max(0, walAfter[3] - walBefore[3]),
                    Math.max(0, walAfter[4] - walBefore[4]),
                    Math.max(0, walAfter[5] - walBefore[5]),
                    walTimingTracked(),
                    relations);
        } catch (Exception e) {
            return WriteAmplification.unavailable(
                    "could not read pg_stat_wal after the run: " + describe(e));
        }
    }

    private Map<Long, Counters> readCounters() throws Exception {
        Map<Long, Counters> counters = new LinkedHashMap<>();
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(BLOCK_COUNTERS);
                ResultSet rows = statement.executeQuery()) {
            while (rows.next()) {
                counters.put(rows.getLong("relid"), new Counters(
                        rows.getLong("heap_blks_hit"),
                        rows.getLong("heap_blks_read"),
                        rows.getLong("idx_blks_hit"),
                        rows.getLong("idx_blks_read")));
            }
        }
        return counters;
    }

    private Map<Long, ResourceAccounting.Relation> readSizes() throws Exception {
        Map<Long, ResourceAccounting.Relation> sizes = new LinkedHashMap<>();
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(SIZES);
                ResultSet rows = statement.executeQuery()) {
            while (rows.next()) {
                sizes.put(rows.getLong("relid"), new ResourceAccounting.Relation(
                        rows.getString("relname"),
                        rows.getLong("est_rows"),
                        rows.getLong("table_bytes"),
                        rows.getLong("index_bytes"),
                        0, 0));
            }
        }
        return sizes;
    }

    /**
     * Returns {shared_buffers bytes, block_size bytes}.
     *
     * <p>{@code pg_settings.shared_buffers} is a count of blocks, not bytes — it reads 16384 on a
     * server with 128MB of buffers. {@code pg_size_bytes(current_setting(...))} converts from the
     * setting's own declared unit instead, which stays correct on a server built with a
     * non-default block size.
     */
    private long[] readSettings() throws Exception {
        String sql = "SELECT pg_size_bytes(current_setting('shared_buffers')) AS shared_buffers,"
                + " current_setting('block_size')::bigint AS block_size";
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery(sql)) {
            if (!rows.next()) {
                return new long[] {0, 0};
            }
            return new long[] {rows.getLong("shared_buffers"), rows.getLong("block_size")};
        }
    }

    private static long generatorAllocatedBytes() {
        if (ManagementFactory.getThreadMXBean()
                instanceof com.sun.management.ThreadMXBean bean) {
            try {
                if (bean.isThreadAllocatedMemorySupported()) {
                    bean.setThreadAllocatedMemoryEnabled(true);
                    return bean.getTotalThreadAllocatedBytes();
                }
            } catch (UnsupportedOperationException | SecurityException e) {
                return -1;
            }
        }
        return -1;
    }

    private static String describe(Exception e) {
        String message = e.getMessage();
        return e.getClass().getSimpleName() + (message == null ? "" : ": " + message);
    }
}
