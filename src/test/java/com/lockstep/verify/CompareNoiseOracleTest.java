package com.lockstep.verify;

import static org.assertj.core.api.Assertions.assertThat;

import com.lockstep.analysis.Bootstrap;
import com.lockstep.compare.RunComparator;
import com.lockstep.report.RunReport;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * Checks the noise gate against reports whose true answer is known by construction.
 *
 * <p>Two runs drawn from the <em>same</em> distribution must not be reported as different, however
 * far their point estimates happen to land. Two runs drawn from distributions that are genuinely
 * far apart must be. Building the reports by hand makes both cases unambiguous, which a pair of
 * real runs never is.
 */
final class CompareNoiseOracleTest {
    private static final long MS = 1_000_000L;

    private static final List<String> LEDGER = new ArrayList<>();

    private static void record(String claim, Object expected, Object reported, boolean ok) {
        LEDGER.add("  %-38s expected %-16s reported %-16s %s".formatted(
                claim, expected, reported, ok ? "MATCH" : "MISMATCH"));
    }

    private static void printLedger(String fixture) {
        System.out.println("\n=== oracle verification · " + fixture + " ===");
        LEDGER.forEach(System.out::println);
        LEDGER.clear();
    }

    /**
     * A report whose per-bucket p99s are drawn around {@code centreMillis} with {@code
     * spreadMillis} of variation, so the run's own noise is a property of the fixture.
     */
    private static RunReport reportWith(long centreMillis, long spreadMillis, int buckets,
            long seed) {
        Random random = new Random(seed);
        List<RunReport.BucketReport> bucketReports = new ArrayList<>();
        List<Long> values = new ArrayList<>();
        for (int i = 0; i < buckets; i++) {
            long p99 = (centreMillis + random.nextInt((int) (2 * spreadMillis + 1))
                    - spreadMillis) * MS;
            values.add(p99);
            bucketReports.add(new RunReport.BucketReport(i, i * 1_000_000_000L, 100, 0,
                    p99 / 2, p99 * 9 / 10, p99, p99, p99));
        }
        long meanP99 = Math.round(
                values.stream().mapToLong(Long::longValue).average().orElseThrow());

        RunReport.RunnerReport runner = new RunReport.RunnerReport("http",
                100L * buckets, 100L * buckets, 0, 100.0,
                100L * buckets, 100L * buckets, 0, 0, 0, 0, true, 0, 0,
                meanP99 / 2, meanP99 / 4, meanP99 / 2, meanP99 * 8 / 10, meanP99 * 9 / 10,
                meanP99, meanP99, meanP99,
                java.util.Map.of("200", 100L * buckets), java.util.Map.of(), bucketReports);

        return new RunReport("lockstep", RunReport.SCHEMA_VERSION, "test",
                "2026-09-30T00:00:00Z", buckets * 1_000_000_000L, 1_000_000_000L, 0, 10, 0.01,
                List.of(runner), List.of(), null, List.of(), List.of(), null, null);
    }

    // --- the bootstrap itself --------------------------------------------------------------------

    @Test
    void anIntervalIsSeededAndThereforeReproducible() {
        List<Long> samples = List.of(10L * MS, 12L * MS, 11L * MS, 13L * MS, 9L * MS, 14L * MS);
        var bootstrap = Bootstrap.defaults();

        var first = bootstrap.interval(samples, 42);
        var second = bootstrap.interval(samples, 42);
        var different = bootstrap.interval(samples, 43);

        record("same seed reproduces", first.low() + ".." + first.high(),
                second.low() + ".." + second.high(),
                first.low() == second.low() && first.high() == second.high());
        record("point is the sample mean", "11.5ms",
                "%.1fms".formatted(first.point() / (double) MS),
                Math.abs(first.point() - 11_500_000L) < 100_000);
        record("interval brackets the point", "yes",
                first.low() <= first.point() && first.point() <= first.high() ? "yes" : "no",
                first.low() <= first.point() && first.point() <= first.high());
        record("a different seed is allowed to differ",
                "(not asserted)",
                different.low() != first.low() || different.high() != first.high()
                        ? "differs" : "same",
                true);
        printLedger("bootstrap interval, six known samples");

        assertThat(first.low())
                .withFailMessage("a build must not pass or fail on the draw; the same inputs have "
                        + "to give the same interval")
                .isEqualTo(second.low());
        assertThat(first.high()).isEqualTo(second.high());
        assertThat(first.point()).isCloseTo(11_500_000L, org.assertj.core.data.Offset.offset(100_000L));
        assertThat(first.low()).isLessThanOrEqualTo(first.point());
        assertThat(first.high()).isGreaterThanOrEqualTo(first.point());
    }

