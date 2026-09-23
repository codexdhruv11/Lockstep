package com.lockstep.runner.redis;

import com.lockstep.config.QuerySpec;
import com.lockstep.config.RedisConfig;
import com.lockstep.core.Operation;
import com.lockstep.core.PacedLoop;
import com.lockstep.core.RunContext;
import com.lockstep.core.RunProgress;
import com.lockstep.core.Runner;
import com.lockstep.runner.QueryPicker;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import io.lettuce.core.codec.StringCodec;
import io.lettuce.core.output.ArrayOutput;
import io.lettuce.core.protocol.CommandArgs;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

public final class RedisRunner implements Runner {
    private static final Duration COMMAND_TIMEOUT = Duration.ofSeconds(10);

    private final RedisClient client;
    private final StatefulRedisConnection<String, String> connection;
    private final RedisCommands<String, String> commands;
    private final QueryPicker picker;
    private final int rate;
    private final Map<String, RedisCommand> parsed = new ConcurrentHashMap<>();

    private final AtomicReference<String> lastFailure = new AtomicReference<>();

    private RedisRunner(RedisClient client, StatefulRedisConnection<String, String> connection,
            QueryPicker picker, int rate) {
        this.client = client;
        this.connection = connection;
        this.commands = connection.sync();
        this.picker = picker;
        this.rate = rate;
    }

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
        return PacedLoop.run(context, rate, this::executeOne, progress);
    }

    @Override
    public String name() {
        return "redis";
    }

    private Operation.Outcome executeOne() {
        QuerySpec query = picker.pick();
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
