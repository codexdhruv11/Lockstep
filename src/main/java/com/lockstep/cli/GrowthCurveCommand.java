package com.lockstep.cli;

import com.lockstep.analysis.GrowthCurve;
import com.lockstep.analysis.ResourceAccounting;
import com.lockstep.config.Config;
import com.lockstep.config.ConfigLoader;
import com.lockstep.config.ConfigValidationException;
import com.lockstep.core.PacedLoop;
import com.lockstep.core.RunCoordinator;
import com.lockstep.report.CliTables;
import com.lockstep.runner.db.ConnectionStrings;
import com.lockstep.util.Ansi;
import com.lockstep.util.Durations;
import com.lockstep.util.Numbers;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

@Command(name = "growth-curve",
        description = "Measure how latency scales with table size by growing the table between runs.")
public final class GrowthCurveCommand implements Callable<Integer> {
    static final int EXIT_SUCCESS = 0;
    static final int EXIT_CONFIG_ERROR = 2;
    static final int EXIT_RUN_FAILED = 3;

    static final String ROW_COUNT_PLACEHOLDER = "{{n}}";

    @Spec
    CommandSpec spec;

    @Option(names = {"-c", "--config"}, description = "Config file (default: ${DEFAULT-VALUE}).")
    Path configPath = Path.of("config.yaml");

    @Option(names = "--table", required = true,
            description = "The table being grown. Analysed after each step so the planner's "
                    + "estimates and the measured sizes stay current.")
    String table;

    @Option(names = "--seed", required = true,
            description = "Statement that adds rows, with " + ROW_COUNT_PLACEHOLDER + " where the "
                    + "number to add goes. Yours, not generated: only you know what a valid row "
                    + "for this schema looks like.")
    String seedStatement;

    @Option(names = "--steps", required = true, split = ",",
            description = "Row counts to measure at, ascending, e.g. 20000,40000,80000,160000. "
                    + "Each is a target total, not an increment.")
    List<Long> steps;

    @Option(names = "--budget", description =
            "Latency budget for p99, e.g. 300ms. Reported as the row count at which the fitted "
            + "curve crosses it.")
    String budget;

    @Option(names = "--rows-per-day", description =
            "Your real growth rate, if you want the budget crossing expressed as a date. Never "
            + "inferred: a seeded table's timestamps say nothing about how fast rows really "
            + "arrive.")
    Long rowsPerDay;

    @Option(names = "--allow-writes", description =
            "Required. This command INSERTS into the target. Point it at a throwaway database.")
    boolean allowWrites;

    @Override
    public Integer call() {
        PrintWriter out = spec.commandLine().getOut();
        PrintWriter err = spec.commandLine().getErr();

        Config config;
        long budgetNanos;
        try {
            if (!Files.exists(configPath)) {
                err.println(Ansi.error("config file not found: " + configPath));
                return EXIT_CONFIG_ERROR;
            }
            config = ConfigLoader.load(configPath);
            budgetNanos = budget == null ? 0 : Durations.parseToNanos(budget);
            validate(config);
        } catch (ConfigValidationException e) {
            err.println(Ansi.error(e.getMessage()));
            return EXIT_CONFIG_ERROR;
        } catch (RuntimeException e) {
            err.println(Ansi.error("could not read " + configPath + ": " + e.getMessage()));
            return EXIT_CONFIG_ERROR;
        }

        ConnectionStrings.JdbcTarget target =
                ConnectionStrings.toJdbc(config.db().target().conn(), config.db().target().driver());

        out.println(header(target));
        out.println();
        out.flush();

        List<Long> ascending = steps.stream().sorted().toList();
        List<GrowthCurve.Point> measured = new ArrayList<>();
        try {
            for (long targetRows : ascending) {
                long added = growTo(target, targetRows, err);
                analyse(target, err);

                err.println(Ansi.dim("  warming at " + Numbers.withSeparators(targetRows)
                        + " rows (added " + Numbers.withSeparators(added)
                        + "; this run is not measured)..."));
                err.flush();
                // Freshly inserted rows are not in the buffer cache, and the first run after a
                // seed pays for reading them in. Without this the smallest step is the slowest
                // and the fit is nonsense.
                RunCoordinator.execute(config, null, 0, 0);

                err.println(Ansi.dim("  measuring at " + Numbers.withSeparators(targetRows)
                        + " rows..."));
                err.flush();

                GrowthCurve.Point point = measure(config, targetRows);
                if (point == null) {
                    err.println(Ansi.error(
                            "the run at " + Numbers.withSeparators(targetRows)
                            + " rows produced no database measurements"));
                    return EXIT_RUN_FAILED;
                }
                measured.add(point);
            }
        } catch (RuntimeException e) {
            err.println(Ansi.error(e.getMessage() == null ? e.toString() : e.getMessage()));
            return EXIT_RUN_FAILED;
        } catch (Exception e) {
            err.println(Ansi.error("growing the table failed: " + e.getMessage()));
            return EXIT_RUN_FAILED;
        }

        GrowthCurve curve = GrowthCurve.fit(measured);
        out.print(CliTables.growthCurveTable(curve));
        out.println();
        out.print(CliTables.growthCurveVerdict(curve, budgetNanos, rowsPerDay, table));
        out.flush();
        return EXIT_SUCCESS;
    }

