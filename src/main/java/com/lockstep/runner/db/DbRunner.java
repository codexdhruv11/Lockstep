package com.lockstep.runner.db;

import com.lockstep.config.DbConfig;
import com.lockstep.runner.QueryPicker;
import com.lockstep.config.QuerySpec;
import com.lockstep.core.Operation;
import com.lockstep.core.PacedLoop;
import com.lockstep.core.RunContext;
import com.lockstep.core.RunProgress;
import com.lockstep.core.Runner;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

public final class DbRunner implements Runner {
    private static final int QUERY_TIMEOUT_SECONDS = 10;

    private final HikariDataSource dataSource;
    private final QueryPicker picker;
    private final int rate;
    private final LongAdder connectionWaitNanos = new LongAdder();
    private final LongAdder connectionAttempts = new LongAdder();
    private final AtomicLong maxConnectionWaitNanos = new AtomicLong();

    private DbRunner(HikariDataSource dataSource, QueryPicker picker, int rate) {
        this.dataSource = dataSource;
        this.picker = picker;
        this.rate = rate;
    }

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
        return new DbRunner(dataSource, new QueryPicker(config.target().queries()), config.rate());
    }

    @Override
    public PacedLoop.LoopResult run(RunContext context) {
        return run(context, null);
    }

    public PacedLoop.LoopResult run(RunContext context, RunProgress.Counter progress) {
        return PacedLoop.run(context, rate, scheduledOffset -> executeOne(), progress);
    }

    @Override
    public String name() {
        return "db";
    }

    private Operation.Outcome executeOne() {
        QuerySpec query = picker.pick();
        long beforeAcquire = System.nanoTime();
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
