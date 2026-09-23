package com.lockstep.cli;

import com.lockstep.config.Config;
import com.lockstep.config.ConfigLoader;
import com.lockstep.config.ConfigValidationException;
import com.lockstep.core.RunCoordinator;
import com.lockstep.core.RunProgress;
import com.lockstep.report.CliTables;
import com.lockstep.report.JsonExport;
import com.lockstep.report.LiveProgress;
import com.lockstep.report.RunReport;
import com.lockstep.util.Ansi;
import com.lockstep.util.Durations;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
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

    @Option(names = "--buckets", description = "Print the per-bucket table for each runner.")
    boolean showBuckets;

    @Option(names = "--no-progress", description = "Suppress the live progress line on stderr.")
    boolean noProgress;

    @Option(names = "--json", description = "Also write the results as JSON, for CI and `compare`.")
    Path jsonPath;

    @Override
    public Integer call() {
        PrintWriter out = spec.commandLine().getOut();
        PrintWriter err = spec.commandLine().getErr();

        Config config;
        try {
            if (!Files.exists(configPath)) {
                err.println(Ansi.error("config file not found: " + configPath));
                return EXIT_CONFIG_ERROR;
            }
            config = applyOverrides(ConfigLoader.load(configPath));
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
            result = RunCoordinator.execute(config, progress);
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
        if (jsonPath != null) {
            JsonExport.write(RunReport.from(result, Version.value()), jsonPath);
            out.println();
            out.println("results written to " + jsonPath);
        }
        out.println();
        out.println(CliTables.precisionNote());
        out.flush();
        return EXIT_SUCCESS;
    }

    private Config applyOverrides(Config config) {
        long durationNanos = duration == null ? config.duration() : Durations.parseToNanos(duration);
        long rampNanos = ramp == null ? config.ramp() : Durations.parseToNanos(ramp);
        int workers = concurrency == null ? config.concurrency() : concurrency;
        return new Config(durationNanos, config.bucketWidth(), rampNanos, workers,
                config.http(), config.db(), config.redis(), config.scenario());
    }
}