    private void validate(Config config) {
        if (!allowWrites) {
            throw new ConfigValidationException("allow-writes",
                    "this command INSERTS into " + table + " and will change the target's data. "
                    + "Re-run with --allow-writes, pointed at a database you can throw away.");
        }
        if (config.db() == null) {
            throw new ConfigValidationException("db",
                    "growth-curve measures a database, so the config needs a db section");
        }
        if (!"postgresql".equals(
                ConnectionStrings.normalizeDriver(config.db().target().driver()))) {
            throw new ConfigValidationException("db.target.driver",
                    "growth-curve needs PostgreSQL: the row counts and sizes it reports come from "
                    + "the statistics views");
        }
        if (!seedStatement.contains(ROW_COUNT_PLACEHOLDER)) {
            throw new ConfigValidationException("seed",
                    "the seed statement must contain " + ROW_COUNT_PLACEHOLDER
                    + ", which is replaced by the number of rows to add for each step");
        }
        if (steps == null || steps.isEmpty()) {
            throw new ConfigValidationException("steps", "at least one step is needed");
        }
        if (steps.size() < GrowthCurve.MIN_POINTS) {
            throw new ConfigValidationException("steps",
                    "at least " + GrowthCurve.MIN_POINTS + " steps are needed to describe a curve; "
                    + "two points are a line through whatever noise they contain");
        }
        if (steps.stream().anyMatch(step -> step == null || step <= 0)) {
            throw new ConfigValidationException("steps", "every step must be a positive row count");
        }
        if (steps.stream().distinct().count() != steps.size()) {
            throw new ConfigValidationException("steps", "the steps must be distinct row counts");
        }
        if (!table.matches("[A-Za-z_][A-Za-z0-9_$]*(\\.[A-Za-z_][A-Za-z0-9_$]*)?")) {
            throw new ConfigValidationException("table",
                    "\"" + table + "\" is not a plain table name; this value is interpolated into "
                    + "ANALYZE and a count, so it is deliberately restricted");
        }
    }

    private String header(ConnectionStrings.JdbcTarget target) {
        return """
                growth curve · table %s · steps %s
                %s
                WRITING to %s — every step INSERTs rows and they are not removed afterwards"""
                .formatted(table,
                        steps.stream().sorted().map(Numbers::withSeparators).toList(),
                        Ansi.dim("  seed: " + seedStatement.replaceAll("\\s+", " ").trim()),
                        target.url());
    }

    /** Adds however many rows are needed to reach {@code targetRows}, and returns how many. */
    private long growTo(ConnectionStrings.JdbcTarget target, long targetRows, PrintWriter err)
            throws Exception {
        try (Connection connection = connect(target)) {
            long current = countRows(connection);
            if (current >= targetRows) {
                return 0;
            }
            long toAdd = targetRows - current;
            err.println(Ansi.dim("  growing " + table + " from "
                    + Numbers.withSeparators(current) + " to "
                    + Numbers.withSeparators(targetRows) + " rows..."));
            err.flush();
            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate(
                        seedStatement.replace(ROW_COUNT_PLACEHOLDER, Long.toString(toAdd)));
            }
            return toAdd;
        }
    }

    private void analyse(ConnectionStrings.JdbcTarget target, PrintWriter err) throws Exception {
        try (Connection connection = connect(target);
                Statement statement = connection.createStatement()) {
            statement.execute("ANALYZE " + table);
        } catch (Exception e) {
            // Without fresh statistics the planner may keep an old plan and the row estimate will
            // lag, so this is worth saying out loud rather than swallowing.
            err.println(Ansi.error("ANALYZE " + table + " failed: " + e.getMessage()
                    + " — row counts and plans for this step may be stale"));
        }
    }

    private long countRows(Connection connection) throws Exception {
        try (Statement statement = connection.createStatement();
                var rows = statement.executeQuery("SELECT count(*) FROM " + table)) {
            return rows.next() ? rows.getLong(1) : 0;
        }
    }

    private Connection connect(ConnectionStrings.JdbcTarget target) throws Exception {
        return DriverManager.getConnection(target.url(), target.username(), target.password());
    }

    private GrowthCurve.Point measure(Config config, long targetRows) {
        RunCoordinator.RunResult result = RunCoordinator.execute(config, null, 0, 0);
        PacedLoop.LoopResult loop = result.byRunner().get("db");
        if (loop == null) {
            return null;
        }
        var summary = loop.series().summarize("db", result.context().durationNanos());
        ResourceAccounting accounting =
                result.dbRunner() == null ? null : result.dbRunner().resourceAccounting();

        long bytesPerRequest = 0;
        double hitRatio = -1;
        if (accounting != null && accounting.available()) {
            for (var relation : accounting.byBytesTouchedDescending()) {
                if (relation.name().equalsIgnoreCase(bareTableName())) {
                    bytesPerRequest = accounting.bytesPerRequest(relation);
                    hitRatio = relation.hitRatio();
                    break;
                }
            }
        }
        long rows = measuredRows(accounting, targetRows);
        return new GrowthCurve.Point(rows, summary.serviceP99Nanos(), summary.p99Nanos(),
                bytesPerRequest, hitRatio, summary.achievedRatePerSecond());
    }

    private long measuredRows(ResourceAccounting accounting, long fallback) {
        if (accounting == null || !accounting.available()) {
            return fallback;
        }
        for (var relation : accounting.relations()) {
            if (relation.name().equalsIgnoreCase(bareTableName()) && relation.liveRows() > 0) {
                return relation.liveRows();
            }
        }
        return fallback;
    }

    private String bareTableName() {
        int dot = table.indexOf('.');
        return dot < 0 ? table : table.substring(dot + 1);
    }
}
