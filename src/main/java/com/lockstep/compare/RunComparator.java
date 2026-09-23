package com.lockstep.compare;

import com.lockstep.report.RunReport;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class RunComparator {
    private RunComparator() {}

    public enum Verdict {
        REGRESSION,

        IMPROVED,

        OK,

        NEW,

        REMOVED
    }

    public record RunnerDiff(
            String name,
            long baselineP99Nanos,
            long currentP99Nanos,
            double changeFraction,
            Verdict verdict,
            long baselineSpikes,
            long currentSpikes,
            long baselineCorrelated,
            long currentCorrelated) {
        public boolean findingsWorsened() {
            return currentCorrelated > baselineCorrelated;
        }
    }

    public record SpikePair(
            String runner,
            int ordinal,
            Long baselineStorageP99Nanos,
            Long currentStorageP99Nanos,
            Boolean baselineMasked,
            Boolean currentMasked) {
        public boolean becameCorrelated() {
            return Boolean.TRUE.equals(baselineMasked) && Boolean.FALSE.equals(currentMasked);
        }
    }

    public record Comparison(List<RunnerDiff> runners, long budgetNanos, List<String> warnings,
            List<SpikePair> spikePairs) {
        public Comparison {
            runners = List.copyOf(runners);
            warnings = List.copyOf(warnings);
            spikePairs = spikePairs == null ? List.of() : List.copyOf(spikePairs);
        }

        public List<RunnerDiff> regressions() {
            return runners.stream().filter(diff -> diff.verdict() == Verdict.REGRESSION).toList();
        }

        public boolean failed() {
            return !regressions().isEmpty();
        }
    }

    public static Comparison compare(RunReport baseline, RunReport current, long budgetNanos) {
        requireComparablePrecision(baseline, current);

        List<String> warnings = new ArrayList<>();
        if (baseline.durationNanos() != current.durationNanos()) {
            warnings.add("durations differ (%s vs %s) — a longer run sees more of the tail"
                    .formatted(seconds(baseline.durationNanos()), seconds(current.durationNanos())));
        }
        if (baseline.concurrency() != current.concurrency()) {
            warnings.add("concurrency differs (%d vs %d) — these are different experiments"
                    .formatted(baseline.concurrency(), current.concurrency()));
        }

        Set<String> names = new LinkedHashSet<>();
        current.runners().forEach(runner -> names.add(runner.name()));
        baseline.runners().forEach(runner -> names.add(runner.name()));

        List<RunnerDiff> diffs = new ArrayList<>();
        for (String name : names) {
            RunReport.RunnerReport before = baseline.runner(name);
            RunReport.RunnerReport after = current.runner(name);
            long baselineSpikes = countSpikes(baseline, name, false);
            long currentSpikes = countSpikes(current, name, false);
            long baselineCorrelated = countSpikes(baseline, name, true);
            long currentCorrelated = countSpikes(current, name, true);

            if (before == null) {
                diffs.add(new RunnerDiff(name, 0, after.p99Nanos(), 0, Verdict.NEW,
                        baselineSpikes, currentSpikes, baselineCorrelated, currentCorrelated));
                continue;
            }
            if (after == null) {
                diffs.add(new RunnerDiff(name, before.p99Nanos(), 0, 0, Verdict.REMOVED,
                        baselineSpikes, currentSpikes, baselineCorrelated, currentCorrelated));
                continue;
            }
            long delta = after.p99Nanos() - before.p99Nanos();
            double change = before.p99Nanos() == 0 ? 0 : (double) delta / before.p99Nanos();
            diffs.add(new RunnerDiff(name, before.p99Nanos(), after.p99Nanos(), change,
                    verdictFor(delta, change, budgetNanos, current.percentilePrecision()),
                    baselineSpikes, currentSpikes, baselineCorrelated, currentCorrelated));
        }
        return new Comparison(diffs, budgetNanos, warnings, pairSpikes(baseline, current, names));
    }

    private static List<SpikePair> pairSpikes(RunReport baseline, RunReport current, Set<String> names) {
        List<SpikePair> pairs = new ArrayList<>();
        for (String name : names) {
            List<RunReport.SpikeReport> before = spikesFor(baseline, name);
            List<RunReport.SpikeReport> after = spikesFor(current, name);
            int count = Math.max(before.size(), after.size());
            for (int i = 0; i < count; i++) {
                RunReport.SpikeReport b = i < before.size() ? before.get(i) : null;
                RunReport.SpikeReport c = i < after.size() ? after.get(i) : null;
                pairs.add(new SpikePair(name, i,
                        b == null ? null : b.storageP99Nanos(),
                        c == null ? null : c.storageP99Nanos(),
                        b == null ? null : b.masked(),
                        c == null ? null : c.masked()));
            }
        }
        return pairs;
    }

    private static List<RunReport.SpikeReport> spikesFor(RunReport report, String runnerName) {
        return report.spikes().stream()
                .filter(spike -> spike.storageRunner().equals(runnerName))
                .toList();
    }

    private static Verdict verdictFor(long deltaNanos, double changeFraction, long budgetNanos,
            double precision) {
        if (Math.abs(changeFraction) <= precision) {
            return Verdict.OK;
        }
        if (deltaNanos > budgetNanos) {
            return Verdict.REGRESSION;
        }
        if (-deltaNanos > budgetNanos) {
            return Verdict.IMPROVED;
        }
        return Verdict.OK;
    }

    private static void requireComparablePrecision(RunReport baseline, RunReport current) {
        if (Double.compare(baseline.percentilePrecision(), current.percentilePrecision()) != 0) {
            throw new IllegalArgumentException(
                    ("cannot compare runs recorded at different precisions (%.3f%% vs %.3f%%) — "
                            + "the difference between them can exceed a real regression")
                            .formatted(baseline.percentilePrecision() * 100,
                                    current.percentilePrecision() * 100));
        }
    }

    private static long countSpikes(RunReport report, String runnerName, boolean correlatedOnly) {
        return report.spikes().stream()
                .filter(spike -> spike.storageRunner().equals(runnerName))
                .filter(spike -> !correlatedOnly || !spike.masked())
                .count();
    }

    private static String seconds(long nanos) {
        return "%.1fs".formatted(nanos / 1_000_000_000.0);
    }
}
