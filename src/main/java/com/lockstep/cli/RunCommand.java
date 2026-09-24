package com.lockstep.cli;

import com.lockstep.config.Config;
import com.lockstep.config.ConfigLoader;
import com.lockstep.config.ConfigValidationException;
import com.lockstep.analysis.CapacityFinder;
import com.lockstep.analysis.SpikeCorrelator;
import com.lockstep.core.RunCoordinator;
import com.lockstep.core.RunProgress;
import com.lockstep.report.CliTables;
import com.lockstep.report.HtmlReport;
import com.lockstep.report.JsonExport;
import com.lockstep.report.LiveProgress;
import com.lockstep.report.RunReport;
import com.lockstep.util.Ansi;
import com.lockstep.util.Durations;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

@Command(name = "run", description = "Run a load test from a config file.")
public final class RunCommand implements Callable<Integer> {
    static final int EXIT_SUCCESS = 0;
    static final int EXIT_CONFIG_ERROR = 2;
    static final int EXIT_RUN_FAILED = 3;

    @Spec
    CommandSpec spec;

    @Option(names = {"-c", "--config"}, description = "Config file (default: ${DEFAULT-VALUE}).")
    Path configPath = Path.of("config.yaml");

    @Option(names = "--duration", description = "Override the config's duration, e.g. 30s.")
    String duration;

    @Option(names = "--ramp", description = "Override the config's ramp window, e.g. 10s.")
    String ramp;

    @Option(names = "--concurrency", description = "Override the config's worker count.")
    Integer concurrency;

    @Option(names = "--warmup", description =
            "Ignore findings from this opening window, e.g. 5s. The JVM compiles itself during a "
            + "run's first moments; those buckets are still printed, just not treated as findings.")
    String warmup;

    @Option(names = "--buckets", description = "Print the per-bucket table for each runner.")
    boolean showBuckets;

    @Option(names = "--no-progress", description = "Suppress the live progress line on stderr.")
    boolean noProgress;

    @Option(names = "--json", description = "Also write the results as JSON, for CI and `compare`.")
    Path jsonPath;

    @Option(names = "--report", description = "Path for the HTML report (default: ${DEFAULT-VALUE}).")
    Path reportPath = Path.of("report.html");

    @Option(names = "--no-report", description = "Skip writing the HTML report.")
    boolean noReport;

    @Option(names = "--http-threshold", description = "App-side p99 spike threshold (default 100ms).")
    String httpThreshold;

    @Option(names = "--db-threshold", description = "Database p99 spike threshold (default 100ms).")
    String dbThreshold;

    @Option(names = "--redis-threshold", description = "Redis p99 spike threshold (default 100ms).")
    String redisThreshold;

    @Option(names = "--no-explain", description =
            "Skip the after-the-run diagnostics. By default a plan is taken for each database "
            + "query whose own p99 crossed --db-threshold, and the server's slowlog is read when "
            + "a Redis command crossed --redis-threshold.")
    boolean noExplain;

