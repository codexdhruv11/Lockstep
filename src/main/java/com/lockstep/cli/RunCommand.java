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

    @Option(names = "--arrivals", description =
            "Arrival model: constant (evenly paced) or poisson (exponential gaps, like real "
            + "traffic). Poisson produces higher queueing delay at the same mean rate.")
    String arrivals;

    @Option(names = "--arrival-seed", description =
            "Seed for poisson arrivals, so a run can be reproduced. 0 means random.")
    long arrivalSeed;

    @Option(names = "--no-self-audit", description =
            "Skip recording this process's own GC and safepoint pauses. By default the tool "
            + "records them and flags any that overlap a spike it reported, because a pause in "
            + "the generator looks exactly like slowness in the target.")
    boolean noSelfAudit;

    @Option(names = "--buckets", description = "Print the per-bucket table for each runner.")
    boolean showBuckets;

    @Option(names = "--distribution", description =
            "Print the latency histogram for each runner. Percentiles cannot show a bimodal "
            + "distribution; this can.")
    boolean showDistribution;

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

    @Option(names = "--trace", description =
            "Send a W3C traceparent header with every request, so the target's own "
            + "OpenTelemetry instrumentation ties its spans to it. The report then prints the "
            + "trace IDs of the slowest requests. Off by default: the header is sampled, so the "
            + "target would export a trace for every request, and most backends bill per span.")
    boolean trace;

    @Option(names = "--otlp", description =
            "An OpenTelemetry collector's metrics endpoint, e.g. "
            + "http://localhost:4318/v1/metrics. The run's results are sent there, so a load "
            + "test lands in the same dashboard as the traffic it is meant to resemble.")
    String otlpEndpoint;

    @Option(names = "--otlp-service", description =
            "service.name for the exported metrics (default: ${DEFAULT-VALUE}).")
    String otlpService = "lockstep";

    @Option(names = "--traces", description =
            "A trace backend's base URL, e.g. http://localhost:16686. The slowest requests' "
            + "traces are fetched and their time broken down here, so an ID does not have to be "
            + "looked up by hand. Needs --trace. Jaeger's query API only.")
    String tracesUrl;

    @Option(names = "--observe-metrics", description =
            "The TARGET's own metrics endpoint, e.g. http://host/actuator/prometheus. Reports the "
            + "server's own view of latency — the gap to the caller's is the queue, measured "
            + "rather than inferred — plus pool saturation, GC pauses and CPU, for any runtime.")
    String observeMetrics;

    @Option(names = "--observe-db", description =
            "Read-only connection to the TARGET's database, so the run can report how many "
            + "queries the target ran per request. Attributable only when this run is the sole "
            + "source of load.")
    String observeDb;

    @Option(names = "--observe-driver", description =
            "Driver for --observe-db (default: ${DEFAULT-VALUE}).")
    String observeDriver = "postgres";

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

        com.lockstep.analysis.SelfAudit selfAudit =
                noSelfAudit ? null : com.lockstep.analysis.SelfAudit.start();

        com.lockstep.runner.MetricsObserver metrics = null;
        if (observeMetrics != null) {
            try {
                metrics = com.lockstep.runner.MetricsObserver.open(observeMetrics);
                metrics.before();
                metrics.start();
            } catch (RuntimeException e) {
                err.println(Ansi.error(e.getMessage()));
                return EXIT_CONFIG_ERROR;
            }
        }

        com.lockstep.runner.db.TargetObserver observer = null;
        com.lockstep.runner.db.BottleneckSampler sampler = null;
        if (observeDb != null) {
            try {
                observer = com.lockstep.runner.db.TargetObserver.open(observeDb, observeDriver);
                observer.before();
                sampler = com.lockstep.runner.db.BottleneckSampler.open(observeDb, observeDriver);
                sampler.start();
            } catch (RuntimeException e) {
                err.println(Ansi.error(e.getMessage()));
                return EXIT_CONFIG_ERROR;
            }
        }

        RunProgress progress = new RunProgress();
        RunCoordinator.RunResult result;
        try (LiveProgress live = new LiveProgress(progress, config.duration(), System.err)) {
            if (!noProgress) {
                live.start();
            }

            result = RunCoordinator.execute(config, progress,
                    noExplain ? 0 : thresholds.dbNanos(),
                    noExplain ? 0 : thresholds.redisNanos(),
                    trace);
            if (!noProgress) {
                live.printTotals();
            }
        } catch (RuntimeException e) {
            err.println(Ansi.error(e.getMessage() == null ? e.toString() : e.getMessage()));
            return EXIT_RUN_FAILED;
        }

        com.lockstep.analysis.TargetMetrics targetMetrics = null;
        if (metrics != null) {
            try (var closing = metrics) {
                targetMetrics = closing.after();
            }
        }

        com.lockstep.analysis.Bottleneck bottleneck = null;
        if (sampler != null) {
            try (var closing = sampler) {
                bottleneck = closing.stop();
            }
        }

        com.lockstep.analysis.TargetQueries targetQueries = null;
        if (observer != null) {
            try (var closing = observer) {
                targetQueries = closing.after(appRequestCount(result));
            }
        }

        com.lockstep.analysis.SelfAudit.Report auditReport = null;
        if (selfAudit != null) {
            auditReport = selfAudit.stop(result.context().startWallClock(),
                    result.context().bucketWidthNanos(),
                    com.lockstep.stats.HistogramRecorder.bucketsFor(
                            result.context().durationNanos(), result.context().bucketWidthNanos()));
        }

        out.println(CliTables.runHeader(result));
        out.println();
        out.print(CliTables.summaryTable(result));
        String shortfall = CliTables.shortfallNotes(result);
        if (!shortfall.isEmpty()) {
            out.println();
            out.print(shortfall);
        }

        String failures = CliTables.errorTable(result);
        if (!failures.isEmpty()) {
            out.println();
            out.print(failures);
        }
        if (showBuckets) {
            result.byRunner().forEach((name, loop) -> {
                out.println();
                out.print(CliTables.bucketTable(name, loop));
            });
        }
        if (showDistribution) {
            result.byRunner().forEach((name, loop) -> {
                out.println();
                out.print(CliTables.distributionTable(name, loop));
            });
        }

        String targetSection = CliTables.targetQueriesTable(targetQueries);
        if (!targetSection.isEmpty()) {
            out.println();
            out.print(targetSection);
        }
        String metricsSection = CliTables.targetMetricsTable(targetMetrics,
                appMeanNanos(result), result.context().durationNanos());
        if (!metricsSection.isEmpty()) {
            out.println();
            out.print(metricsSection);
        }

        String traceSection = CliTables.slowestRequestsTable(result, trace);
        if (!traceSection.isEmpty()) {
            out.println();
            out.print(traceSection);
        }

        String breakdown = traceBreakdown(result, out, err);
        if (!breakdown.isEmpty()) {
            out.println();
            out.print(breakdown);
        }
        String bottleneckSection = CliTables.bottleneckTable(bottleneck);
        if (!bottleneckSection.isEmpty()) {
            out.println();
            out.print(bottleneckSection);
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

        if (result.httpRunner() != null) {
            String targets = CliTables.queryTable("http targets",
                    result.httpRunner().targetBreakdown(), result.context().durationNanos());
            if (!targets.isEmpty()) {
                out.println();
                out.print(targets);
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
            String resources = CliTables.resourceTable(result.dbRunner().resourceAccounting());
            if (!resources.isEmpty()) {
                out.println();
                out.print(resources);
            }
            String writes = CliTables.writeAmplificationTable(
                    result.dbRunner().writeAmplification());
            if (!writes.isEmpty()) {
                out.println();
                out.print(writes);
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
                        warmupNanos, appDeliveryRatio(result));
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
        String selfAuditOut = CliTables.selfAuditTable(auditReport, correlation,
                result.context().bucketWidthNanos(), result.context().durationNanos());
        if (!selfAuditOut.isEmpty()) {
            out.println();
            out.print(selfAuditOut);
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
        if (otlpEndpoint != null) {
            out.println();
            com.lockstep.runner.OtlpExporter exporter =
                    com.lockstep.runner.OtlpExporter.open(otlpEndpoint, otlpService);
            String problem = exporter.export(result);
            if (problem == null) {
                out.println("results sent to " + exporter.endpoint() + " as "
                        + (com.lockstep.runner.OtlpExporter.metricsPerRunner()
                                * result.byRunner().size())
                        + " metrics");
            } else {
                err.println(Ansi.error("could not send results to " + exporter.endpoint()
                        + ": " + problem));
            }
        }

        out.println();
        out.println(CliTables.precisionNote());
        out.flush();
        return EXIT_SUCCESS;
    }

    /** Requests the application-side runner actually executed — the denominator for per-request
     * figures about the target. */
    private static long appRequestCount(RunCoordinator.RunResult result) {
        var loop = result.byRunner().get("http");
        if (loop == null) {
            loop = result.byRunner().get("scenario");
        }
        return loop == null ? 0 : loop.executedCount();
    }

    /**
     * Fetches the slowest requests' traces and breaks them down, when a backend was given.
     *
     * <p>Only a few are fetched. Each is an HTTP round trip with retries for ingest lag, and the
     * second-slowest request rarely tells a different story from the slowest.
     */
    private String traceBreakdown(RunCoordinator.RunResult result, PrintWriter out,
            PrintWriter err) {
        if (tracesUrl == null) {
            return "";
        }
        if (!trace) {
            return CliTables.traceBreakdownSection(null,
                    "--traces needs --trace: without it no trace IDs are sent, so there is "
                    + "nothing to fetch");
        }

        List<String> ids = new java.util.ArrayList<>();
        for (var entry : result.byRunner().values()) {
            for (var slow : entry.slowestRequests()) {
                if (slow.traceId() != null && ids.size() < 3 && !ids.contains(slow.traceId())) {
                    ids.add(slow.traceId());
                }
            }
        }
        if (ids.isEmpty()) {
            return CliTables.traceBreakdownSection(null, "no trace IDs were recorded to fetch");
        }

        err.println(com.lockstep.util.Ansi.dim(
                "fetching " + ids.size() + " trace(s) from " + tracesUrl + "..."));
        err.flush();

        List<com.lockstep.analysis.TraceBreakdown> breakdowns = new java.util.ArrayList<>();
        try (var fetcher = com.lockstep.runner.TraceFetcher.open(tracesUrl)) {
            for (String id : ids) {
                var fetched = fetcher.fetch(id);
                if (fetched != null && !fetched.isEmpty()) {
                    breakdowns.add(fetched);
                }
            }
        } catch (RuntimeException e) {
            return CliTables.traceBreakdownSection(null,
                    "could not read " + tracesUrl + ": " + e.getMessage());
        }
        if (breakdowns.isEmpty()) {
            return CliTables.traceBreakdownSection(null,
                    "no spans came back for those IDs. Either the target is not instrumented, its "
                    + "exporter is not pointed at this backend, or it supports "
                    + com.lockstep.runner.TraceFetcher.describeSupport());
        }
        return CliTables.traceBreakdownSection(breakdowns, null);
    }

    /** The caller's observed mean, for comparison against the server's own. */
    private static long appMeanNanos(RunCoordinator.RunResult result) {
        var loop = result.byRunner().get("http");
        if (loop == null) {
            loop = result.byRunner().get("scenario");
        }
        if (loop == null) {
            return -1;
        }
        return loop.series().summarize("app", result.context().durationNanos()).meanNanos();
    }

    private static double appDeliveryRatio(RunCoordinator.RunResult result) {
        var loop = result.byRunner().get("http");
        if (loop == null) {
            loop = result.byRunner().get("scenario");
        }
        if (loop == null || loop.scheduledCount() <= 0) {
            return 1.0;
        }
        return (double) loop.executedCount() / loop.scheduledCount();
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
        String model = arrivals == null ? config.arrivals() : arrivals;
        long seed = arrivalSeed != 0 ? arrivalSeed : config.arrivalSeed();
        com.lockstep.core.Arrivals.parse(model);
        return new Config(durationNanos, config.bucketWidth(), rampNanos, workers, model, seed,
                config.http(), config.db(), config.redis(), config.scenario());
    }
}
