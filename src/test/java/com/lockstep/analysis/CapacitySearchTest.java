package com.lockstep.analysis;

import static org.assertj.core.api.Assertions.assertThat;

import com.lockstep.analysis.CapacitySearch.Measurement;
import com.lockstep.analysis.CapacitySearch.Strain;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class CapacitySearchTest {
    private static final long MS = 1_000_000L;

    private static final class Target implements CapacitySearch.Probe {
        private final double kneeMultiplier;
        private final long healthyP99Nanos;
        final List<Double> probed = new ArrayList<>();

        Target(double kneeMultiplier, long healthyP99Nanos) {
            this.kneeMultiplier = kneeMultiplier;
            this.healthyP99Nanos = healthyP99Nanos;
        }

        @Override
        public Measurement at(double multiplier) {
            probed.add(multiplier);
            long scheduled = (long) (100 * multiplier);
            if (multiplier <= kneeMultiplier) {
                return new Measurement(10 * multiplier, 10 * multiplier, healthyP99Nanos,
                        scheduled, scheduled, "db");
            }
            long executed = (long) (scheduled * (kneeMultiplier / multiplier));
            return new Measurement(10 * multiplier, 10 * kneeMultiplier, healthyP99Nanos * 40,
                    scheduled, executed, "db");
        }
    }

    @Test
    void bracketsTheKneeAndThenNarrowsIt() {
        Target target = new Target(5.0, 10 * MS);

        CapacitySearch.Result result = CapacitySearch.search(target, 9, 3);

        assertThat(result.bracketed()).isTrue();

        assertThat(result.sustained().multiplier()).isLessThanOrEqualTo(5.0);
        assertThat(result.strained().multiplier()).isGreaterThan(5.0);
        assertThat(result.strained().multiplier() / result.sustained().multiplier())
                .withFailMessage("the bracket must be narrower than a factor of two, got %s..%s",
                        result.sustained().multiplier(), result.strained().multiplier())
                .isLessThan(1.5);
    }

    @Test
    void searchesDownwardWhenTheConfiguredRateIsAlreadyTooMuch() {
        Target target = new Target(0.2, 10 * MS);

        CapacitySearch.Result result = CapacitySearch.search(target, 9, 3);

        assertThat(result.bracketed()).isTrue();
        assertThat(result.sustained().multiplier()).isLessThanOrEqualTo(0.2);
        assertThat(target.probed.get(0)).isEqualTo(1.0);
        assertThat(target.probed.get(1))
                .withFailMessage("after straining at the configured rate the search must go down")
                .isLessThan(1.0);
    }

    @Test
    void latencyBlowUpCountsAsStrainEvenWhenEveryRequestIsDelivered() {
        CapacitySearch.Probe absorbing = multiplier -> {
            long scheduled = (long) (100 * multiplier);
            long p99 = multiplier <= 4 ? 10 * MS : 2000 * MS;
            return new Measurement(10 * multiplier, 10 * multiplier, p99, scheduled, scheduled, "http");
        };

        CapacitySearch.Result result = CapacitySearch.search(absorbing, 9, 3);

        assertThat(result.bracketed()).isTrue();
        assertThat(result.strain()).isEqualTo(Strain.LATENCY);
        assertThat(result.sustained().multiplier()).isLessThanOrEqualTo(4.0);
    }

    @Test
    void aTargetThatNeverGivesWayIsReportedAsNotFoundRatherThanAsANumber() {
        CapacitySearch.Probe unbreakable = multiplier -> {
            long scheduled = (long) (100 * multiplier);
            return new Measurement(10 * multiplier, 10 * multiplier, 5 * MS, scheduled, scheduled, "redis");
        };

        CapacitySearch.Result result = CapacitySearch.search(unbreakable, 6, 2);

        assertThat(result.bracketed()).isFalse();
        assertThat(result.strained()).isNull();
        assertThat(result.sustained()).isNotNull();
        assertThat(result.ranOutOfSteps()).isTrue();
    }

    @Test
    void aTargetThatStrainsAtEveryRateIsAlsoReportedAsNotFound() {
        CapacitySearch.Probe alwaysBroken = multiplier -> {
            long scheduled = (long) (100 * multiplier);
            return new Measurement(10 * multiplier, 1, 900 * MS, scheduled, scheduled / 10, "db");
        };

        CapacitySearch.Result result = CapacitySearch.search(alwaysBroken, 6, 2);

        assertThat(result.bracketed()).isFalse();
        assertThat(result.sustained()).isNull();
        assertThat(result.strained()).isNotNull();
    }

    @Test
    void theStepBudgetIsNeverExceeded() {
        Target target = new Target(300.0, 10 * MS);

        CapacitySearch.Result result = CapacitySearch.search(target, 5, 2);

        assertThat(result.steps()).hasSizeLessThanOrEqualTo(5);
        assertThat(target.probed).hasSizeLessThanOrEqualTo(5);
    }

    @Test
    void theSameRateGetsTheSameVerdictWheneverItIsTried() {
        CapacitySearch.Probe degrading = multiplier -> {
            long scheduled = (long) (100 * multiplier);
            long p99 = (long) (10 * MS * Math.pow(multiplier, 2));
            return new Measurement(10 * multiplier, 10 * multiplier, p99, scheduled, scheduled, "db");
        };

        CapacitySearch.Result result = CapacitySearch.search(degrading, 9, 3);

        Map<Double, Boolean> verdicts = new java.util.HashMap<>();
        for (var step : result.steps()) {
            Boolean previous = verdicts.put(step.multiplier(), step.sustained());
            assertThat(previous == null || previous == step.sustained())
                    .withFailMessage("rate %s was judged both ways in one search", step.multiplier())
                    .isTrue();
        }

        assertThat(result.sustained().multiplier()).isLessThan(result.strained().multiplier());
    }

    @Test
    void latencyIsJudgedAgainstTheHealthiestRateNotThePreviousStep() {
        CapacitySearch.Probe doubling = multiplier -> {
            long scheduled = (long) (100 * multiplier);
            return new Measurement(10 * multiplier, 10 * multiplier,
                    (long) (100 * MS * multiplier), scheduled, scheduled, "db");
        };

        CapacitySearch.Result result = CapacitySearch.search(doubling, 9, 3);

        assertThat(result.bracketed())
                .withFailMessage("latency growing without bound must eventually be called strain")
                .isTrue();
        assertThat(result.strain()).isEqualTo(Strain.LATENCY);

        assertThat(result.sustained().multiplier()).isLessThanOrEqualTo(3.0);
    }

    @Test
    void refinementStopsOnceTheBracketIsTighterThanTheRatesCanExpress() {
        Target target = new Target(5.0, 10 * MS);

        CapacitySearch.Result result = CapacitySearch.search(target, 40, 30);

        double gap = result.strained().multiplier() - result.sustained().multiplier();
        assertThat(gap / result.sustained().multiplier())
                .withFailMessage("the search kept refining past the point where two steps ask for "
                        + "the same load")
                .isGreaterThan(CapacitySearch.RESOLUTION * 0.5);

        assertThat(result.steps()).hasSizeLessThan(40);
    }

    @Test
    void shedLoadIsReportedInPreferenceToLatency() {
        Measurement shedAndSlow = new Measurement(100, 40, 5000 * MS, 1000, 400, "db");
        assertThat(CapacitySearch.judge(shedAndSlow, 10 * MS)).isEqualTo(Strain.SHED);
    }

    @Test
    void nothingIsCalledStrainedBeforeThereIsABaselineToCompareAgainst() {
        Measurement slowButDelivered = new Measurement(100, 100, 5000 * MS, 1000, 1000, "db");
        assertThat(CapacitySearch.judge(slowButDelivered, -1)).isEqualTo(Strain.NONE);
    }

    @Test
    void aMicrosecondBaselineDoesNotMakeEveryStepALimit() {
        Measurement slightlySlower = new Measurement(100, 100, 600_000, 1000, 1000, "redis");
        assertThat(CapacitySearch.judge(slightlySlower, 200_000)).isEqualTo(Strain.NONE);
    }
}
