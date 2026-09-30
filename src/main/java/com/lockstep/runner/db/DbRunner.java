package com.lockstep.runner.db;

import com.lockstep.config.DbConfig;
import com.lockstep.runner.QueryLabels;
import com.lockstep.runner.QueryPicker;
import com.lockstep.config.QuerySpec;
import com.lockstep.core.Operation;
import com.lockstep.core.PacedLoop;
import com.lockstep.core.RunContext;
import com.lockstep.core.RunProgress;
import com.lockstep.core.Runner;
import com.lockstep.stats.BucketSeries;
import com.lockstep.stats.HistogramRecorder;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

public final class DbRunner implements Runner {
    private static final int QUERY_TIMEOUT_SECONDS = 10;

    private static final int MAX_PLANS = 5;

    private final HikariDataSource dataSource;
    private final QueryPicker picker;
    private final int rate;
    private final List<String> queryLabels;
    private final String normalizedDriver;
    private final LongAdder connectionWaitNanos = new LongAdder();
    private final LongAdder connectionAttempts = new LongAdder();
    private final AtomicLong maxConnectionWaitNanos = new AtomicLong();

    private volatile HistogramRecorder[] queryRecorders;
    private volatile RunContext runContext;

    private final Map<String, QueryPlan> plans = new LinkedHashMap<>();
    private int plansSkipped;

    private final ResourceProbe resourceProbe;
    private volatile com.lockstep.analysis.ResourceAccounting resourceAccounting;

    private DbRunner(HikariDataSource dataSource, QueryPicker picker, int rate, String normalizedDriver) {
        this.dataSource = dataSource;
        this.picker = picker;
        this.rate = rate;
        this.normalizedDriver = normalizedDriver;
        this.queryLabels = QueryLabels.forQueries(picker.queries());
        this.resourceProbe = new ResourceProbe(dataSource, normalizedDriver, dataSource.getMaximumPoolSize());
    }

    public record QueryPlan(String label, String statement, boolean executed, String plan,
            String failure) {}

    public static DbRunner create(DbConfig config, int concurrency) {
        ConnectionStrings.JdbcTarget target =
                ConnectionStrings.toJdbc(config.target().conn(), config.target().driver());

        HikariConfig hikari = new HikariConfig();
        hikari.setJdbcUrl(target.url());
        if (target.username() != null) {
            hikari.setUsername(target.username());
        }
        if (target.password() != null) {
            hikari.setPassword(target.password());
        }

        int poolSize = config.target().poolSize() > 0 ? config.target().poolSize() : Math.max(1, concurrency);
        hikari.setMaximumPoolSize(poolSize);
        hikari.setMinimumIdle(poolSize);
        hikari.setPoolName("lockstep-db");

        hikari.setInitializationFailTimeout(10_000);
        hikari.setConnectionTimeout(10_000);

        HikariDataSource dataSource = new HikariDataSource(hikari);
        return new DbRunner(dataSource, new QueryPicker(config.target().queries()), config.rate(),
                ConnectionStrings.normalizeDriver(config.target().driver()));
    }

    @Override
    public PacedLoop.LoopResult run(RunContext context) {
        return run(context, null);
    }

    public PacedLoop.LoopResult run(RunContext context, RunProgress.Counter progress) {
        this.runContext = context;
        int buckets = HistogramRecorder.bucketsFor(context.durationNanos(), context.bucketWidthNanos());
        HistogramRecorder[] recorders = new HistogramRecorder[queryLabels.size()];
        for (int i = 0; i < recorders.length; i++) {
            recorders[i] = new HistogramRecorder(context.bucketWidthNanos(), buckets);
        }
        this.queryRecorders = recorders;

        resourceProbe.before();
        PacedLoop.LoopResult result = PacedLoop.run(context, rate, this::executeOne, progress);
        this.resourceAccounting =
                resourceProbe.after(result.executedCount(), context.durationNanos());
        return result;
    }

    /** What the run cost in bytes. Null until a run has finished. */
    public com.lockstep.analysis.ResourceAccounting resourceAccounting() {
        return resourceAccounting;
    }

    @Override
    public String name() {
        return "db";
    }

    private Operation.Outcome executeOne(long scheduledOffsetNanos) {
        int index = picker.pickIndex();
        long beforeAcquire = System.nanoTime();
        boolean success = false;
        try {
            Operation.Outcome outcome = executeQuery(picker.queries().get(index), beforeAcquire);
            success = outcome.success();
            return outcome;
        } finally {
            recordQuery(index, scheduledOffsetNanos, beforeAcquire, success);
        }
    }

    private void recordQuery(int index, long scheduledOffsetNanos, long startedAt, boolean success) {
        HistogramRecorder[] recorders = queryRecorders;
        RunContext context = runContext;
        if (recorders == null || context == null || index >= recorders.length) {
            return;
        }
        long finishedAt = System.nanoTime();
        recorders[index].record(
                scheduledOffsetNanos,
                finishedAt - context.deadlineFor(scheduledOffsetNanos),
                finishedAt - startedAt,
                success,
                null);
    }

