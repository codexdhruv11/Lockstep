package com.lockstep.analysis;

import static org.assertj.core.api.Assertions.assertThat;

import com.lockstep.report.CliTables;
import java.util.List;
import org.junit.jupiter.api.Test;

final class GrowthCurveTest {

    private static final long MS = 1_000_000L;

    private static GrowthCurve.Point point(long rows, long serviceMillis) {
        return new GrowthCurve.Point(rows, serviceMillis * MS, serviceMillis * MS, 0, -1, 100);
    }

    private static GrowthCurve.Point point(long rows, long serviceMillis, long bytesPerRequest,
            double hitRatio) {
        return new GrowthCurve.Point(rows, serviceMillis * MS, serviceMillis * MS,
                bytesPerRequest, hitRatio, 100);
    }

    // --- fitting --------------------------------------------------------------------------------

    @Test
    void aCostProportionalToRowCountFitsAnExponentOfOne() {
        GrowthCurve curve = GrowthCurve.fit(List.of(
                point(1_000, 10), point(2_000, 20), point(4_000, 40)));

        assertThat(curve.exponent()).isCloseTo(1.0, org.assertj.core.data.Offset.offset(0.01));
        assertThat(curve.rSquared()).isCloseTo(1.0, org.assertj.core.data.Offset.offset(0.001));
        assertThat(curve.shape()).isEqualTo(GrowthCurve.Shape.LINEAR);
    }

    @Test
    void aCostThatIgnoresRowCountFitsAnExponentOfZero() {
        GrowthCurve curve = GrowthCurve.fit(List.of(
                point(1_000, 5), point(2_000, 5), point(4_000, 5)));

        assertThat(curve.exponent()).isCloseTo(0.0, org.assertj.core.data.Offset.offset(0.01));
        assertThat(curve.shape())
                .withFailMessage("an index lookup whose latency does not move with the table is "
                        + "the flat case, and it is the answer people most want confirmed")
                .isEqualTo(GrowthCurve.Shape.FLAT);
    }

    @Test
    void aQuadraticCostIsReportedAsSuperlinear() {
        GrowthCurve curve = GrowthCurve.fit(List.of(
                point(1_000, 1), point(2_000, 4), point(4_000, 16)));

        assertThat(curve.exponent()).isCloseTo(2.0, org.assertj.core.data.Offset.offset(0.01));
        assertThat(curve.shape()).isEqualTo(GrowthCurve.Shape.SUPERLINEAR);
    }

    @Test
    void aSquareRootCostIsReportedAsSublinear() {
        GrowthCurve curve = GrowthCurve.fit(List.of(
                point(1_000, 10), point(4_000, 20), point(16_000, 40)));

        assertThat(curve.exponent()).isCloseTo(0.5, org.assertj.core.data.Offset.offset(0.01));
        assertThat(curve.shape()).isEqualTo(GrowthCurve.Shape.SUBLINEAR);
    }

    @Test
    void tooFewPointsIsNotACurve() {
        assertThat(GrowthCurve.fit(List.of()).shape()).isEqualTo(GrowthCurve.Shape.UNKNOWN);
        assertThat(GrowthCurve.fit(List.of(point(1_000, 10))).shape())
                .isEqualTo(GrowthCurve.Shape.UNKNOWN);
        assertThat(GrowthCurve.fit(null).points()).isEmpty();
    }

    @Test
    void measurementsAllAtTheSameRowCountCannotBeFitted() {
        GrowthCurve curve = GrowthCurve.fit(List.of(
                point(1_000, 10), point(1_000, 20), point(1_000, 30)));

        assertThat(curve.exponent()).isZero();
        assertThat(curve.shape()).isEqualTo(GrowthCurve.Shape.UNKNOWN);
    }

    @Test
    void zeroAndNegativeMeasurementsAreExcludedRatherThanLogged() {
        GrowthCurve curve = GrowthCurve.fit(List.of(
                new GrowthCurve.Point(0, 10 * MS, 10 * MS, 0, -1, 0),
                new GrowthCurve.Point(1_000, 0, 0, 0, -1, 0),
                point(2_000, 20), point(4_000, 40)));

        assertThat(curve.exponent())
                .withFailMessage("a row count or latency of zero has no logarithm; including it "
                        + "would produce NaN rather than an error")
                .isCloseTo(1.0, org.assertj.core.data.Offset.offset(0.01));
    }

    // --- extrapolation -------------------------------------------------------------------------

