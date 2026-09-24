package com.lockstep.cli;

import com.lockstep.compare.CompareHtmlReport;
import com.lockstep.compare.RunComparator;
import com.lockstep.report.JsonExport;
import com.lockstep.report.RunReport;
import com.lockstep.util.Ansi;
import com.lockstep.util.Durations;
import com.lockstep.util.Numbers;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

@Command(name = "compare", description = "Compare a baseline run against a current run.")
public final class CompareCommand implements Callable<Integer> {
    static final int EXIT_OK = 0;
    static final int EXIT_REGRESSION = 1;
    static final int EXIT_INPUT_ERROR = 2;

    @Spec
    CommandSpec spec;

    @Option(names = {"-b", "--baseline"}, required = true, description = "Baseline JSON report.")
    Path baselinePath;

    @Option(names = {"-c", "--current"}, required = true, description = "Current JSON report.")
    Path currentPath;

    @Option(names = "--report", description = "Path for the HTML comparison (default: ${DEFAULT-VALUE}).")
    Path reportPath = Path.of("compare.html");

    @Option(names = "--no-report", description = "Skip writing the HTML comparison.")
    boolean noReport;

    @Option(names = "--no-fail-on-findings", description =
            "Do not fail when storage spikes the application feels have increased.")
    boolean noFailOnFindings;

    @Option(names = "--fail-on", description =
            "How much p99 growth is tolerated before a runner counts as regressed (default: ${DEFAULT-VALUE}).")
    String failOn = "100ms";

    @Override
    public Integer call() {
        PrintWriter out = spec.commandLine().getOut();
        PrintWriter err = spec.commandLine().getErr();

        RunReport baseline;
        RunReport current;
        long budget;
        try {
            budget = Durations.parseToNanos(failOn);
            baseline = read(baselinePath);
            current = read(currentPath);
        } catch (RuntimeException e) {
            err.println(Ansi.error(e.getMessage()));
            return EXIT_INPUT_ERROR;
        }

        RunComparator.Comparison comparison;
        try {
            comparison = RunComparator.compare(baseline, current, budget, !noFailOnFindings);
        } catch (IllegalArgumentException e) {
            err.println(Ansi.error(e.getMessage()));
            return EXIT_INPUT_ERROR;
        }

        out.println("comparing %s -> %s (fail-on %s)".formatted(
                baselinePath, currentPath, Durations.formatNanos(budget)));
        out.println();
        out.print(table(comparison));

        if (!comparison.warnings().isEmpty()) {
            out.println();
            for (String warning : comparison.warnings()) {
                out.println(Ansi.accent("! ") + warning);
            }
        }

        List<String> findings = comparison.runners().stream()
                .filter(RunComparator.RunnerDiff::findingsWorsened)
                .map(diff -> "%s: %d correlated spikes, up from %d"
                        .formatted(diff.name(), diff.currentCorrelated(), diff.baselineCorrelated()))
                .toList();
        if (!findings.isEmpty()) {
            out.println();
            out.println((noFailOnFindings ? Ansi.accent("! ") : Ansi.error("FAIL "))
                    + "storage spikes the application feels have increased:");
            findings.forEach(finding -> out.println("  " + finding));
            if (noFailOnFindings) {
                out.println("  (not failing the build: --no-fail-on-findings)");
            }
        }

        if (!noReport) {
            CompareHtmlReport.write(comparison, baselinePath.toString(), currentPath.toString(), reportPath);
            out.println();
            out.println("comparison written to " + reportPath);
        }

        out.flush();
        return comparison.failed() ? EXIT_REGRESSION : EXIT_OK;
    }

    private RunReport read(Path path) {
        if (!Files.exists(path)) {
            throw new IllegalArgumentException("report not found: " + path);
        }
        return JsonExport.read(path);
    }

    private static String table(RunComparator.Comparison comparison) {
        List<String[]> rows = new ArrayList<>();
        rows.add(new String[] {"RUNNER", "BASELINE_P50", "CURRENT_P50", "BASELINE_P99",
            "CURRENT_P99", "CHANGE", "VERDICT"});
        for (RunComparator.RunnerDiff diff : comparison.runners()) {
            boolean oneSided = diff.verdict() == RunComparator.Verdict.NEW
                    || diff.verdict() == RunComparator.Verdict.REMOVED;
            String verdict = diff.p50Verdict() == RunComparator.Verdict.REGRESSION
                    && diff.verdict() != RunComparator.Verdict.REGRESSION
                    ? "REGRESSION (p50)"
                    : diff.verdict().name();
            rows.add(new String[] {
                diff.name(),
                diff.verdict() == RunComparator.Verdict.NEW ? "-" : Numbers.latency(diff.baselineP50Nanos()),
                diff.verdict() == RunComparator.Verdict.REMOVED ? "-" : Numbers.latency(diff.currentP50Nanos()),
                diff.verdict() == RunComparator.Verdict.NEW ? "-" : Numbers.latency(diff.baselineP99Nanos()),
                diff.verdict() == RunComparator.Verdict.REMOVED ? "-" : Numbers.latency(diff.currentP99Nanos()),
                oneSided ? "-" : String.format(Locale.ROOT, "%+.0f%%", diff.changeFraction() * 100),
                verdict,
            });
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
            StringBuilder line = new StringBuilder();
            for (int i = 0; i < columns; i++) {
                line.append(rows.get(r)[i]);
                if (i < columns - 1) {
                    line.append(" ".repeat(widths[i] - rows.get(r)[i].length() + 2));
                }
            }
            String text = line.toString().stripTrailing();
            out.append(r == 0 ? Ansi.bold(text)
                    : rows.get(r)[6].startsWith("REGRESSION") ? Ansi.error(text) : text).append('\n');
        }
        return out.toString();
    }
}
