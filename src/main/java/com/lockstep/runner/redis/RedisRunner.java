package com.lockstep.runner.redis;

import com.lockstep.config.QuerySpec;
import com.lockstep.config.RedisConfig;
import com.lockstep.core.Operation;
import com.lockstep.core.PacedLoop;
import com.lockstep.core.RunContext;
import com.lockstep.core.RunProgress;
import com.lockstep.core.Runner;
import com.lockstep.runner.QueryLabels;
import com.lockstep.runner.QueryPicker;
import com.lockstep.stats.BucketSeries;
import com.lockstep.stats.HistogramRecorder;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import io.lettuce.core.codec.StringCodec;
import io.lettuce.core.output.ArrayOutput;
import io.lettuce.core.protocol.CommandArgs;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

public final class RedisRunner implements Runner {
    private static final Duration COMMAND_TIMEOUT = Duration.ofSeconds(10);

    private static final int SLOWLOG_FETCH = 128;
    private static final int MAX_SLOWLOG_ENTRIES = 10;

    private final RedisClient client;
    private final StatefulRedisConnection<String, String> connection;
    private final RedisCommands<String, String> commands;
    private final QueryPicker picker;
    private final int rate;
    private final Map<String, RedisCommand> parsed = new ConcurrentHashMap<>();
    private final List<String> commandLabels;

    private final AtomicReference<String> lastFailure = new AtomicReference<>();

    private volatile HistogramRecorder[] commandRecorders;
    private volatile RunContext runContext;

    private volatile long slowlogWatermark = -1;
    private final List<SlowlogEntry> slowlog = new ArrayList<>();
    private volatile String slowlogNote;

    private RedisRunner(RedisClient client, StatefulRedisConnection<String, String> connection,
            QueryPicker picker, int rate) {
        this.client = client;
        this.connection = connection;
        this.commands = connection.sync();
        this.picker = picker;
        this.rate = rate;
        this.commandLabels = QueryLabels.forQueries(picker.queries());
    }

    public record SlowlogEntry(long id, long durationMicros, String command, String client) {}

    public static RedisRunner create(RedisConfig config, int concurrency) {
        RedisConfig.Target target = config.target();

        for (QuerySpec query : target.queries()) {
            RedisCommand.parse(query.query());
        }

        RedisURI.Builder uri = RedisURI.builder()
                .withHost(hostOf(target.addr()))
                .withPort(portOf(target.addr()))
                .withDatabase(target.db())
                .withTimeout(COMMAND_TIMEOUT);
        if (target.password() != null && !target.password().isEmpty()) {
            uri.withPassword(target.password().toCharArray());
        }

        RedisClient client = RedisClient.create(uri.build());
        try {
            StatefulRedisConnection<String, String> connection = client.connect();
            connection.sync().ping();
            return new RedisRunner(client, connection, new QueryPicker(target.queries()), config.rate());
        } catch (RuntimeException e) {
            client.shutdown();
            throw e;
        }
    }

    @Override
    public PacedLoop.LoopResult run(RunContext context) {
        return run(context, null);
    }

    public PacedLoop.LoopResult run(RunContext context, RunProgress.Counter progress) {
        this.runContext = context;
        int buckets = HistogramRecorder.bucketsFor(context.durationNanos(), context.bucketWidthNanos());
        HistogramRecorder[] recorders = new HistogramRecorder[commandLabels.size()];
        for (int i = 0; i < recorders.length; i++) {
            recorders[i] = new HistogramRecorder(context.bucketWidthNanos(), buckets);
        }
        this.commandRecorders = recorders;

        this.slowlogWatermark = newestSlowlogId();
        return PacedLoop.run(context, rate, this::executeOne, progress);
    }

    @Override
    public String name() {
        return "redis";
    }

    private Operation.Outcome executeOne(long scheduledOffsetNanos) {
        int index = picker.pickIndex();
        long startedAt = System.nanoTime();
        boolean success = false;
        try {
            Operation.Outcome outcome = dispatch(picker.queries().get(index));
            success = outcome.success();
            return outcome;
        } finally {
            recordCommand(index, scheduledOffsetNanos, startedAt, success);
        }
    }

    private Operation.Outcome dispatch(QuerySpec query) {
        RedisCommand command = parsed.computeIfAbsent(query.query(), RedisCommand::parse);
        try {
            CommandArgs<String, String> args = new CommandArgs<>(StringCodec.UTF8);
            for (String arg : command.args()) {
                args.add(arg);
            }
            commands.dispatch(command.keyword(), new ArrayOutput<>(StringCodec.UTF8), args);
            return Operation.Outcome.OK;
        } catch (Exception e) {
            String message = e.getMessage();
            String described = e.getClass().getSimpleName() + (message == null ? "" : ": " + message);
            lastFailure.set(described);
            return Operation.Outcome.failed(described);
        }
    }