    @Test
    void theBudgetCrossingFollowsFromTheFit() {
        // 10ms at 1,000 rows, linear: 10,000ns a row, so 300ms arrives at 30,000 rows.
        GrowthCurve curve = GrowthCurve.fit(List.of(
                point(1_000, 10), point(2_000, 20), point(4_000, 40)));

        assertThat(curve.rowsAtBudget(300 * MS)).isBetween(29_500L, 30_500L);
        assertThat(curve.predictServiceNanos(8_000)).isBetween(79L * MS, 81L * MS);
    }

    @Test
    void aFlatCurveHasNoBudgetCrossingToReport() {
        GrowthCurve curve = GrowthCurve.fit(List.of(
                point(1_000, 5), point(2_000, 5), point(4_000, 5)));

        assertThat(curve.rowsAtBudget(300 * MS))
                .withFailMessage("extrapolating a flat line to a budget it never reaches would "
                        + "invent a row count from nothing")
                .isEqualTo(-1);
        assertThat(curve.extrapolatable()).isFalse();
    }

    @Test
    void aBudgetAlreadyBreachedAtTheSmallestMeasurementIsSaidSo() {
        GrowthCurve curve = GrowthCurve.fit(List.of(
                point(1_000, 400), point(2_000, 800), point(4_000, 1_600)));

        assertThat(curve.budgetAlreadyExceeded(300 * MS)).isTrue();
        assertThat(curve.budgetAlreadyExceeded(2_000 * MS)).isFalse();
    }

    @Test
    void twoPointsAreNotEnoughToExtrapolateFrom() {
        GrowthCurve curve = GrowthCurve.fit(List.of(point(1_000, 10), point(2_000, 20)));

        assertThat(curve.exponent()).isCloseTo(1.0, org.assertj.core.data.Offset.offset(0.01));
        assertThat(curve.extrapolatable())
                .withFailMessage("two points fit any line perfectly, so R-squared says nothing "
                        + "about whether the model is right")
                .isFalse();
        assertThat(curve.rowsAtBudget(300 * MS)).isEqualTo(-1);
    }

    @Test
    void aPoorFitIsNotExtrapolatedFrom() {
        GrowthCurve curve = GrowthCurve.fit(List.of(
                point(1_000, 10), point(2_000, 90), point(4_000, 12), point(8_000, 400)));

        assertThat(curve.rSquared()).isLessThan(GrowthCurve.MIN_R_SQUARED);
        assertThat(curve.extrapolatable()).isFalse();
        assertThat(curve.rowsAtBudget(300 * MS)).isEqualTo(-1);
    }

    @Test
    void aCrossingFarBeyondTheDataIsRefusedHoweverGoodTheFit() {
        // The measured case: an indexed lookup, 2.0ms to 2.7ms over 25k to 100k rows. A real fit
        // (R-squared 0.982) on a nearly flat line, which solved out to 22 quadrillion rows.
        GrowthCurve curve = GrowthCurve.fit(List.of(
                new GrowthCurve.Point(25_000, 2_000_000L, 2_600_000L, 24_576, 1.0, 30),
                new GrowthCurve.Point(50_000, 2_300_000L, 3_000_000L, 24_576, 1.0, 30),
                new GrowthCurve.Point(100_000, 2_700_000L, 3_100_000L, 24_576, 1.0, 30)));

        assertThat(curve.rSquared()).isGreaterThan(0.9);
        assertThat(curve.exponent()).isGreaterThan(0.15);
        assertThat(curve.largestMeasuredRows()).isEqualTo(100_000);
        assertThat(curve.budgetBeyondExtrapolationRange(500 * MS)).isTrue();
        assertThat(curve.rowsAtBudget(500 * MS))
                .withFailMessage("a fit over a 4x span of row counts says nothing about 10^16, "
                        + "however well it describes the points it has")
                .isEqualTo(-1);
    }

    @Test
    void aCrossingJustBeyondTheDataIsStillReported() {
        // Linear, 10ms at 1,000 rows: a 300ms budget lands at 30,000, which is 7.5x the largest
        // measurement of 4,000 — inside the allowed range.
        GrowthCurve curve = GrowthCurve.fit(List.of(
                point(1_000, 10), point(2_000, 20), point(4_000, 40)));

        assertThat(curve.budgetBeyondExtrapolationRange(300 * MS)).isFalse();
        assertThat(curve.rowsAtBudget(300 * MS)).isBetween(29_500L, 30_500L);
    }

    @Test
    void anOutOfRangeBudgetIsRenderedAsOutOfRangeNotAsANumber() {
        GrowthCurve curve = GrowthCurve.fit(List.of(
                new GrowthCurve.Point(25_000, 2_000_000L, 2_600_000L, 24_576, 1.0, 30),
                new GrowthCurve.Point(50_000, 2_300_000L, 3_000_000L, 24_576, 1.0, 30),
                new GrowthCurve.Point(100_000, 2_700_000L, 3_100_000L, 24_576, 1.0, 30)));

        String out = CliTables.growthCurveVerdict(curve, 500 * MS, null, "signals");

        assertThat(out).contains("not reached within 10×");
        assertThat(out).contains("100,000 rows");
        assertThat(out)
                .withFailMessage("printing an absurd row count destroys trust in every other "
                        + "number in the report")
                .doesNotContain("reaches it at about");
    }

