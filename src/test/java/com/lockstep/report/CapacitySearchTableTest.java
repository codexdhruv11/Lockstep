package com.lockstep.report;

import static org.assertj.core.api.Assertions.assertThat;

import com.lockstep.analysis.CapacitySearch;
import com.lockstep.analysis.CapacitySearch.Measurement;
import com.lockstep.analysis.CapacitySearch.Observation;
import com.lockstep.analysis.CapacitySearch.Strain;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class CapacitySearchTableTest {
    private static final long MS = 1_000_000L;

    private static Map<String, Integer> rates(String name, int rate) {
        Map<String, Integer> rates = new LinkedHashMap<>();
        rates.put(name, rate);
        return rates;
    }

    private static Observation held(double multiplier, double rate, long p99) {
        return new Observation(multiplier,
                new Measurement(rate, rate, p99, (long) rate * 10, (long) rate * 10, "db"),
                Strain.NONE);
    }

    private static Observation shed(double multiplier, double requested, double achieved, long p99) {
        return new Observation(multiplier,
                new Measurement(requested, achieved, p99, (long) requested * 10, (long) achieved * 10, "db"),
                Strain.SHED);
    }

    @Test
    void theVerdictNamesBothSidesOfTheBracketAndWhatGaveWay() {
        var sustained = held(1.0, 57, 42 * MS);
        var strained = shed(1.25, 71, 40, 3200 * MS);
        var result = new CapacitySearch.Result(List.of(sustained, strained),
                sustained, strained, Strain.SHED, false);

        String verdict = CliTables.capacitySearchVerdict(result, rates("db", 57), 20);

        assertThat(verdict).contains("sustains 57/s", "strains at 71/s");

        assertThat(verdict).contains("db could not be given the load", "400 of 710");
        assertThat(verdict).contains("p99 42ms");

        assertThat(verdict).contains("20 workers", "absolute number does not");
    }

    @Test
    void aLatencyBlowUpIsDescribedAsOneRatherThanAsShedding() {
        var sustained = held(1.0, 100, 20 * MS);
        var strained = new Observation(2.0,
                new Measurement(200, 200, 1800 * MS, 2000, 2000, "http"), Strain.LATENCY);
        var result = new CapacitySearch.Result(List.of(sustained, strained),
                sustained, strained, Strain.LATENCY, false);

        String verdict = CliTables.capacitySearchVerdict(result, rates("http", 100), 40);

        assertThat(verdict).contains("http held the rate but its p99 went to 1.8s, from 20ms");
        assertThat(verdict).doesNotContain("could not be given the load");
    }

    @Test
    void aTargetThatHeldEverythingIsNotGivenACeilingItNeverShowed() {
        var sustained = held(8.0, 480, 12 * MS);
        var result = new CapacitySearch.Result(List.of(held(1.0, 60, 10 * MS), sustained),
                sustained, null, Strain.NONE, true);

        String verdict = CliTables.capacitySearchVerdict(result, rates("db", 60), 20);

        assertThat(verdict).contains("not found", "480/s held, the highest rate tried");
        assertThat(verdict).doesNotContain("strains at");
    }

    @Test
    void aTargetThatStrainedAtEveryRateSaysToLowerTheStartingPoint() {
        var strained = shed(0.25, 15, 4, 900 * MS);
        var result = new CapacitySearch.Result(List.of(shed(1.0, 60, 5, 900 * MS), strained),
                null, strained, Strain.SHED, true);

        String verdict = CliTables.capacitySearchVerdict(result, rates("db", 60), 20);

        assertThat(verdict).contains("not found", "15/s, already strained", "lower the config's rate");
    }

    @Test
    void theStaircaseShowsWhatWasDeliveredNotWhatWasAskedFor() {
        var result = new CapacitySearch.Result(
                List.of(held(1.0, 60, 10 * MS), shed(2.0, 120, 57, 3600 * MS)),
                held(1.0, 60, 10 * MS), shed(2.0, 120, 57, 3600 * MS), Strain.SHED, false);

        String[] lines = CliTables.capacitySearchTable(result, rates("db", 60))
                .stripTrailing().split("\n");

        assertThat(lines[0]).isEqualTo("capacity search");
        assertThat(lines[1]).contains("RATE", "DELIVERED", "P99", "VERDICT");
        assertThat(lines[2]).contains("60/s", "held");

        assertThat(lines[3]).contains("120/s", "57.0/s", "strained");
        assertThat(lines[3]).contains("47.5%");
    }

    @Test
    void severalRunnersAreNamedIndividuallyRatherThanAsAMultiplier() {
        Map<String, Integer> rates = new LinkedHashMap<>();
        rates.put("http", 10);
        rates.put("db", 5);

        assertThat(CliTables.describeRates(rates, 4.0)).isEqualTo("http 40/s + db 20/s");
        assertThat(CliTables.describeRates(rates, 1.0)).isEqualTo("http 10/s + db 5/s");
        assertThat(CliTables.describeRates(rates("db", 60), 0.5)).isEqualTo("30/s");
    }

    @Test
    void aRateNeverRoundsDownToNothing() {
        assertThat(CliTables.describeRates(rates("db", 1), 0.125)).isEqualTo("1/s");
    }
}