    @Test
    void tooFewSamplesGivesNoIntervalRatherThanAConfidentOne() {
        var bootstrap = Bootstrap.defaults();

        assertThat(bootstrap.interval(List.of(10L * MS, 11L * MS), 1).usable())
                .withFailMessage("two buckets cannot bound anything, and pretending otherwise "
                        + "would let a short run gate a build")
                .isFalse();
        assertThat(bootstrap.interval(List.of(), 1).usable()).isFalse();
        assertThat(bootstrap.interval(null, 1).usable()).isFalse();
    }

    @Test
    void aWiderSpreadGivesAWiderInterval() {
        var bootstrap = Bootstrap.defaults();
        List<Long> tight = new ArrayList<>();
        List<Long> loose = new ArrayList<>();
        Random random = new Random(7);
        for (int i = 0; i < 30; i++) {
            tight.add((100L + random.nextInt(5)) * MS);
            loose.add((100L + random.nextInt(60)) * MS);
        }

        double tightWidth = bootstrap.interval(tight, 1).relativeHalfWidth();
        double looseWidth = bootstrap.interval(loose, 1).relativeHalfWidth();

        record("tight spread half-width", "small", "%.1f%%".formatted(tightWidth * 100), true);
        record("loose spread half-width", "> tight", "%.1f%%".formatted(looseWidth * 100),
                looseWidth > tightWidth);
        printLedger("bootstrap width tracks the spread");

        assertThat(looseWidth)
                .withFailMessage("a noisier run must produce a wider interval: tight %.4f, "
                        + "loose %.4f", tightWidth, looseWidth)
                .isGreaterThan(tightWidth);
    }

    // --- the gate, against known-identical and known-different runs ------------------------------

    @Test
    void twoRunsFromTheSameDistributionAreNotCalledARegression() {
        // Both centred on 100ms with 30ms of per-bucket variation: any difference between them is
        // noise by construction, whatever the point estimates land on.
        RunReport baseline = reportWith(100, 30, 20, 1);
        RunReport current = reportWith(100, 30, 20, 2);

        // A 1ms budget, which the 2.3ms difference between these two runs clears. Under the old
        // rule — precision, then budget, nothing else — that made this a REGRESSION. It is noise:
        // both runs came from the same distribution.
        var comparison = RunComparator.compare(baseline, current, MS);
        var diff = comparison.runners().get(0);

        record("budget", "1ms", "1ms", true);
        record("delta exceeds budget", "yes",
                Math.abs(diff.currentP99Nanos() - diff.baselineP99Nanos()) > MS ? "yes" : "no",
                Math.abs(diff.currentP99Nanos() - diff.baselineP99Nanos()) > MS);
        record("baseline p99", "~100ms", "%.1fms".formatted(diff.baselineP99Nanos() / (double) MS),
                true);
        record("current p99", "~100ms", "%.1fms".formatted(diff.currentP99Nanos() / (double) MS),
                true);
        record("change", "(noise)", "%+.1f%%".formatted(diff.changeFraction() * 100), true);
        record("noise half-width", "(recorded)", "%.1f%%".formatted(diff.noiseHalfWidth() * 100),
                true);
        record("distinguishable", false, diff.distinguishableFromNoise(),
                !diff.distinguishableFromNoise());
        record("verdict", "OK", diff.verdict(), diff.verdict() == RunComparator.Verdict.OK);
        record("build fails", false, comparison.failed(), !comparison.failed());
        printLedger("compare, two runs from the same distribution");

        assertThat(diff.noiseBounded()).isTrue();
        assertThat(diff.distinguishableFromNoise())
                .withFailMessage("""
                        both runs were drawn from the same distribution, so their intervals must \
                        overlap. baseline %s..%s, current %s..%s""",
                        diff.baselineInterval().low(), diff.baselineInterval().high(),
                        diff.currentInterval().low(), diff.currentInterval().high())
                .isFalse();
        assertThat(Math.abs(diff.currentP99Nanos() - diff.baselineP99Nanos()))
                .withFailMessage("the fixture only demonstrates anything if the difference does "
                        + "clear the budget")
                .isGreaterThan(MS);
        assertThat(diff.verdict())
                .withFailMessage("""
                        p99 differs by %.1f%%, which clears the 1ms budget, so the old rule would \
                        have called this a regression. Both runs came from the same distribution: \
                        it is noise, and the gate has to absorb it.""",
                        diff.changeFraction() * 100)
                .isEqualTo(RunComparator.Verdict.OK);
        assertThat(comparison.failed())
                .withFailMessage("a build must not fail on noise")
                .isFalse();
    }

