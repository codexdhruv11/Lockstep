package com.lockstep.report;

import com.lockstep.core.PacedLoop;
import com.lockstep.core.RunCoordinator;
import com.lockstep.stats.Bucket;
import com.lockstep.stats.HistogramRecorder;
import com.lockstep.stats.RunnerSummary;
import com.lockstep.util.Ansi;
import com.lockstep.util.Numbers;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public final class CliTables {
    private CliTables() {}

    public static String summaryTable(RunCoordinator.RunResult result) {
        List<String[]> rows = new ArrayList<>();

        rows.add(new String[] {"RUNNER", "REQUESTS", "SUCCESS", "RATE", "MIN", "MEAN", "P50",
            "P90", "P95", "P99", "MAX", "STATUS"});

        result.byRunner().forEach((name, loop) -> {
            RunnerSummary summary = loop.series().summarize(name, result.context().durationNanos());
            rows.add(new String[] {
                name,
                Numbers.withSeparators(summary.count()),
                Numbers.percent(summary.successRate()),
                Numbers.rate(summary.achievedRatePerSecond()),
                Numbers.latency(summary.minNanos()),
                Numbers.latency(summary.meanNanos()),
                Numbers.latency(summary.p50Nanos()),
                Numbers.latency(summary.p90Nanos()),
                Numbers.latency(summary.p95Nanos()),
                Numbers.latency(summary.p99Nanos()),
                Numbers.latency(summary.maxNanos()),
                statusBreakdown(summary),
            });
        });
        return render(rows);
    }

    public static String shortfallNotes(RunCoordinator.RunResult result) {
        StringBuilder notes = new StringBuilder();
        result.byRunner().forEach((name, loop) -> {
            if (!loop.fellShort()) {
                return;
            }
            List<String> parts = new ArrayList<>();
            if (loop.shedCount() > 0) {
                parts.add(Numbers.withSeparators(loop.shedCount()) + " shed (every worker was busy)");
            }
            if (loop.lateFireCount() > 0) {
                parts.add(Numbers.withSeparators(loop.lateFireCount()) + " fired late, worst "
                        + Numbers.latency(loop.maxLatenessNanos()));
            }
            var summary = loop.series().summarize(name, result.context().durationNanos());
            if (summary.clampedEarlyCount() + summary.clampedLateCount() > 0) {
                parts.add(Numbers.withSeparators(
                        summary.clampedEarlyCount() + summary.clampedLateCount())
                        + " landed outside the run window and were clamped into it");
            }
            if (loop.abandonedCount() > 0) {
                parts.add(Numbers.withSeparators(loop.abandonedCount())
                        + " still running when the run gave up waiting");
            }
            if (!loop.drainedCleanly()) {
                parts.add("did not drain cleanly");
            }
            if (loop.scheduledCount() < loop.expectedHits()) {
                parts.add(Numbers.withSeparators(loop.expectedHits() - loop.scheduledCount())
                        + " never scheduled");
            }

            notes.append(Ansi.accent("! " + name)).append("  ")
                    .append(Numbers.withSeparators(loop.executedCount())).append(" of ")
                    .append(Numbers.withSeparators(loop.expectedHits())).append(" expected — ")
                    .append(String.join("; ", parts)).append('\n');
        });
        return notes.toString();
    }

    public static String spikeTable(com.lockstep.analysis.SpikeCorrelator.CorrelationResult correlation) {
        if (!correlation.appReferencePresent()) {
            return Ansi.dim("no application-side timeline — storage spikes cannot be correlated\n");
        }
        if (correlation.spikes().isEmpty()) {
            return "";
        }
        List<String[]> rows = new ArrayList<>();
        rows.add(new String[] {"TIME", "RUNNER", "APP_P99", "STORAGE_P99", "NOTE"});
        for (var spike : correlation.spikes()) {
            rows.add(new String[] {
                Numbers.clock(spike.startOffsetNanos()),
                spike.storageRunner(),
                Numbers.latency(spike.appP99Nanos()),
                Numbers.latency(spike.storageP99Nanos()),
                spike.masked()
                        ? spike.storageRunner() + "-only (app not affected yet)"
                        : "correlated → " + spike.verdict(),
            });
        }
        return "correlated spikes\n" + render(rows);
    }

    public static String capacityLine(com.lockstep.analysis.CapacityFinder.Capacity capacity) {
        return capacityLine(capacity, 0);
    }

    public static String capacityLine(com.lockstep.analysis.CapacityFinder.Capacity capacity,
            long warmupNanos) {
        if (!capacity.usable()) {
            String because = warmupNanos > 0
                    ? " — the first " + com.lockstep.util.Durations.formatNanos(warmupNanos)
                            + " is excluded as warm-up"
                    : "";
            return Ansi.dim("capacity: run too short to say (needs "
                    + com.lockstep.analysis.CapacityFinder.MINIMUM_BUCKETS
                    + "+ buckets with traffic" + because + ")\n");
        }
        String headline;
        if (capacity.strained()) {
            headline = "capacity: strain starts around ~%d users (at %s, p99 crossed %s against a %s baseline)"
                    .formatted(capacity.strainUsers(), Numbers.clock(capacity.strainOffsetNanos()),
                            Numbers.latency(capacity.strainLevelNanos()),
                            Numbers.latency(capacity.baselineP99Nanos()));
        } else if (capacity.overCapacityThroughout()) {
            headline = "capacity: already over capacity at ~%d users".formatted(capacity.usersAtEnd())
                    + "\n  load was shed for the whole run, so there was no healthy stretch to "
                    + "measure a strain point against";
        } else {
            headline = "capacity: no strain up to ~%d users (baseline p99 %s)"
                    .formatted(capacity.usersAtEnd(), Numbers.latency(capacity.baselineP99Nanos()));
        }
        String next = (capacity.overCapacityThroughout() ? "  re-test lower, at concurrency "
                : "  re-test at concurrency ") + capacity.suggestedNextConcurrency();
        String caveat = Ansi.dim(
                "  users are estimated from concurrency, not measured — one worker is not one user");
        return headline + "\n" + next + "\n" + caveat + "\n";
    }

    public static String bucketTable(String runnerName, PacedLoop.LoopResult loop) {
        List<String[]> rows = new ArrayList<>();
        rows.add(new String[] {"BUCKET", "COUNT", "ERR", "P50", "P95", "P99", "MAX", "QUEUE_P99"});
        for (Bucket bucket : loop.series().nonEmptyBuckets()) {
            rows.add(new String[] {
                Numbers.clock(bucket.startOffsetNanos()),
                Numbers.withSeparators(bucket.count()),
                Numbers.withSeparators(bucket.errorCount()),
                Numbers.latency(bucket.p50Nanos()),
                Numbers.latency(bucket.p95Nanos()),
                Numbers.latency(bucket.p99Nanos()),
                Numbers.latency(bucket.maxNanos()),
                Numbers.latency(bucket.queueDelayP99Nanos()),
            });
        }
        return runnerName + "\n" + render(rows);
    }

    public static String stepTable(java.util.Map<String, com.lockstep.stats.BucketSeries> steps,
            long runDurationNanos) {
        if (steps.isEmpty()) {
            return "";
        }
        List<String[]> rows = new ArrayList<>();
        rows.add(new String[] {"STEP", "CALLS", "ERR", "MEAN", "P50", "P95", "P99", "MAX"});
        steps.forEach((label, series) -> {
            var summary = series.summarize(label, runDurationNanos);
            rows.add(new String[] {
                label,
                Numbers.withSeparators(summary.count()),
                Numbers.withSeparators(summary.errorCount()),
                Numbers.latency(summary.meanNanos()),
                Numbers.latency(summary.p50Nanos()),
                Numbers.latency(summary.p95Nanos()),
                Numbers.latency(summary.p99Nanos()),
                Numbers.latency(summary.maxNanos()),
            });
        });
        return "steps\n" + render(rows);
    }

    public static String queryTable(String heading,
            Map<String, com.lockstep.stats.BucketSeries> queries, long runDurationNanos) {
        List<QueryBreakdown.Row> ranked = QueryBreakdown.rank(queries);
        if (ranked.isEmpty()) {
            return "";
        }
        List<String[]> rows = new ArrayList<>();
        rows.add(new String[] {heading.toUpperCase(), "CALLS", "SHARE", "ERR", "MEAN", "P50",
            "P95", "P99", "MAX"});
        for (QueryBreakdown.Row row : ranked) {
            rows.add(new String[] {
                row.label(),
                Numbers.withSeparators(row.count()),
                Numbers.percent(row.shareOfServiceTime()),
                Numbers.withSeparators(row.errorCount()),
                Numbers.latency(row.serviceMeanNanos()),
                Numbers.latency(row.serviceP50Nanos()),
                Numbers.latency(row.serviceP95Nanos()),
                Numbers.latency(row.serviceP99Nanos()),
                Numbers.latency(row.serviceMaxNanos()),
            });
        }
        return heading + "\n" + render(rows) + Ansi.dim(
                "  share is of this runner's total service time (calls x mean), not of calls\n"
                + "  latencies here are each one's own cost; the wait including queue delay is in "
                + "the runner table above\n");
    }

    private static final int MAX_PLAN_LINES = 30;

    public static String planSection(
            Map<String, com.lockstep.runner.db.DbRunner.QueryPlan> plans, long thresholdNanos,
            int skipped) {
        if (plans == null || plans.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder("query plans");
        out.append(Ansi.dim(" (p99 above " + Numbers.latency(thresholdNanos)
                + "; taken after the run, with the load off)")).append('\n');
        if (skipped > 0) {
            out.append(Ansi.dim("  " + skipped + " more crossed the threshold and were not "
                    + "explained — these own the most database time")).append('\n');
        }
        for (var plan : plans.values()) {
            out.append('\n').append(Ansi.bold(plan.label())).append('\n');
            if (plan.plan() == null) {
                out.append("  ").append(Ansi.dim("no plan: " + plan.failure())).append('\n');
                continue;
            }
            out.append("  ").append(Ansi.dim(plan.statement()
                    + (plan.executed() ? "" : " — estimates, not measurements"))).append('\n');
            String[] lines = plan.plan().split("\n");
            for (int i = 0; i < Math.min(lines.length, MAX_PLAN_LINES); i++) {
                out.append("  ").append(lines[i]).append('\n');
            }
            if (lines.length > MAX_PLAN_LINES) {
                out.append("  ").append(Ansi.dim("... " + (lines.length - MAX_PLAN_LINES)
                        + " more lines — the full plan is in the report")).append('\n');
            }
        }
        return out.toString();
    }

    public static String slowlogSection(com.lockstep.report.RunReport.SlowlogReport slowlog) {
        if (slowlog == null) {
            return "";
        }
        if (slowlog.entries().isEmpty()) {
            return slowlog.note() == null ? ""
                    : "redis slowlog\n" + Ansi.dim("  " + slowlog.note()) + "\n";
        }
        List<String[]> rows = new ArrayList<>();
        rows.add(new String[] {"SERVER_TIME", "COMMAND", "CLIENT"});
        for (var entry : slowlog.entries()) {
            rows.add(new String[] {
                Numbers.latency(entry.durationMicros() * 1_000L),
                truncate(entry.command()),
                entry.client() == null ? "" : entry.client(),
            });
        }
        return "redis slowlog\n" + render(rows) + Ansi.dim(
                "  the server's own timing, from inside the server — a command this run measured "
                + "as slow\n  but that is not here waited somewhere other than Redis\n"
                + "  the log is server-wide: another client's slow commands appear here too\n");
    }

    private static String truncate(String command) {
        if (command == null) {
            return "";
        }
        String oneLine = command.replaceAll("\\s+", " ").trim();
        return oneLine.length() <= 70 ? oneLine : oneLine.substring(0, 69) + "\u2026";
    }

    public static String describeRates(Map<String, Integer> baseRates, double multiplier) {
        if (baseRates.isEmpty()) {
            return "-";
        }
        if (baseRates.size() == 1) {
            var only = baseRates.entrySet().iterator().next();
            return scaled(only.getValue(), multiplier) + "/s";
        }
        return baseRates.entrySet().stream()
                .map(entry -> entry.getKey() + " " + scaled(entry.getValue(), multiplier) + "/s")
                .collect(Collectors.joining(" + "));
    }

    private static int scaled(int rate, double multiplier) {
        return Math.max(1, (int) Math.round(rate * multiplier));
    }

    public static String capacitySearchTable(
            com.lockstep.analysis.CapacitySearch.Result result, Map<String, Integer> baseRates) {
        if (result.steps().isEmpty()) {
            return "";
        }
        List<String[]> rows = new ArrayList<>();
        rows.add(new String[] {"RATE", "DELIVERED", "P99", "SLOWEST", "VERDICT"});
        for (var step : result.steps()) {
            var measurement = step.measurement();
            rows.add(new String[] {
                describeRates(baseRates, step.multiplier()),

                Numbers.rate(measurement.achievedRatePerSecond())
                        + " (" + Numbers.percent(measurement.deliveryRatio()) + ")",
                Numbers.latency(measurement.p99Nanos()),
                measurement.runnerName(),
                switch (step.strain()) {
                    case NONE -> "held";
                    case SHED -> "strained — shed load";
                    case LATENCY -> "strained — p99 blew up";
                },
            });
        }
        return "capacity search\n" + render(rows);
    }

    public static String capacitySearchVerdict(
            com.lockstep.analysis.CapacitySearch.Result result, Map<String, Integer> baseRates,
            int concurrency) {
        StringBuilder out = new StringBuilder();
        if (result.bracketed()) {
            var held = result.sustained();
            var gave = result.strained();
            out.append("capacity: sustains %s, strains at %s\n".formatted(
                    describeRates(baseRates, held.multiplier()),
                    describeRates(baseRates, gave.multiplier())));
            out.append("  at %s: %s delivered, p99 %s\n".formatted(
                    describeRates(baseRates, held.multiplier()),
                    Numbers.rate(held.measurement().achievedRatePerSecond()),
                    Numbers.latency(held.measurement().p99Nanos())));
            out.append("  at %s: %s\n".formatted(
                    describeRates(baseRates, gave.multiplier()),
                    switch (gave.strain()) {
                        case SHED -> "%s could not be given the load — %s of %s delivered"
                                .formatted(gave.measurement().runnerName(),
                                        Numbers.withSeparators(gave.measurement().executedCount()),
                                        Numbers.withSeparators(gave.measurement().scheduledCount()));
                        case LATENCY -> "%s held the rate but its p99 went to %s, from %s"
                                .formatted(gave.measurement().runnerName(),
                                        Numbers.latency(gave.measurement().p99Nanos()),
                                        Numbers.latency(held.measurement().p99Nanos()));
                        case NONE -> "strained";
                    }));
        } else if (result.sustained() != null) {
            out.append("capacity: not found — %s held, the highest rate tried\n".formatted(
                    describeRates(baseRates, result.sustained().multiplier())));
            out.append("  raise the config's rate, or allow more steps\n");
        } else {
            out.append("capacity: not found — the lowest rate tried, %s, already strained\n"
                    .formatted(describeRates(baseRates, lowestMultiplier(result))));
            out.append("  lower the config's rate, or allow more steps\n");
        }

        out.append(Ansi.dim(
                "  measured with " + concurrency + " workers on this machine, which also generated "
                + "the load\n  the shape transfers; the absolute number does not"));
        return out.append('\n').toString();
    }

    private static double lowestMultiplier(com.lockstep.analysis.CapacitySearch.Result result) {
        return result.steps().stream()
                .mapToDouble(com.lockstep.analysis.CapacitySearch.Observation::multiplier)
                .min().orElse(1.0);
    }

    public static String errorTable(RunCoordinator.RunResult result) {
        List<String[]> rows = new ArrayList<>();
        rows.add(new String[] {"RUNNER", "COUNT", "FAILURE"});
        result.byRunner().forEach((name, loop) ->
                loop.errorCounts().forEach((message, count) -> rows.add(new String[] {
                    name, Numbers.withSeparators(count), message,
                })));
        if (rows.size() <= 1) {
            return "";
        }
        String note = "";
        if (result.byRunner().values().stream()
                .anyMatch(loop -> loop.errorCounts().containsKey(
                        com.lockstep.stats.ErrorCounts.OVERFLOW_KEY))) {
            note = Ansi.dim("  the distinct-message list is capped; the rest are pooled in the "
                    + "last row\n");
        }
        return "failures\n" + render(rows) + note;
    }

    private static final long[] DISTRIBUTION_EDGES_NANOS = {
        1_000_000L, 5_000_000L, 10_000_000L, 25_000_000L, 50_000_000L, 100_000_000L,
        250_000_000L, 500_000_000L, 1_000_000_000L, 2_500_000_000L, 5_000_000_000L, 10_000_000_000L,
    };

    private static final int DISTRIBUTION_BAR_WIDTH = 40;

    public static String distributionTable(String runnerName, PacedLoop.LoopResult loop) {
        var merged = loop.series().mergedLatency();
        long total = merged.getTotalCount();
        if (total == 0) {
            return "";
        }
        long[] counts = new long[DISTRIBUTION_EDGES_NANOS.length + 1];
        long previousEdge = 0;
        for (int i = 0; i < DISTRIBUTION_EDGES_NANOS.length; i++) {
            counts[i] = merged.getCountBetweenValues(i == 0 ? 0 : previousEdge + 1,
                    DISTRIBUTION_EDGES_NANOS[i]);
            previousEdge = DISTRIBUTION_EDGES_NANOS[i];
        }
        counts[counts.length - 1] = merged.getCountBetweenValues(previousEdge + 1, Long.MAX_VALUE);

        long busiest = 0;
        for (long count : counts) {
            busiest = Math.max(busiest, count);
        }

        List<String[]> rows = new ArrayList<>();
        rows.add(new String[] {"LATENCY", "COUNT", "SHARE", ""});
        for (int i = 0; i < counts.length; i++) {
            String range = i == counts.length - 1
                    ? "> " + Numbers.latency(DISTRIBUTION_EDGES_NANOS[DISTRIBUTION_EDGES_NANOS.length - 1])
                    : "<= " + Numbers.latency(DISTRIBUTION_EDGES_NANOS[i]);
            rows.add(new String[] {
                range,
                Numbers.withSeparators(counts[i]),
                Numbers.percent((double) counts[i] / total),
                bar(counts[i], busiest),
            });
        }
        return runnerName + " latency distribution\n" + render(rows);
    }

    private static String bar(long count, long busiest) {
        if (busiest <= 0 || count <= 0) {
            return "";
        }
        int width = (int) Math.max(1, Math.round((double) count / busiest * DISTRIBUTION_BAR_WIDTH));
        return "\u2588".repeat(width);
    }

    public static String selfAuditTable(com.lockstep.analysis.SelfAudit.Report audit,
            com.lockstep.analysis.SpikeCorrelator.CorrelationResult correlation,
            long bucketWidthNanos) {
        if (audit == null) {
            return "";
        }
        if (!audit.available()) {
            return "generator self-audit\n"
                    + Ansi.dim("  unavailable: " + audit.unavailableReason()) + "\n";
        }
        if (!audit.hasPauses()) {
            return "generator self-audit\n"
                    + Ansi.dim("  no JVM pause above "
                        + Numbers.latency(com.lockstep.analysis.SelfAudit.SIGNIFICANT_PAUSE_NANOS)
                        + " in this run; the latencies above are the target's, not this process's")
                    + "\n";
        }

        java.util.Set<Integer> spikeBuckets = new java.util.HashSet<>();
        if (correlation != null) {
            for (var spike : correlation.spikes()) {
                spikeBuckets.add(spike.bucketIndex());
            }
        }

        List<String[]> rows = new ArrayList<>();
        rows.add(new String[] {"TIME", "PAUSED", "EVENT", "NOTE"});
        int contaminated = 0;
        for (var pause : audit.longestPauses()) {
            boolean overlapsSpike = spikeBuckets.contains(pause.bucketIndex());
            if (overlapsSpike) {
                contaminated++;
            }
            rows.add(new String[] {
                Numbers.clock(pause.bucketIndex() * bucketWidthNanos),
                Numbers.latency(pause.durationNanos()),
                shortEventName(pause.eventName()),
                overlapsSpike ? "overlaps a reported spike" : "",
            });
        }

        StringBuilder out = new StringBuilder("generator self-audit\n");
        out.append(render(rows));
        out.append(Ansi.dim("  this process was paused for "
                + Numbers.latency(audit.totalPausedNanos())
                + " in total, across " + audit.gcPauseCount() + " GC pauses\n"));
        if (contaminated > 0) {
            out.append(Ansi.dim("  " + contaminated + " of these fall in a bucket reported as a "
                    + "storage spike above - that latency was at least partly this process, "
                    + "not the target\n"));
        }
        return out.toString();
    }

    private static String shortEventName(String jfrName) {
        int dot = jfrName.lastIndexOf('.');
        return dot < 0 ? jfrName : jfrName.substring(dot + 1);
    }

    public static String warmupNote(long warmupNanos) {
        return Ansi.dim("first " + com.lockstep.util.Durations.formatNanos(warmupNanos)
                + " treated as warm-up: still measured and printed above, excluded from spikes "
                + "and the capacity baseline");
    }

    public static String runHeader(RunCoordinator.RunResult result) {
        var context = result.context();
        return "duration %s · bucket %s · concurrency %d · ramp %s · arrivals %s".formatted(
                com.lockstep.util.Durations.formatNanos(context.durationNanos()),
                com.lockstep.util.Durations.formatNanos(context.bucketWidthNanos()),
                context.concurrency(),
                com.lockstep.util.Durations.formatNanos(context.rampNanos()),
                context.arrivals().label());
    }

    public static String precisionNote() {
        return Ansi.dim("percentiles accurate to ~%s (histogram precision)"
                .formatted(Numbers.percent(HistogramRecorder.PERCENTILE_PRECISION)));
    }

    public static String bottleneckTable(com.lockstep.analysis.Bottleneck bottleneck) {
        if (bottleneck == null) {
            return "";
        }
        if (!bottleneck.available()) {
            return "bottleneck\n" + Ansi.dim("  unavailable — "
                    + bottleneck.unavailableReason() + "\n");
        }

        StringBuilder out = new StringBuilder("bottleneck\n");
        out.append(("  %s samples · busy backends mean %.1f, peak %d")
                .formatted(Numbers.withSeparators(bottleneck.samples()),
                        bottleneck.meanActiveBackends(), bottleneck.maxActiveBackends()));
        if (bottleneck.serverMaxConnections() > 0) {
            out.append(" · server max_connections ")
                    .append(Numbers.withSeparators(bottleneck.serverMaxConnections()));
        }
        out.append('\n');

        if (bottleneck.totalWaitObservations() > 0) {
            List<String[]> rows = new ArrayList<>();
            rows.add(new String[] {"WAITING ON", "SHARE", "SAMPLES"});
            int total = bottleneck.totalWaitObservations();
            for (var event : bottleneck.topWaitEvents(6)) {
                rows.add(new String[] {
                    event.getKey(),
                    Numbers.percent((double) event.getValue() / total),
                    Numbers.withSeparators(event.getValue()),
                });
            }
            out.append(render(rows));
        }

        out.append(verdictLine(bottleneck));
        out.append(Ansi.dim("  sampled every "
                + com.lockstep.util.Durations.formatNanos(
                        com.lockstep.analysis.Bottleneck.SAMPLE_INTERVAL_MILLIS * 1_000_000L)
                + ", so this is a statistical picture rather than a census, and it covers every "
                + "client of this database rather than only this run\n"));
        return out.toString();
    }

    private static String verdictLine(com.lockstep.analysis.Bottleneck bottleneck) {
        var verdict = bottleneck.verdict();
        String plateau = ("  busy backends sat at %d in %s of samples")
                .formatted(bottleneck.plateauBackends(),
                        Numbers.percent(bottleneck.plateauShare()));
        return switch (verdict) {
            case CONNECTIONS -> Ansi.accent("! bounded by connections")
                    + ("  — %s, which is almost certainly the client's pool size: work was "
                    + "queued waiting for a connection rather than for the database\n")
                    .formatted(plateau.trim());
            case STORAGE_IO -> Ansi.accent("! bounded by storage")
                    + ("  — backends were waiting on disk in %s of observations; the working set "
                    + "no longer fits in cache\n")
                    .formatted(Numbers.percent(bottleneck.shareOfWaits("IO")));
            case LOCK_CONTENTION -> Ansi.accent("! bounded by lock contention")
                    + ("  — backends were blocked on each other in %s of observations; they are "
                    + "competing for the same rows\n")
                    .formatted(Numbers.percent(bottleneck.shareOfWaits("Lock")));
            case CPU -> Ansi.accent("! bounded by the database's CPU")
                    + ("  — backends were running rather than waiting in %s of observations; the "
                    + "queries themselves are the cost\n")
                    .formatted(Numbers.percent(
                            bottleneck.shareOfWaits(com.lockstep.analysis.Bottleneck.RUNNING)));
            case NOT_SATURATED ->
                    "  nothing was near a limit — no plateau held and no single wait dominated\n";
            case UNKNOWN -> Ansi.dim(
                    "  too few samples to attribute a bottleneck (needs at least 3)\n");
        };
    }

    public static String targetQueriesTable(com.lockstep.analysis.TargetQueries queries) {
        if (queries == null) {
            return "";
        }
        if (!queries.available()) {
            return "target database\n" + Ansi.dim("  unavailable — "
                    + queries.unavailableReason() + "\n");
        }
        if (!queries.hasObservations()) {
            return "target database\n" + Ansi.dim(
                    "  the target ran no queries against a table the statistics views track\n");
        }

        List<String[]> rows = new ArrayList<>();
        rows.add(new String[] {"RELATION", "SEQ_SCANS", "IDX_SCANS", "SCANS/REQ", "ROWS_READ"});
        for (var relation : queries.byScansDescending()) {
            rows.add(new String[] {
                relation.name(),
                Numbers.withSeparators(relation.sequentialScans()),
                Numbers.withSeparators(relation.indexScans()),
                "%.1f".formatted(queries.scansPerRequest(relation)),
                Numbers.withSeparators(relation.rowsRead()),
            });
        }

        StringBuilder out = new StringBuilder("target database\n").append(render(rows));
        out.append(("  %s requests read %s rows each\n").formatted(
                Numbers.withSeparators(queries.requests()),
                Numbers.withSeparators((long) queries.rowsPerRequest())));

        if (queries.canJudgeStatementCount()) {
            out.append(("  %.1f statements per request\n")
                    .formatted(queries.statementsPerRequest()));
            if (queries.manyStatementsPerRequest()) {
                out.append(Ansi.accent("! the target sent %.1f statements for every request"
                                .formatted(queries.statementsPerRequest())))
                        .append(" — often a loop that should have been one query, though a "
                                + "genuinely multi-step endpoint looks the same\n");
            }
        } else {
            out.append(Ansi.dim("  statements per request unavailable: pg_stat_statements is not "
                    + "installed\n"));
            out.append(Ansi.dim("  without it an N+1 cannot be identified — the scan counts above "
                    + "are plan-node executions, and a nested-loop join scans its inner table "
                    + "once per outer row exactly as an application-side loop does\n"));
        }

        out.append(Ansi.dim("  per-database and a lower bound: the target's background jobs and "
                + "other clients are included, and its idle pooled connections may not have "
                + "reported their final second\n"));
        return out.toString();
    }

    public static String writeAmplificationTable(
            com.lockstep.analysis.WriteAmplification amplification) {
        if (amplification == null) {
            return "";
        }
        if (!amplification.available()) {
            return "write amplification\n" + Ansi.dim("  unavailable — "
                    + amplification.unavailableReason() + "\n");
        }
        if (!amplification.hasWrites()) {
            // A read-only run has nothing to amplify. Printing a section of zeroes would suggest
            // the writes were free rather than absent.
            return "";
        }

        List<String[]> rows = new ArrayList<>();
        rows.add(new String[] {"RELATION", "INSERTS", "UPDATES", "DELETES", "HOT_UPD",
            "INDEXES", "BYTES/ROW"});
        for (var relation : amplification.byWritesDescending()) {
            String indexes = relation.indexCount()
                    + (relation.partialIndexCount() > 0
                            ? " (" + relation.partialIndexCount() + " partial)" : "");
            rows.add(new String[] {
                relation.name(),
                Numbers.withSeparators(relation.inserts()),
                Numbers.withSeparators(relation.updates()),
                Numbers.withSeparators(relation.deletes()),
                relation.hotUpdateFraction() < 0
                        ? "-" : Numbers.percent(relation.hotUpdateFraction()),
                indexes,
                relation.effectiveBytesPerRow() <= 0
                        ? "?" : Numbers.bytes(relation.effectiveBytesPerRow()),
            });
        }

        StringBuilder out = new StringBuilder("write amplification\n").append(render(rows));
        long writes = amplification.totalLogicalWrites();
        out.append("  %s logical writes wrote %s to the log — %s each, %.1f records, %.2f fsyncs\n"
                .formatted(Numbers.withSeparators(writes), Numbers.bytes(amplification.walBytes()),
                        Numbers.bytes(amplification.walBytesPerWrite()),
                        amplification.walRecordsPerWrite(), amplification.syncsPerWrite()));

        double factor = amplification.amplificationFactor();
        if (factor > 0) {
            out.append(Ansi.accent("  the log carries %.1f× the row's own size".formatted(factor)))
                    .append(" — that multiple is the write path's real cost\n");
        }

        for (var relation : amplification.byWritesDescending()) {
            double tax = relation.indexTaxRatio();
            if (tax > 0) {
                out.append(("  %s grew %s of table and %s of indexes — %s and %s per row, so the "
                        + "indexes cost %.1f× the data they point at\n").formatted(
                                relation.name(),
                                Numbers.bytes(relation.tableBytesGrown()),
                                Numbers.bytes(relation.indexBytesGrown()),
                                Numbers.bytes(relation.onDiskBytesPerInsert()),
                                Numbers.bytes(relation.indexBytesPerInsert()),
                                tax));
            }
        }

        for (var relation : amplification.byWritesDescending()) {
            if (relation.heavilyIndexed() && relation.inserts() > 0) {
                out.append(("  %s carries %d indexes, so each of its %s inserts writes up to %s "
                        + "index entries%s\n").formatted(
                                relation.name(), relation.indexCount(),
                                Numbers.withSeparators(relation.inserts()),
                                Numbers.withSeparators(
                                        relation.inserts() * (long) relation.indexCount()),
                                relation.partialIndexCount() > 0
                                        ? " (fewer where a partial index's predicate does not match)"
                                        : ""));
            }
            if (relation.updates() > 0 && relation.hotUpdateFraction() >= 0
                    && relation.hotUpdateFraction() < 0.5 && relation.heavilyIndexed()) {
                out.append(Ansi.accent("  only %s of %s updates were HOT"
                        .formatted(Numbers.percent(relation.hotUpdateFraction()),
                                relation.name())))
                        .append(" — the rest rewrote every index entry for the row\n");
            }
        }

        if (amplification.fullPageImagesDominate()) {
            out.append(("  %.2f full page images per write: the run straddled a checkpoint, so "
                    + "these bytes are one-off rather than steady state\n")
                    .formatted(amplification.fullPageImagesPerWrite()));
        }
        if (amplification.walBuffersFull() > 0) {
            out.append(("  the WAL buffers filled %s times, forcing writes mid-transaction — "
                    + "wal_buffers is too small for this write rate\n")
                    .formatted(Numbers.withSeparators(amplification.walBuffersFull())));
        }
        if (!amplification.walTimingTracked()) {
            out.append(Ansi.dim("  fsync timing not measured: track_wal_io_timing is off, so the "
                    + "count above is real but the time spent is not\n"));
        } else if (amplification.walSyncTimeMicros() > 0) {
            out.append("  %s spent in fsync\n".formatted(
                    Numbers.latency(amplification.walSyncTimeMicros() * 1_000)));
        }
        out.append(Ansi.dim("  pg_stat_wal is cluster-wide: every database on this server "
                + "contributes, so these bytes are an upper bound on what this run wrote\n"));
        return out.toString();
    }

    public static String growthCurveTable(com.lockstep.analysis.GrowthCurve curve) {
        if (curve == null || curve.points().isEmpty()) {
            return "";
        }
        List<String[]> rows = new ArrayList<>();
        rows.add(new String[] {"ROWS", "SERVICE_P99", "LATENCY_P99", "BYTES/REQ", "HIT%",
            "DELIVERED"});
        for (var point : curve.points()) {
            rows.add(new String[] {
                Numbers.withSeparators(point.rows()),
                Numbers.latency(point.serviceP99Nanos()),
                Numbers.latency(point.latencyP99Nanos()),
                point.bytesPerRequest() <= 0 ? "-" : Numbers.bytes(point.bytesPerRequest()),
                point.hitRatio() < 0 ? "-" : Numbers.percent(point.hitRatio()),
                Numbers.rate(point.deliveredRatePerSecond()),
            });
        }
        return "growth curve\n" + render(rows);
    }

    public static String growthCurveVerdict(com.lockstep.analysis.GrowthCurve curve,
            long budgetNanos, Long rowsPerDay, String table) {
        if (curve == null || curve.points().size() < 2) {
            return Ansi.dim("growth: not enough measurements to describe a curve\n");
        }

        StringBuilder out = new StringBuilder();
        out.append("service time grows as rows^%.2f (R%s %.3f) — %s\n".formatted(
                curve.exponent(), "²", curve.rSquared(), describe(curve.shape())));

        long cliff = curve.cacheCliffAtRows();
        if (cliff > 0) {
            out.append(Ansi.accent("! the buffer cache stopped holding the working set at about "
                    + Numbers.withSeparators(cliff) + " rows\n"));
            out.append("    latency steps there rather than curving, so one exponent does not "
                    + "describe both sides of it — this is the knee, and it is the number to "
                    + "plan against\n");
        }

        if (curve.bytesPerRequestGrew()) {
            out.append("  bytes read per request grew with the table: the work is proportional to "
                    + "the data, not bounded by an index\n");
        } else if (curve.shape() == com.lockstep.analysis.GrowthCurve.Shape.FLAT) {
            out.append("  bytes read per request did not grow — an index is bounding the work\n");
        }

        if (budgetNanos > 0) {
            out.append(budgetLine(curve, budgetNanos, rowsPerDay, table));
        }

        if (!curve.extrapolatable()) {
            out.append(Ansi.dim("  not extrapolating: "
                    + whyNotExtrapolatable(curve) + "\n"));
        }
        out.append(Ansi.dim("  measured on this machine, with this data shape; the exponent "
                + "transfers, the absolute latencies do not\n"));
        return out.toString();
    }

    private static String budgetLine(com.lockstep.analysis.GrowthCurve curve, long budgetNanos,
            Long rowsPerDay, String table) {
        if (curve.budgetAlreadyExceeded(budgetNanos)) {
            return Ansi.accent("! the service-time budget of " + Numbers.latency(budgetNanos)
                    + " is already exceeded at the smallest measurement\n");
        }
        if (curve.budgetBeyondExtrapolationRange(budgetNanos)) {
            return ("  budget %s: not reached within %.0f× the largest measurement (%s rows), so "
                    + "no crossing is reported — the curve is too flat to place one\n").formatted(
                            Numbers.latency(budgetNanos),
                            com.lockstep.analysis.GrowthCurve.MAX_EXTRAPOLATION_FACTOR,
                            Numbers.withSeparators(curve.largestMeasuredRows()));
        }
        long atBudget = curve.rowsAtBudget(budgetNanos);
        if (atBudget <= 0) {
            return "  budget " + Numbers.latency(budgetNanos)
                    + ": cannot say where it breaks from these measurements\n";
        }
        StringBuilder line = new StringBuilder("  budget %s: %s reaches it at about %s rows"
                .formatted(Numbers.latency(budgetNanos), table,
                        Numbers.withSeparators(atBudget)));
        if (rowsPerDay != null && rowsPerDay > 0) {
            long current = curve.points().get(curve.points().size() - 1).rows();
            long headroom = atBudget - current;
            if (headroom <= 0) {
                line.append(", which is at or below the current row count");
            } else {
                long days = headroom / rowsPerDay;
                line.append(", which at %s rows/day is %s days away"
                        .formatted(Numbers.withSeparators(rowsPerDay),
                                Numbers.withSeparators(days)));
            }
        }
        return line.append('\n').toString();
    }

    private static String whyNotExtrapolatable(com.lockstep.analysis.GrowthCurve curve) {
        if (curve.spansCacheCliff()) {
            return "the measurements cross a cache cliff, and a power law through a step is "
                    + "a number that looks authoritative and is not";
        }
        if (curve.points().size() < com.lockstep.analysis.GrowthCurve.MIN_POINTS) {
            return "fewer than " + com.lockstep.analysis.GrowthCurve.MIN_POINTS
                    + " usable measurements";
        }
        if (curve.rSquared() < com.lockstep.analysis.GrowthCurve.MIN_R_SQUARED) {
            return "the points do not fit a power law well (R² %.3f, needs %.2f)"
                    .formatted(curve.rSquared(),
                            com.lockstep.analysis.GrowthCurve.MIN_R_SQUARED);
        }
        return "latency does not grow with the data, so there is no crossing to find";
    }

    private static String describe(com.lockstep.analysis.GrowthCurve.Shape shape) {
        return switch (shape) {
            case FLAT -> "flat: the row count barely matters";
            case SUBLINEAR -> "sublinear: grows more slowly than the table";
            case LINEAR -> "linear: cost is proportional to the row count, the shape of a scan";
            case SUPERLINEAR -> "superlinear: getting worse faster than the table grows";
            case UNKNOWN -> "not enough signal to name a shape";
        };
    }

    public static String resourceTable(com.lockstep.analysis.ResourceAccounting accounting) {
        if (accounting == null) {
            return "";
        }
        if (!accounting.available()) {
            return "resource accounting\n" + Ansi.dim("  unavailable — "
                    + accounting.unavailableReason() + "\n");
        }
        if (!accounting.hasRelations()) {
            return "resource accounting\n" + Ansi.dim(
                    "  no relation's block counters moved during the run — nothing was read "
                    + "through a table the statistics views track\n");
        }

        List<String[]> rows = new ArrayList<>();
        rows.add(new String[] {"RELATION", "ROWS", "TABLE", "INDEXES", "BYTES/ROW",
            "BLOCKS/REQ", "BYTES/REQ", "HIT%"});
        for (var relation : accounting.byBytesTouchedDescending()) {
            double hit = relation.hitRatio();
            rows.add(new String[] {
                relation.name(),
                relation.liveRows() <= 0 ? "?" : Numbers.withSeparators(relation.liveRows()),
                Numbers.bytes(relation.tableBytes()),
                Numbers.bytes(relation.indexBytes()),
                relation.bytesPerRow() <= 0 ? "?" : Numbers.bytes(relation.bytesPerRow()),
                Numbers.withSeparators(accounting.blocksPerRequest(relation)),
                Numbers.bytes(accounting.bytesPerRequest(relation)),
                hit < 0 ? "-" : Numbers.percent(hit),
            });
        }

        StringBuilder out = new StringBuilder("resource accounting\n").append(render(rows));
        for (var relation : accounting.byBytesTouchedDescending()) {
            if (accounting.readsWholeTablePerRequest(relation)) {
                out.append(Ansi.accent("! " + relation.name()))
                        .append(" reads ")
                        .append(Numbers.bytes(accounting.bytesPerRequest(relation)))
                        .append(" per request against a ")
                        .append(Numbers.bytes(relation.tableBytes()))
                        .append(" table — every request walks the whole thing\n")
                        .append("    at ")
                        .append(Numbers.rate(accounting.achievedRatePerSecond()))
                        .append(" that is ")
                        .append(Numbers.bytes((long) accounting.bufferBytesPerSecond(relation)))
                        .append("/s of buffer traffic\n");
            }
        }

        if (accounting.sharedBuffersBytes() > 0) {
            var widest = accounting.byBytesTouchedDescending().get(0);
            if (accounting.exceedsSharedBuffers(widest)) {
                out.append("  shared_buffers ").append(Numbers.bytes(accounting.sharedBuffersBytes()))
                        .append(" — ").append(widest.name()).append(" (")
                        .append(Numbers.bytes(widest.tableBytes()))
                        .append(") already exceeds it\n");
            } else {
                long limit = accounting.rowsAtSharedBuffersLimit(widest);
                out.append("  shared_buffers ").append(Numbers.bytes(accounting.sharedBuffersBytes()));
                if (limit > 0) {
                    out.append(" — ").append(widest.name()).append(" outgrows it at about ")
                            .append(Numbers.withSeparators(limit)).append(" rows");
                }
                out.append('\n');
            }
        }

        if (accounting.generatorBytesPerRequest() > 0) {
            out.append(Ansi.dim("  this generator allocated "
                    + Numbers.bytes(accounting.generatorBytesPerRequest())
                    + " per request ("
                    + Numbers.bytes((long) accounting.generatorAllocationBytesPerSecond())
                    + "/s) — its own cost, not the target's\n"));
        }
        out.append(Ansi.dim(
                "  block counters are per-database: anything else querying it during the run is "
                + "counted here too. Row counts are the planner's estimate.\n"));
        return out.toString();
    }

    private static String statusBreakdown(RunnerSummary summary) {
        Map<Integer, Long> statuses = summary.statusCounts();
        if (statuses.isEmpty()) {
            return "-";
        }
        return statuses.entrySet().stream()
                .map(entry -> entry.getKey() + "×" + Numbers.withSeparators(entry.getValue()))
                .collect(Collectors.joining(" "));
    }

    private static String render(List<String[]> rows) {
        if (rows.size() <= 1) {
            return "";
        }
        int columns = rows.get(0).length;
        int[] widths = new int[columns];
        for (String[] row : rows) {
            for (int i = 0; i < columns; i++) {
                widths[i] = Math.max(widths[i], row[i].length());
            }
        }
        StringBuilder out = new StringBuilder();
        for (int r = 0; r < rows.size(); r++) {
            String[] row = rows.get(r);
            StringBuilder line = new StringBuilder();
            for (int i = 0; i < columns; i++) {
                line.append(row[i]);
                if (i < columns - 1) {
                    line.append(" ".repeat(widths[i] - row[i].length() + 2));
                }
            }
            String text = line.toString().stripTrailing();
            out.append(r == 0 ? Ansi.bold(text) : text).append('\n');
        }
        return out.toString();
    }
}
