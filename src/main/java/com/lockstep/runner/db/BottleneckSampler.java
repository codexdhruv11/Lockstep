package com.lockstep.runner.db;

import com.lockstep.analysis.Bottleneck;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Samples the target's {@code pg_stat_activity} while the load runs, so the report can name the
 * resource that bounded it.
 *
 * <p>Sampling rather than aggregating, because the interesting facts are about simultaneity: how
 * many backends were busy at the same moment, and what they were waiting for. A cumulative counter
 * cannot answer either. The cost is that this is a statistical picture, not a census, and the
 * report says how many samples it rests on.
 *
 * <p>Its own backend is excluded from every count. A sampler that included itself would report one
 * permanently busy connection and a permanent {@code Client} wait.
 */
public final class BottleneckSampler implements AutoCloseable {

    private static final String ACTIVITY = """
            SELECT state, wait_event_type, wait_event
            FROM pg_stat_activity
            WHERE datname = current_database()
              AND pid <> pg_backend_pid()
              AND backend_type = 'client backend'
            """;

    private static final String MAX_CONNECTIONS =
            "SELECT current_setting('max_connections')::bigint";

    private final Connection connection;
    private final AtomicBoolean running = new AtomicBoolean();
    private Thread thread;

    private int samples;
    private long activeSum;
    private int activeMax;
    private final Map<Integer, Integer> activeHistogram = new LinkedHashMap<>();
    private final Map<String, Integer> waitsByType = new LinkedHashMap<>();
    private final Map<String, Integer> waitsByEvent = new LinkedHashMap<>();
    private long serverMaxConnections;
    private String failure;

    private BottleneckSampler(Connection connection) {
        this.connection = connection;
    }

    public static BottleneckSampler open(String conn, String driver) {
        ConnectionStrings.JdbcTarget target = ConnectionStrings.toJdbc(conn, driver);
        if (!"postgresql".equals(ConnectionStrings.normalizeDriver(driver))) {
            throw new IllegalArgumentException(
                    "bottleneck attribution needs PostgreSQL's pg_stat_activity; got driver \""
                    + driver + "\"");
        }
        try {
            Connection connection =
                    DriverManager.getConnection(target.url(), target.username(), target.password());
            connection.setReadOnly(true);
            return new BottleneckSampler(connection);
        } catch (Exception e) {
            throw new IllegalArgumentException(
                    "could not connect to the target's database to sample it: " + describe(e), e);
        }
    }

    public void start() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        try (Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery(MAX_CONNECTIONS)) {
            serverMaxConnections = rows.next() ? rows.getLong(1) : 0;
        } catch (Exception e) {
            serverMaxConnections = 0;
        }
        thread = new Thread(this::loop, "lockstep-bottleneck-sampler");
        thread.setDaemon(true);
        thread.start();
    }

    private void loop() {
        while (running.get()) {
            try {
                sampleOnce();
            } catch (Exception e) {
                failure = describe(e);
                return;
            }
            try {
                Thread.sleep(Bottleneck.SAMPLE_INTERVAL_MILLIS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private synchronized void sampleOnce() throws Exception {
        int active = 0;
        try (Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery(ACTIVITY)) {
            while (rows.next()) {
                String state = rows.getString("state");
                if (state == null || !state.equals("active")) {
                    continue;
                }
                active++;
                String type = rows.getString("wait_event_type");
                String event = rows.getString("wait_event");
                // Postgres reports no wait event for a backend that is genuinely running, which
                // is a distinct and important observation rather than missing data.
                String typeKey = type == null ? Bottleneck.RUNNING : type;
                String eventKey = event == null ? Bottleneck.RUNNING : type + ":" + event;
                waitsByType.merge(typeKey, 1, Integer::sum);
                waitsByEvent.merge(eventKey, 1, Integer::sum);
            }
        }
        samples++;
        activeSum += active;
        activeMax = Math.max(activeMax, active);
        activeHistogram.merge(active, 1, Integer::sum);
    }

    public synchronized Bottleneck stop() {
        running.set(false);
        if (thread != null) {
            thread.interrupt();
            try {
                thread.join(2_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        if (samples == 0) {
            return Bottleneck.unavailable(failure == null
                    ? "no samples were taken — the run may have been shorter than the sampling interval"
                    : "sampling failed: " + failure);
        }

        // The plateau is the most frequent busy-backend count, ignoring zero: a run that is idle
        // half the time still has a ceiling worth naming.
        int plateau = 0;
        int plateauCount = 0;
        for (Map.Entry<Integer, Integer> entry : activeHistogram.entrySet()) {
            if (entry.getKey() > 0 && entry.getValue() > plateauCount) {
                plateauCount = entry.getValue();
                plateau = entry.getKey();
            }
        }

        return new Bottleneck(true, null, samples, (double) activeSum / samples, activeMax,
                plateau, plateauCount, serverMaxConnections,
                new LinkedHashMap<>(waitsByType), new LinkedHashMap<>(waitsByEvent));
    }

    private static String describe(Exception e) {
        String message = e.getMessage();
        return e.getClass().getSimpleName() + (message == null ? "" : ": " + message);
    }

    @Override
    public void close() {
        running.set(false);
        try {
            connection.close();
        } catch (Exception ignored) {
            // closing a read-only sampling connection cannot fail a completed run
        }
    }
}