    private void recordCommand(int index, long scheduledOffsetNanos, long startedAt, boolean success) {
        HistogramRecorder[] recorders = commandRecorders;
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

    public Map<String, BucketSeries> commandBreakdown() {
        HistogramRecorder[] recorders = commandRecorders;
        Map<String, BucketSeries> out = new LinkedHashMap<>();
        if (recorders == null) {
            return out;
        }
        for (int i = 0; i < recorders.length && i < commandLabels.size(); i++) {
            BucketSeries series = recorders[i].snapshot();
            if (series.totalCount() > 0) {
                out.put(commandLabels.get(i), series);
            }
        }
        return out;
    }

    public void captureSlowlog(long thresholdNanos) {
        slowlog.clear();
        slowlogNote = null;
        HistogramRecorder[] recorders = commandRecorders;
        if (recorders == null || thresholdNanos <= 0 || !anyCommandCrossed(recorders, thresholdNanos)) {
            return;
        }
        try {
            List<Object> entries = commands.slowlogGet(SLOWLOG_FETCH);
            for (Object entry : entries) {
                SlowlogEntry parsedEntry = parseSlowlogEntry(entry);

                if (parsedEntry == null) {
                    continue;
                }
                if (slowlogWatermark >= 0 && parsedEntry.id() <= slowlogWatermark) {
                    continue;
                }
                slowlog.add(parsedEntry);
                if (slowlog.size() >= MAX_SLOWLOG_ENTRIES) {
                    break;
                }
            }
            if (slowlog.isEmpty()) {
                slowlogNote = "the server logged nothing slower than its own "
                        + "slowlog-log-slower-than (" + slowlogThresholdDescription() + "), so the "
                        + "wait was not time spent executing these commands";
            }
        } catch (Exception e) {
            slowlogNote = "the slowlog could not be read: " + describe(e);
        }
    }

    private static boolean anyCommandCrossed(HistogramRecorder[] recorders, long thresholdNanos) {
        for (HistogramRecorder recorder : recorders) {
            BucketSeries series = recorder.snapshot();
            if (series.totalCount() > 0
                    && series.mergedServiceTime().getValueAtPercentile(99) >= thresholdNanos) {
                return true;
            }
        }
        return false;
    }

    private long newestSlowlogId() {
        try {
            List<Object> newest = commands.slowlogGet(1);
            if (newest == null || newest.isEmpty()) {
                return -1;
            }
            SlowlogEntry entry = parseSlowlogEntry(newest.get(0));
            return entry == null ? -1 : entry.id();
        } catch (Exception e) {
            return -1;
        }
    }

    private static SlowlogEntry parseSlowlogEntry(Object raw) {
        if (!(raw instanceof List<?> fields) || fields.size() < 4) {
            return null;
        }
        Long id = asLong(fields.get(0));
        Long micros = asLong(fields.get(2));
        if (id == null || micros == null) {
            return null;
        }
        StringBuilder command = new StringBuilder();
        if (fields.get(3) instanceof List<?> argv) {
            for (Object arg : argv) {
                command.append(command.isEmpty() ? "" : " ").append(arg);
            }
        }
        String client = fields.size() > 4 && fields.get(4) != null ? String.valueOf(fields.get(4)) : "";
        return new SlowlogEntry(id, micros, command.toString(), client);
    }

    private static Long asLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return value == null ? null : Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String slowlogThresholdDescription() {
        try {
            Map<String, String> config = commands.configGet("slowlog-log-slower-than");
            String value = config == null ? null : config.get("slowlog-log-slower-than");
            return value == null ? "unknown" : value + "\u00b5s";
        } catch (Exception e) {
            return "unknown";
        }
    }

    private static String describe(Exception e) {
        String message = e.getMessage();
        return e.getClass().getSimpleName() + (message == null ? "" : ": " + message);
    }

    public List<SlowlogEntry> slowlog() {
        return List.copyOf(slowlog);
    }

    public String slowlogNote() {
        return slowlogNote;
    }

    static String hostOf(String addr) {
        String trimmed = addr == null ? "" : addr.trim();
        int colon = trimmed.lastIndexOf(':');
        String host = colon < 0 ? trimmed : trimmed.substring(0, colon);
        return host.isEmpty() ? "localhost" : host;
    }

    static int portOf(String addr) {
        String trimmed = addr == null ? "" : addr.trim();
        int colon = trimmed.lastIndexOf(':');
        if (colon < 0 || colon == trimmed.length() - 1) {
            return 6379;
        }
        try {
            return Integer.parseInt(trimmed.substring(colon + 1));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("redis addr has a non-numeric port: " + addr, e);
        }
    }

    public String lastFailure() {
        return lastFailure.get();
    }

    @Override
    public void close() {
        try {
            connection.close();
        } finally {
            client.shutdown();
        }
    }
}
