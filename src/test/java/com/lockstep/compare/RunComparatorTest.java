package com.lockstep.compare;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.lockstep.compare.RunComparator.Comparison;
import com.lockstep.compare.RunComparator.Verdict;
import com.lockstep.report.RunReport;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class RunComparatorTest {
    private static final long MS = 1_000_000L;
    private static final long SECOND = 1_000_000_000L;

    private static RunReport.RunnerReport runner(String name, long p99Nanos) {
        return new RunReport.RunnerReport(name, 100, 100, 0, 10.0, 100, 100, 0, 0, 0, true,
                p99Nanos / 2, p99Nanos / 2, p99Nanos - MS, p99Nanos, p99Nanos, p99Nanos,
                Map.of("200", 100L), List.of());
    }

    private static RunReport report(List<RunReport.RunnerReport> runners) {
        return report(runners, List.of(), 0.01, 10 * SECOND, 8);
    }

    private static RunReport report(List<RunReport.RunnerReport> runners,
            List<RunReport.SpikeReport> spikes, double precision, long durationNanos, int concurrency) {
        return new RunReport("lockstep", 1, "test", "2026-09-24T00:00:00Z",
                durationNanos, SECOND, 0, concurrency, precision, runners, spikes, null, List.of());
    }

    private static RunReport.SpikeReport spike(String runner, boolean masked) {
        return new RunReport.SpikeReport(0, 0, runner, 500 * MS, 50 * MS, masked, masked ? "DB" : "DB");
    }

    @Test
    void growthBeyondTheBudgetIsARegression() {
        Comparison comparison = RunComparator.compare(
                report(List.of(runner("http", 100 * MS))),
                report(List.of(runner("http", 350 * MS))),
                100 * MS);

        assertThat(comparison.failed()).isTrue();
        assertThat(comparison.regressions()).hasSize(1);
        var diff = comparison.runners().get(0);
        assertThat(diff.verdict()).isEqualTo(Verdict.REGRESSION);
        assertThat(diff.changeFraction()).isCloseTo(2.5, org.assertj.core.data.Offset.offset(0.01));
    }

    @Test
    void aLargeProportionalChangeInsideTheBudgetIsNotARegression() {
        Comparison comparison = RunComparator.compare(
                report(List.of(runner("http", 5 * MS))),
                report(List.of(runner("http", 40 * MS))),
                100 * MS);

        assertThat(comparison.failed()).isFalse();
        assertThat(comparison.runners().get(0).verdict()).isEqualTo(Verdict.OK);
    }

    @Test
    void aSmallProportionalChangeOnASlowServiceCanStillRegress() {
        Comparison comparison = RunComparator.compare(
                report(List.of(runner("http", 900 * MS))),
                report(List.of(runner("http", 1200 * MS))),
                100 * MS);

        assertThat(comparison.regressions()).hasSize(1);
    }

    @Test
    void aChangeSmallerThanMeasurementPrecisionIsReportedAsUnchanged() {
        Comparison comparison = RunComparator.compare(
                report(List.of(runner("http", 1000 * MS))),
                report(List.of(runner("http", 1005 * MS))),
                1 * MS);

        assertThat(comparison.runners().get(0).verdict()).isEqualTo(Verdict.OK);
        assertThat(comparison.failed()).isFalse();
    }

    @Test
    void gettingFasterIsReportedAsAnImprovementNotAFailure() {
        Comparison comparison = RunComparator.compare(
                report(List.of(runner("db", 500 * MS))),
                report(List.of(runner("db", 120 * MS))),
                100 * MS);

        assertThat(comparison.runners().get(0).verdict()).isEqualTo(Verdict.IMPROVED);
        assertThat(comparison.failed()).isFalse();
    }

    @Test
    void aRunnerAddedInTheCurrentRunIsNeverARegression() {
        Comparison comparison = RunComparator.compare(
                report(List.of(runner("http", 20 * MS))),
                report(List.of(runner("http", 20 * MS), runner("redis", 900 * MS))),
                100 * MS);

        assertThat(comparison.failed()).isFalse();
        assertThat(comparison.runners()).extracting(RunComparator.RunnerDiff::verdict)
                .containsExactly(Verdict.OK, Verdict.NEW);
    }

    @Test
    void aRunnerDroppedFromTheConfigIsNotARegressionEither() {
        Comparison comparison = RunComparator.compare(
                report(List.of(runner("http", 20 * MS), runner("db", 30 * MS))),
                report(List.of(runner("http", 20 * MS))),
                100 * MS);

        assertThat(comparison.failed()).isFalse();
        assertThat(comparison.runners()).extracting(RunComparator.RunnerDiff::name)
                .containsExactly("http", "db");
        assertThat(comparison.runners().get(1).verdict()).isEqualTo(Verdict.REMOVED);
    }

    @Test
    void reportsRecordedAtDifferentPrecisionsAreRefused() {
        assertThatThrownBy(() -> RunComparator.compare(
                report(List.of(runner("http", 100 * MS)), List.of(), 0.01, 10 * SECOND, 8),
                report(List.of(runner("http", 100 * MS)), List.of(), 0.001, 10 * SECOND, 8),
                100 * MS))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("different precisions");
    }

    @Test
    void runsWithDifferentLoadAreComparedButFlagged() {
        Comparison comparison = RunComparator.compare(
                report(List.of(runner("http", 20 * MS)), List.of(), 0.01, 10 * SECOND, 8),
                report(List.of(runner("http", 20 * MS)), List.of(), 0.01, 60 * SECOND, 64),
                100 * MS);

        assertThat(comparison.warnings()).hasSize(2);
        assertThat(comparison.warnings().get(0)).contains("durations differ");
        assertThat(comparison.warnings().get(1)).contains("concurrency differs");
    }

    @Test
    void findingsGettingWorseIsVisibleEvenWhenLatencyBarelyMoved() {
        List<RunReport.SpikeReport> before = new ArrayList<>();
        List<RunReport.SpikeReport> after = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            before.add(spike("db", true));
            after.add(spike("db", false));
        }

        Comparison comparison = RunComparator.compare(
                report(List.of(runner("db", 200 * MS)), before, 0.01, 10 * SECOND, 8),
                report(List.of(runner("db", 205 * MS)), after, 0.01, 10 * SECOND, 8),
                100 * MS);

        var diff = comparison.runners().get(0);
        assertThat(diff.verdict()).isEqualTo(Verdict.OK);
        assertThat(diff.findingsWorsened()).isTrue();
        assertThat(diff.baselineCorrelated()).isZero();
        assertThat(diff.currentCorrelated()).isEqualTo(3);
    }

    @Test
    void spikesArePairedByPositionNotByTime() {
        Comparison comparison = RunComparator.compare(
                report(List.of(runner("db", 200 * MS)), List.of(spike("db", true), spike("db", true)),
                        0.01, 10 * SECOND, 8),
                report(List.of(runner("db", 205 * MS)), List.of(spike("db", false)),
                        0.01, 10 * SECOND, 8),
                100 * MS);

        assertThat(comparison.spikePairs()).hasSize(2);
        var first = comparison.spikePairs().get(0);
        assertThat(first.ordinal()).isZero();
        assertThat(first.becameCorrelated()).isTrue();

        var second = comparison.spikePairs().get(1);
        assertThat(second.currentStorageP99Nanos()).isNull();
        assertThat(second.becameCorrelated()).isFalse();
    }

    @Test
    void theHtmlComparisonEmbedsTheSameFiguresTheCliPrints() {
        Comparison comparison = RunComparator.compare(
                report(List.of(runner("http", 100 * MS))),
                report(List.of(runner("http", 350 * MS))),
                100 * MS);

        String html = CompareHtmlReport.render(comparison, "base.json", "new.json");

        assertThat(html).contains("base.json").contains("new.json");
        assertThat(html).contains("\"verdict\" : \"REGRESSION\"");
        assertThat(html).contains("\"budgetNanos\" : 100000000");
        assertThat(html).doesNotContain("src=\"http");
    }

    @Test
    void theBudgetIsHonouredExactly() {
        assertThat(RunComparator.compare(
                report(List.of(runner("http", 100 * MS))),
                report(List.of(runner("http", 200 * MS))),
                100 * MS).failed()).isFalse();

        assertThat(RunComparator.compare(
                report(List.of(runner("http", 100 * MS))),
                report(List.of(runner("http", 200 * MS + 1))),
                100 * MS).failed()).isTrue();
    }
}