    @Test
    void aRealShiftIsStillCalledARegression() {
        // 100ms against 400ms, both with the same 30ms of variation: far apart by construction.
        RunReport baseline = reportWith(100, 30, 20, 1);
        RunReport current = reportWith(400, 30, 20, 2);

        var comparison = RunComparator.compare(baseline, current, 5 * MS);
        var diff = comparison.runners().get(0);

        record("baseline p99", "~100ms", "%.1fms".formatted(diff.baselineP99Nanos() / (double) MS),
                true);
        record("current p99", "~400ms", "%.1fms".formatted(diff.currentP99Nanos() / (double) MS),
                true);
        record("change", "~+300%", "%+.0f%%".formatted(diff.changeFraction() * 100), true);
        record("distinguishable", true, diff.distinguishableFromNoise(),
                diff.distinguishableFromNoise());
        record("verdict", "REGRESSION", diff.verdict(),
                diff.verdict() == RunComparator.Verdict.REGRESSION);
        record("build fails", true, comparison.failed(), comparison.failed());
        printLedger("compare, a fourfold real shift");

        assertThat(diff.distinguishableFromNoise())
                .withFailMessage("100ms and 400ms with 30ms of variation cannot overlap")
                .isTrue();
        assertThat(diff.verdict())
                .withFailMessage("""
                        the gate must not swallow a real change: %+.0f%% with non-overlapping \
                        intervals is exactly what compare exists to catch""",
                        diff.changeFraction() * 100)
                .isEqualTo(RunComparator.Verdict.REGRESSION);
        assertThat(comparison.failed()).isTrue();
    }

    @Test
    void aRealImprovementIsStillCalledAnImprovement() {
        RunReport baseline = reportWith(400, 30, 20, 1);
        RunReport current = reportWith(100, 30, 20, 2);

        var diff = RunComparator.compare(baseline, current, 5 * MS).runners().get(0);

        record("change", "~-75%", "%+.0f%%".formatted(diff.changeFraction() * 100), true);
        record("distinguishable", true, diff.distinguishableFromNoise(),
                diff.distinguishableFromNoise());
        record("verdict", "IMPROVED", diff.verdict(),
                diff.verdict() == RunComparator.Verdict.IMPROVED);
        printLedger("compare, a real improvement");

        assertThat(diff.verdict()).isEqualTo(RunComparator.Verdict.IMPROVED);
    }

    @Test
    void theNoiseWarningNamesTheOverlapRatherThanStayingSilent() {
        RunReport baseline = reportWith(100, 30, 20, 1);
        RunReport current = reportWith(100, 30, 20, 2);

        var comparison = RunComparator.compare(baseline, current, MS);

        boolean warned = comparison.warnings().stream()
                .anyMatch(warning -> warning.contains("not distinguishable from noise"));

        record("warning present", "yes", warned ? "yes" : "no", warned);
        printLedger("compare, the noise warning");

        assertThat(warned)
                .withFailMessage("""
                        silently returning OK hides that a change was seen and discounted. The \
                        reader needs to know the measurement was too noisy to answer. Warnings: \
                        %s""", comparison.warnings())
                .isTrue();
    }

    @Test
    void aRunTooShortToBoundItsNoiseFallsBackToTheBudget() {
        // Three buckets: below the bootstrap's minimum, so the gate cannot apply and the old
        // budget rule must still work rather than silently passing everything.
        RunReport baseline = reportWith(100, 5, 3, 1);
        RunReport current = reportWith(400, 5, 3, 2);

        var diff = RunComparator.compare(baseline, current, 5 * MS).runners().get(0);

        record("noise bounded", false, diff.noiseBounded(), !diff.noiseBounded());
        record("verdict", "REGRESSION", diff.verdict(),
                diff.verdict() == RunComparator.Verdict.REGRESSION);
        printLedger("compare, too few buckets to bound noise");

        assertThat(diff.noiseBounded())
                .withFailMessage("three buckets is below the bootstrap minimum")
                .isFalse();
        assertThat(diff.verdict())
                .withFailMessage("""
                        with no interval to compare, the budget is all there is; returning OK \
                        would turn a short run into a way of passing any change""")
                .isEqualTo(RunComparator.Verdict.REGRESSION);
    }
}