    public Map<String, BucketSeries> queryBreakdown() {
        HistogramRecorder[] recorders = queryRecorders;
        Map<String, BucketSeries> out = new LinkedHashMap<>();
        if (recorders == null) {
            return out;
        }
        for (int i = 0; i < recorders.length && i < queryLabels.size(); i++) {
            BucketSeries series = recorders[i].snapshot();
            if (series.totalCount() > 0) {
                out.put(queryLabels.get(i), series);
            }
        }
        return out;
    }

    public void capturePlans(long thresholdNanos) {
        plans.clear();
        plansSkipped = 0;
        HistogramRecorder[] recorders = queryRecorders;
        if (recorders == null || thresholdNanos <= 0) {
            return;
        }

        record Candidate(int index, double serviceTimeNanos) {}
        List<Candidate> candidates = new java.util.ArrayList<>();
        for (int i = 0; i < recorders.length && i < queryLabels.size(); i++) {
            BucketSeries series = recorders[i].snapshot();
            if (series.totalCount() == 0
                    || series.mergedServiceTime().getValueAtPercentile(99) < thresholdNanos) {
                continue;
            }
            candidates.add(new Candidate(i, series.totalCount() * series.mergedServiceTime().getMean()));
        }

        candidates.sort(java.util.Comparator.comparingDouble(Candidate::serviceTimeNanos).reversed());
        for (Candidate candidate : candidates) {
            if (plans.size() >= MAX_PLANS) {
                plansSkipped++;
                continue;
            }
            String label = queryLabels.get(candidate.index());
            plans.put(label, capture(label, picker.queries().get(candidate.index())));
        }
    }

    public int plansSkipped() {
        return plansSkipped;
    }

    private QueryPlan capture(String label, QuerySpec query) {
        ExplainDialect.Request request =
                ExplainDialect.forQuery(normalizedDriver, query.query(), ReadWriteRouter.isRead(query));
        if (request == null) {
            return new QueryPlan(label, null, false, null,
                    "no EXPLAIN form is known for driver \"" + normalizedDriver + "\"");
        }
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(request.sql())) {
            statement.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
            bindArgs(statement, query.args());
            StringBuilder plan = new StringBuilder();
            try (ResultSet rows = statement.executeQuery()) {
                int columns = rows.getMetaData().getColumnCount();
                while (rows.next()) {
                    StringBuilder line = new StringBuilder();
                    for (int c = 1; c <= columns; c++) {
                        String value = rows.getString(c);
                        if (value != null && !value.isBlank()) {
                            line.append(line.isEmpty() ? "" : " ").append(value);
                        }
                    }
                    plan.append(line).append('\n');
                }
            }
            String text = plan.toString().stripTrailing();
            return text.isEmpty()
                    ? new QueryPlan(label, request.label(), request.executes(), null,
                            "the database returned an empty plan")
                    : new QueryPlan(label, request.label(), request.executes(), text, null);
        } catch (Exception e) {
            return new QueryPlan(label, request.label(), request.executes(), null, describe(e));
        }
    }

    public Map<String, QueryPlan> plans() {
        return new LinkedHashMap<>(plans);
    }

    private Operation.Outcome executeQuery(QuerySpec query, long beforeAcquire) {
        try (Connection connection = acquire(beforeAcquire)) {
            try (PreparedStatement statement = connection.prepareStatement(query.query())) {
                statement.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
                bindArgs(statement, query.args());
                if (ReadWriteRouter.isRead(query)) {
                    try (ResultSet rows = statement.executeQuery()) {
                        while (rows.next()) {
                        }
                    }
                } else {
                    boolean hasResultSet = statement.execute();
                    if (hasResultSet) {
                        try (ResultSet rows = statement.getResultSet()) {
                            while (rows.next()) {
                            }
                        }
                    }
                }
            }
            return Operation.Outcome.OK;
        } catch (Exception e) {
            return Operation.Outcome.failed(describe(e));
        }
    }

    private Connection acquire(long beforeAcquire) throws java.sql.SQLException {
        try {
            return dataSource.getConnection();
        } finally {
            long waited = System.nanoTime() - beforeAcquire;
            connectionAttempts.increment();
            connectionWaitNanos.add(waited);
            maxConnectionWaitNanos.accumulateAndGet(waited, Math::max);
        }
    }

    private static void bindArgs(PreparedStatement statement, List<Object> args) throws java.sql.SQLException {
        if (args == null) {
            return;
        }
        for (int i = 0; i < args.size(); i++) {
            statement.setObject(i + 1, args.get(i));
        }
    }

    private static String describe(Exception e) {
        String message = e.getMessage();
        return e.getClass().getSimpleName() + (message == null ? "" : ": " + message);
    }

    public long meanConnectionWaitNanos() {
        long attempts = connectionAttempts.sum();
        return attempts <= 0 ? 0 : connectionWaitNanos.sum() / attempts;
    }

    public long maxConnectionWaitNanos() {
        return maxConnectionWaitNanos.get();
    }

    @Override
    public void close() {
        dataSource.close();
    }
}
