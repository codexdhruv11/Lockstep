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
        rows.add(new String[] {"RUNNER", "REQUESTS", "SUCCESS", "RATE", "MEAN", "P50", "P95", "P99", "MAX", "STATUS"});

        result.byRunner().forEach((name, loop) -> {
            RunnerSummary summary = loop.series().summarize(name, result.context().durationNanos());
            rows.add(new String[] {
                name,
                Numbers.withSeparators(summary.count()),
                Numbers.percent(summary.successRate()),
                Numbers.rate(summary.achievedRatePerSecond()),
                Numbers.latency(summary.meanNanos()),
                Numbers.latency(summary.p50Nanos()),
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
            if (!loop.drainedCleanly()) {
                parts.add("queued work abandoned at the deadline");
            }
            notes.append(Ansi.accent("! " + name)).append("  ")
                    .append(Numbers.withSeparators(loop.executedCount())).append(" of ")
                    .append(Numbers.withSeparators(loop.scheduledCount())).append(" scheduled — ")
                    .append(String.join("; ", parts)).append('\n');
        });
        return notes.toString();
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