    @Override
    public Integer call() {
        PrintWriter out = spec.commandLine().getOut();
        PrintWriter err = spec.commandLine().getErr();

        Config config;
        SpikeCorrelator.Thresholds thresholds;
        long warmupNanos;
        try {
            if (!Files.exists(configPath)) {
                err.println(Ansi.error("config file not found: " + configPath));
                return EXIT_CONFIG_ERROR;
            }
            config = applyOverrides(ConfigLoader.load(configPath));

            thresholds = thresholds();
            warmupNanos = warmupNanos();
            if (warmupNanos >= config.duration()) {
                throw new ConfigValidationException("warmup",
                        "warmup must be shorter than the run's duration");
            }
        } catch (ConfigValidationException e) {
            err.println(Ansi.error(e.getMessage()));
            return EXIT_CONFIG_ERROR;
        } catch (RuntimeException e) {
            err.println(Ansi.error("could not read " + configPath + ": " + e.getMessage()));
            return EXIT_CONFIG_ERROR;
        }

        RunProgress progress = new RunProgress();
        RunCoordinator.RunResult result;
        try (LiveProgress live = new LiveProgress(progress, config.duration(), System.err)) {
            if (!noProgress) {
                live.start();
            }

            result = RunCoordinator.execute(config, progress,
                    noExplain ? 0 : thresholds.dbNanos(),
                    noExplain ? 0 : thresholds.redisNanos());
            if (!noProgress) {
                live.printTotals();
            }
        } catch (RuntimeException e) {
            err.println(Ansi.error(e.getMessage() == null ? e.toString() : e.getMessage()));
            return EXIT_RUN_FAILED;
        }

        out.println(CliTables.runHeader(result));
        out.println();
        out.print(CliTables.summaryTable(result));
        String shortfall = CliTables.shortfallNotes(result);
        if (!shortfall.isEmpty()) {
            out.println();
            out.print(shortfall);
        }
        if (showBuckets) {
            result.byRunner().forEach((name, loop) -> {
                out.println();
                out.print(CliTables.bucketTable(name, loop));
            });
        }

        List<com.lockstep.stats.Bucket> appTimeline = SpikeCorrelator.appTimelineOf(result);

        if (result.scenarioRunner() != null) {
            String steps = CliTables.stepTable(result.scenarioRunner().stepSeries(),
                    result.context().durationNanos());
            if (!steps.isEmpty()) {
                out.println();
                out.print(steps);
            }
        }

        if (result.dbRunner() != null) {
            String queries = CliTables.queryTable("db queries", result.dbRunner().queryBreakdown(),
                    result.context().durationNanos());
            if (!queries.isEmpty()) {
                out.println();
                out.print(queries);
            }
            String plans = CliTables.planSection(result.dbRunner().plans(),
                    thresholds.dbNanos(), result.dbRunner().plansSkipped());
            if (!plans.isEmpty()) {
                out.println();
                out.print(plans);
            }
        }
        if (result.redisRunner() != null) {
            String commands = CliTables.queryTable("redis commands",
                    result.redisRunner().commandBreakdown(), result.context().durationNanos());
            if (!commands.isEmpty()) {
                out.println();
                out.print(commands);
            }
        }

        CapacityFinder.Capacity capacity = appTimeline.isEmpty()
                ? CapacityFinder.Capacity.notUsable(result.context().concurrency())
                : CapacityFinder.find(appTimeline, result.context().concurrency(),
                        result.context().rampNanos(), result.context().bucketWidthNanos(),
                        warmupNanos);
        if (!appTimeline.isEmpty()) {
            out.println();
            out.print(CliTables.capacityLine(capacity, warmupNanos));
        }

        Map<String, List<com.lockstep.stats.Bucket>> storageTimelines =
                SpikeCorrelator.storageTimelinesOf(result);
        SpikeCorrelator.CorrelationResult correlation =
                SpikeCorrelator.correlate(appTimeline, storageTimelines, thresholds, warmupNanos);
        if (warmupNanos > 0) {
            out.println();
            out.println(CliTables.warmupNote(warmupNanos));
        }

        boolean hasStorage = !storageTimelines.isEmpty();
        if (hasStorage) {
            String spikes = CliTables.spikeTable(correlation);
            if (!spikes.isEmpty()) {
                out.println();
                out.print(spikes);
            }
        }
        RunReport report = RunReport.from(result, Version.value(), correlation, capacity);
        String slowlog = CliTables.slowlogSection(report.slowlog());
        if (!slowlog.isEmpty()) {
            out.println();
            out.print(slowlog);
        }
        if (jsonPath != null) {
            JsonExport.write(report, jsonPath);
            out.println();
            out.println("results written to " + jsonPath);
        }
        if (!noReport) {
            HtmlReport.write(report, reportPath);
            out.println();
            out.println("report written to " + reportPath);
        }
        out.println();
        out.println(CliTables.precisionNote());
        out.flush();
        return EXIT_SUCCESS;
    }

    private SpikeCorrelator.Thresholds thresholds() {
        SpikeCorrelator.Thresholds defaults = SpikeCorrelator.Thresholds.defaults();
        return new SpikeCorrelator.Thresholds(
                httpThreshold == null ? defaults.httpNanos() : Durations.parseToNanos(httpThreshold),
                dbThreshold == null ? defaults.dbNanos() : Durations.parseToNanos(dbThreshold),
                redisThreshold == null ? defaults.redisNanos() : Durations.parseToNanos(redisThreshold));
    }

    private long warmupNanos() {
        return warmup == null ? 0 : Durations.parseToNanos(warmup);
    }

    private Config applyOverrides(Config config) {
        long durationNanos = duration == null ? config.duration() : Durations.parseToNanos(duration);
        long rampNanos = ramp == null ? config.ramp() : Durations.parseToNanos(ramp);
        int workers = concurrency == null ? config.concurrency() : concurrency;
        return new Config(durationNanos, config.bucketWidth(), rampNanos, workers,
                config.http(), config.db(), config.redis(), config.scenario());
    }
}
