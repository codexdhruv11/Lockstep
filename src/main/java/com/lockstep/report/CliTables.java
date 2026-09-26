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
        String headline = capacity.strained()
                ? "capacity: strain starts around ~%d users (at %s, p99 crossed %s against a %s baseline)"
                        .formatted(capacity.strainUsers(), Numbers.clock(capacity.strainOffsetNanos()),
                                Numbers.latency(capacity.strainLevelNanos()),
                                Numbers.latency(capacity.baselineP99Nanos()))
                : "capacity: no strain up to ~%d users (baseline p99 %s)"
                        .formatted(capacity.usersAtEnd(), Numbers.latency(capacity.baselineP99Nanos()));
        String next = "  re-test at concurrency " + capacity.suggestedNextConcurrency();
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

    public static String warmupNote(long warmupNanos) {
        return Ansi.dim("first " + com.lockstep.util.Durations.formatNanos(warmupNanos)
                + " treated as warm-up: still measured and printed above, excluded from spikes "
                + "and the capacity baseline");
    }

    public static String runHeader(RunCoordinator.RunResult result) {
        var context = result.context();
        return "duration %s · bucket %s · concurrency %d · ramp %s".formatted(
                com.lockstep.util.Durations.formatNanos(context.durationNanos()),
                com.lockstep.util.Durations.formatNanos(context.bucketWidthNanos()),
                context.concurrency(),
                com.lockstep.util.Durations.formatNanos(context.rampNanos()));
    }

    public static String precisionNote() {
        return Ansi.dim("percentiles accurate to ~%s (histogram precision)"
                .formatted(Numbers.percent(HistogramRecorder.PERCENTILE_PRECISION)));
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
