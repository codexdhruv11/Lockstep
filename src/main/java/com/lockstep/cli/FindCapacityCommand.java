package com.lockstep.cli;

import com.lockstep.analysis.CapacitySearch;
import com.lockstep.config.Config;
import com.lockstep.config.ConfigLoader;
import com.lockstep.config.ConfigValidationException;
import com.lockstep.core.PacedLoop;
import com.lockstep.core.RunCoordinator;
import com.lockstep.report.CliTables;
import com.lockstep.util.Ansi;
import com.lockstep.util.Durations;
import com.lockstep.util.Numbers;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

@Command(name = "find-capacity",
        description = "Find the rate at which the target stops keeping up, by stepping the load.")
public final class FindCapacityCommand implements Callable<Integer> {
    static final int EXIT_SUCCESS = 0;
    static final int EXIT_CONFIG_ERROR = 2;
    static final int EXIT_RUN_FAILED = 3;

    private static final int DEFAULT_MAX_STEPS = 9;
    private static final int DEFAULT_REFINE_STEPS = 3;

    @Spec
    CommandSpec spec;

    @Option(names = {"-c", "--config"}, description = "Config file (default: ${DEFAULT-VALUE}).")
    Path configPath = Path.of("config.yaml");

    @Option(names = "--step", description =
            "How long to hold each rate, e.g. 10s (default: ${DEFAULT-VALUE}). Long enough that "
            + "the target reaches a steady state at that rate; short enough that the search ends.")
    String step = "10s";

    @Option(names = "--max-steps", description =
            "Most steps to run, bracketing and refining together (default: ${DEFAULT-VALUE}).")
    int maxSteps = DEFAULT_MAX_STEPS;

    @Option(names = "--concurrency", description = "Override the config's worker count.")
    Integer concurrency;

    @Override
    public Integer call() {
        PrintWriter out = spec.commandLine().getOut();
        PrintWriter err = spec.commandLine().getErr();

        Config config;
        long stepNanos;
        try {
            if (!Files.exists(configPath)) {
                err.println(Ansi.error("config file not found: " + configPath));
                return EXIT_CONFIG_ERROR;
            }
            config = ConfigLoader.load(configPath);
            if (concurrency != null) {
                config = new Config(config.duration(), config.bucketWidth(), config.ramp(),
                        concurrency, config.http(), config.db(), config.redis(), config.scenario());
            }
            stepNanos = Durations.parseToNanos(step);
            if (maxSteps < 2) {
                throw new ConfigValidationException("max-steps",
                        "at least 2 steps are needed to find a limit: one that holds and one that does not");
            }
            if (!config.hasScalableRate()) {
                throw new ConfigValidationException("scenario",
                        "find-capacity needs an http, db or redis runner with a rate to scale. "
                        + "A scenario's arrival rate comes from concurrency, so scaling it would "
                        + "change the worker pool at the same time and the answer would not mean "
                        + "what it says.");
            }
        } catch (ConfigValidationException e) {
            err.println(Ansi.error(e.getMessage()));
            return EXIT_CONFIG_ERROR;
        } catch (RuntimeException e) {
            err.println(Ansi.error("could not read " + configPath + ": " + e.getMessage()));
            return EXIT_CONFIG_ERROR;
        }

        Config stepConfig = config.withDuration(stepNanos);
        Map<String, Integer> baseRates = config.ratesByRunner();
        out.println(header(baseRates, stepNanos));
        out.flush();

        try {
            err.println(Ansi.dim("warming up (this step is not measured)..."));
            RunCoordinator.execute(stepConfig, null, 0, 0);

            CapacitySearch.Result result = CapacitySearch.search(
                    multiplier -> probe(stepConfig, multiplier, baseRates, err),
                    maxSteps, Math.min(DEFAULT_REFINE_STEPS, maxSteps - 1));

            out.println();
            out.print(CliTables.capacitySearchTable(result, baseRates));
            out.println();
            out.print(CliTables.capacitySearchVerdict(result, baseRates, config.concurrency()));
            out.flush();
            return EXIT_SUCCESS;
        } catch (RuntimeException e) {
            err.println(Ansi.error(e.getMessage() == null ? e.toString() : e.getMessage()));
            return EXIT_RUN_FAILED;
        }
    }

    private String header(Map<String, Integer> baseRates, long stepNanos) {
        return "finding capacity · step %s · up to %d steps · from %s".formatted(
                Durations.formatNanos(stepNanos), maxSteps, CliTables.describeRates(baseRates, 1.0));
    }

    private CapacitySearch.Measurement probe(Config stepConfig, double multiplier,
            Map<String, Integer> baseRates, PrintWriter err) {
        Config scaled = stepConfig.scaledBy(multiplier);
        err.println(Ansi.dim("  step at " + CliTables.describeRates(baseRates, multiplier) + "..."));
        err.flush();
        RunCoordinator.RunResult result = RunCoordinator.execute(scaled, null, 0, 0);

        double seconds = result.context().durationNanos() / 1_000_000_000.0;
        String worstRunner = null;
        long worstP99 = -1;
        double worstDelivery = 1.0;
        long scheduled = 0;
        long executed = 0;
        double requested = 0;
        double achieved = 0;

        Map<String, Integer> scaledRates = scaled.ratesByRunner();
        for (Map.Entry<String, PacedLoop.LoopResult> entry : result.byRunner().entrySet()) {
            PacedLoop.LoopResult loop = entry.getValue();
            var summary = loop.series().summarize(entry.getKey(), result.context().durationNanos());
            double delivery = loop.scheduledCount() <= 0
                    ? 1.0 : (double) loop.executedCount() / loop.scheduledCount();

            boolean worse = worstRunner == null
                    || delivery < worstDelivery
                    || (delivery >= worstDelivery && summary.p99Nanos() > worstP99);
            if (worse) {
                worstRunner = entry.getKey();
                worstP99 = summary.p99Nanos();
                worstDelivery = delivery;
                scheduled = loop.scheduledCount();
                executed = loop.executedCount();
                requested = scaledRates.getOrDefault(entry.getKey(), 0);
                achieved = seconds > 0 ? loop.executedCount() / seconds : 0;
            }
        }
        return new CapacitySearch.Measurement(requested, achieved, Math.max(0, worstP99),
                scheduled, executed, worstRunner == null ? "-" : worstRunner,
                scaled.duration());
    }

    static String formatRate(double perSecond) {
        return Numbers.rate(perSecond);
    }
}