    @Test
    void timeFallingWhileTheWorkRisesIsReportedAsContamination() {
        // The measured case: bytes per request scaled 4x across the steps while service time
        // fell, fitting rows^-0.29 at R-squared 0.977. A confident fit to interference.
        GrowthCurve curve = GrowthCurve.fit(List.of(
                new GrowthCurve.Point(25_000, 147 * MS, 148 * MS, 5_800_000, 1.0, 30),
                new GrowthCurve.Point(50_000, 114 * MS, 117 * MS, 11_500_000, 1.0, 30),
                new GrowthCurve.Point(100_000, 98 * MS, 98 * MS, 23_000_000, 1.0, 30)));

        assertThat(curve.workExponent()).isCloseTo(1.0, org.assertj.core.data.Offset.offset(0.05));
        assertThat(curve.exponent()).isNegative();
        assertThat(curve.workGrewButTimeDidNot())
                .withFailMessage("work rose and time fell; that is not a property of the target")
                .isTrue();
        assertThat(curve.fixedCostMasksScaling())
                .withFailMessage("this is contamination, not a fixed cost to quantify — the two "
                        + "have different remedies and must not be conflated")
                .isFalse();

        String out = CliTables.growthCurveVerdict(curve, 2_000 * MS, null, "signals");
        assertThat(out).contains("cannot support a growth curve");
        assertThat(out).contains("Re-measure on an otherwise idle machine");
        assertThat(out)
                .withFailMessage("a budget crossing from contaminated data would be invention")
                .doesNotContain("reaches it at about");
    }

    @Test
    void aGenuineFixedCostIsNotReportedAsContamination() {
        // Time rises, just more slowly than the work: that is fixed overhead, which is a real
        // property worth quantifying rather than a reason to re-run.
        GrowthCurve curve = GrowthCurve.fit(List.of(
                new GrowthCurve.Point(20_000, 10 * MS, 10 * MS, 2_800_000, 1.0, 20),
                new GrowthCurve.Point(40_000, 14 * MS, 14 * MS, 5_600_000, 1.0, 20),
                new GrowthCurve.Point(80_000, 25 * MS, 25 * MS, 11_200_000, 1.0, 20)));

        assertThat(curve.exponent()).isPositive();
        assertThat(curve.workGrewButTimeDidNot()).isFalse();
        assertThat(curve.fixedCostMasksScaling())
                .withFailMessage("work %.2f against time %.2f is the fixed-cost case",
                        curve.workExponent(), curve.exponent())
                .isTrue();
    }

    // --- the cache cliff -----------------------------------------------------------------------

    @Test
    void aFallingHitRatioIsReportedAsTheKnee() {
        GrowthCurve curve = GrowthCurve.fit(List.of(
                point(10_000, 10, 1_000, 1.0),
                point(20_000, 20, 2_000, 1.0),
                point(40_000, 90, 4_000, 0.82)));

        assertThat(curve.cacheCliffAtRows()).isEqualTo(40_000);
        assertThat(curve.spansCacheCliff()).isTrue();
    }

    @Test
    void aPowerLawIsNotExtrapolatedAcrossACliff() {
        GrowthCurve curve = GrowthCurve.fit(List.of(
                point(10_000, 10, 1_000, 1.0),
                point(20_000, 20, 2_000, 1.0),
                point(40_000, 90, 4_000, 0.82)));

        assertThat(curve.extrapolatable())
                .withFailMessage("latency steps at a cliff rather than curving, so one exponent "
                        + "cannot describe both sides")
                .isFalse();
        assertThat(curve.rowsAtBudget(300 * MS)).isEqualTo(-1);
    }

    @Test
    void aSteadyHitRatioIsNotMistakenForACliff() {
        GrowthCurve curve = GrowthCurve.fit(List.of(
                point(10_000, 10, 1_000, 1.0),
                point(20_000, 20, 2_000, 0.995),
                point(40_000, 40, 4_000, 0.99)));

        assertThat(curve.cacheCliffAtRows()).isEqualTo(-1);
        assertThat(curve.extrapolatable()).isTrue();
    }

    @Test
    void anUnmeasuredHitRatioIsNotACliff() {
        GrowthCurve curve = GrowthCurve.fit(List.of(
                point(10_000, 10, 1_000, -1), point(20_000, 20, 2_000, -1),
                point(40_000, 40, 4_000, -1)));

        assertThat(curve.cacheCliffAtRows()).isEqualTo(-1);
    }

    @Test
    void growingBytesPerRequestSeparatesAScanFromAnIndex() {
        GrowthCurve scan = GrowthCurve.fit(List.of(
                point(10_000, 10, 1_000_000, 1.0), point(20_000, 20, 2_000_000, 1.0),
                point(40_000, 40, 4_000_000, 1.0)));
        assertThat(scan.bytesPerRequestGrew()).isTrue();

        GrowthCurve indexed = GrowthCurve.fit(List.of(
                point(10_000, 5, 16_384, 1.0), point(20_000, 5, 16_384, 1.0),
                point(40_000, 5, 24_576, 1.0)));
        assertThat(indexed.bytesPerRequestGrew()).isFalse();
    }

    // --- rendering -----------------------------------------------------------------------------

    @Test
    void nothingMeasuredRendersNothing() {
        assertThat(CliTables.growthCurveTable(null)).isEmpty();
        assertThat(CliTables.growthCurveTable(GrowthCurve.fit(List.of()))).isEmpty();
        assertThat(CliTables.growthCurveVerdict(GrowthCurve.fit(List.of()), 0, null, "t"))
                .contains("not enough measurements");
    }

    @Test
    void theVerdictNamesTheExponentAndTheShape() {
        GrowthCurve curve = GrowthCurve.fit(List.of(
                point(1_000, 10), point(2_000, 20), point(4_000, 40)));

        String out = CliTables.growthCurveVerdict(curve, 0, null, "signals");

        assertThat(out).contains("rows^1.0").contains("linear");
        assertThat(out).contains("service time grows");
        assertThat(out)
                .withFailMessage("the exponent transfers between machines and the latencies do "
                        + "not; saying so is the difference between a result and a number")
                .contains("the exponent transfers");
    }

    @Test
    void theBudgetLineGivesRowsAndOptionallyDays() {
        GrowthCurve curve = GrowthCurve.fit(List.of(
                point(1_000, 10), point(2_000, 20), point(4_000, 40)));

        String withoutRate = CliTables.growthCurveVerdict(curve, 300 * MS, null, "signals");
        assertThat(withoutRate).contains("30,000 rows");
        assertThat(withoutRate).doesNotContain("days away");

        // 30,000 crossing minus 4,000 current = 26,000 headroom at 1,000/day = 26 days.
        String withRate = CliTables.growthCurveVerdict(curve, 300 * MS, 1_000L, "signals");
        assertThat(withRate).contains("26 days away");
    }

    @Test
    void aCliffIsRenderedAsTheHeadlineRatherThanTheFit() {
        GrowthCurve curve = GrowthCurve.fit(List.of(
                point(10_000, 10, 1_000, 1.0),
                point(20_000, 20, 2_000, 1.0),
                point(40_000, 90, 4_000, 0.82)));

        String out = CliTables.growthCurveVerdict(curve, 300 * MS, null, "signals");

        assertThat(out).contains("buffer cache stopped holding").contains("40,000");
        assertThat(out).contains("this is the knee");
        assertThat(out)
                .withFailMessage("a budget crossing computed through a cliff must not be printed "
                        + "as though it were sound")
                .doesNotContain("reaches it at about");
        assertThat(out).contains("not extrapolating");
    }

    @Test
    void anAlreadyBreachedBudgetIsFlaggedNotExtrapolated() {
        GrowthCurve curve = GrowthCurve.fit(List.of(
                point(1_000, 400), point(2_000, 800), point(4_000, 1_600)));

        String out = CliTables.growthCurveVerdict(curve, 300 * MS, null, "signals");

        assertThat(out).contains("already exceeded at the smallest measurement");
        assertThat(out).doesNotContain("reaches it at about");
    }

    @Test
    void theTableShowsEveryMeasuredStep() {
        String out = CliTables.growthCurveTable(GrowthCurve.fit(List.of(
                point(10_000, 10, 4_096, 1.0), point(20_000, 20, 8_192, 0.99))));

        assertThat(out).contains("ROWS").contains("SERVICE_P99").contains("BYTES/REQ").contains("HIT%");
        assertThat(out).contains("10,000").contains("20,000").contains("4KB").contains("8KB");
    }

    @Test
    void anUnmeasuredHitRatioRendersAsADashNotZero() {
        String out = CliTables.growthCurveTable(GrowthCurve.fit(List.of(
                point(10_000, 10, 4_096, -1), point(20_000, 20, 8_192, -1))));

        assertThat(out)
                .withFailMessage("a hit ratio of 0%% would read as a total cache miss")
                .doesNotContain("0.0%");
    }
}
