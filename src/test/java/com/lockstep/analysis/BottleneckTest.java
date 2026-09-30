package com.lockstep.analysis;

import static org.assertj.core.api.Assertions.assertThat;

import com.lockstep.report.CliTables;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class BottleneckTest {

    private static Map<String, Integer> waits(Object... pairs) {
        Map<String, Integer> map = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            map.put((String) pairs[i], (Integer) pairs[i + 1]);
        }
        return map;
    }

    private static Bottleneck bottleneck(int samples, double meanActive, int maxActive,
            int plateau, int plateauSamples, long maxConnections, Map<String, Integer> byType) {
        return new Bottleneck(true, null, samples, meanActive, maxActive, plateau, plateauSamples,
                maxConnections, byType, byType);
    }

    // --- the verdict ----------------------------------------------------------------------------

    @Test
    void aHeldPlateauBelowTheServerLimitIsTheClientsPool() {
        // Backends running rather than waiting, pinned at 20 while the server allows 100: more
        // connections would admit more work, so the pool is genuinely the limit.
        var bottleneck = bottleneck(100, 19.6, 20, 20, 90, 100,
                waits(Bottleneck.RUNNING, 280, "IO", 20));

        assertThat(bottleneck.connectionCeilingReached()).isTrue();
        assertThat(bottleneck.verdict())
                .withFailMessage("backends working flat out and pinned at 20 while the server "
                        + "allows 100 means more connections would admit more work")
                .isEqualTo(Bottleneck.Verdict.CONNECTIONS);
    }

    @Test
    void aDominantWaitOutranksAFullPool() {
        // The measured case: a row lock held for a whole run. All four pooled backends busy for
        // 99% of samples, every one of them waiting on the lock.
        var bottleneck = bottleneck(139, 3.97, 4, 4, 138, 100, waits("Lock", 552));

        assertThat(bottleneck.connectionCeilingReached())
                .withFailMessage("the pool really was full; that part was never in doubt")
                .isTrue();
        assertThat(bottleneck.verdict())
                .withFailMessage("the backends were busy because they were blocked on a lock, so "
                        + "the full pool is the symptom and the lock the cause. Calling it a "
                        + "connection bottleneck sends the reader to raise the pool, which would "
                        + "only add more blocked backends.")
                .isEqualTo(Bottleneck.Verdict.LOCK_CONTENTION);
    }

    @Test
    void dominantStorageWaitsAlsoOutrankAFullPool() {
        var bottleneck = bottleneck(100, 3.9, 4, 4, 98, 100, waits("IO", 400));

        assertThat(bottleneck.connectionCeilingReached()).isTrue();
        assertThat(bottleneck.verdict())
                .withFailMessage("raising the pool cannot make the disk faster")
                .isEqualTo(Bottleneck.Verdict.STORAGE_IO);
    }

    @Test
    void aPlateauOfOneIsASingleThreadedClientNotACeiling() {
        var bottleneck = bottleneck(100, 1.0, 1, 1, 100, 100,
                waits(Bottleneck.RUNNING, 100));

        assertThat(bottleneck.connectionCeilingReached())
                .withFailMessage("one busy backend throughout is a serial client, not a pool "
                        + "that has run out")
                .isFalse();
        assertThat(bottleneck.verdict()).isEqualTo(Bottleneck.Verdict.CPU);
    }

    @Test
    void aBriefPlateauIsNotACeiling() {
        var bottleneck = bottleneck(100, 8.0, 20, 20, 20, 100,
                waits("IO", 500));

        assertThat(bottleneck.plateauShare()).isEqualTo(0.2);
        assertThat(bottleneck.connectionCeilingReached())
                .withFailMessage("touching a number briefly is not sitting against it")
                .isFalse();
        assertThat(bottleneck.verdict()).isEqualTo(Bottleneck.Verdict.STORAGE_IO);
    }

    @Test
    void aPlateauAtTheServersOwnLimitIsNotCalledAClientPool() {
        var bottleneck = bottleneck(100, 99.0, 100, 100, 95, 100,
                waits("IO", 500));

        assertThat(bottleneck.connectionCeilingReached())
                .withFailMessage("at max_connections the server is the limit, not the client's "
                        + "pool, and calling it a pool would point at the wrong thing")
                .isFalse();
    }

    @Test
    void dominantWaitsNameTheirBottleneck() {
        assertThat(bottleneck(50, 4.0, 6, 4, 10, 100, waits("IO", 80, "Lock", 10)).verdict())
                .isEqualTo(Bottleneck.Verdict.STORAGE_IO);
        assertThat(bottleneck(50, 4.0, 6, 4, 10, 100, waits("Lock", 80, "IO", 10)).verdict())
                .isEqualTo(Bottleneck.Verdict.LOCK_CONTENTION);
        assertThat(bottleneck(50, 4.0, 6, 4, 10, 100,
                waits(Bottleneck.RUNNING, 80, "IO", 10)).verdict())
                .isEqualTo(Bottleneck.Verdict.CPU);
    }

    @Test
    void noSingleDominantWaitMeansNothingWasSaturated() {
        var bottleneck = bottleneck(50, 3.0, 5, 3, 10, 100,
                waits("IO", 30, "Lock", 30, Bottleneck.RUNNING, 30, "LWLock", 30));

        assertThat(bottleneck.verdict())
                .withFailMessage("an even spread across wait types is not a bottleneck, and "
                        + "naming one would be invention")
                .isEqualTo(Bottleneck.Verdict.NOT_SATURATED);
    }

    @Test
    void anUnrecognisedWaitTypeIsNotForcedIntoACategory() {
        var bottleneck = bottleneck(50, 4.0, 6, 4, 10, 100, waits("Timeout", 90));

        assertThat(bottleneck.verdict()).isEqualTo(Bottleneck.Verdict.NOT_SATURATED);
    }

    @Test
    void tooFewSamplesIsUnknownRatherThanAGuess() {
        assertThat(bottleneck(2, 10.0, 20, 20, 2, 100, waits("IO", 40)).verdict())
                .isEqualTo(Bottleneck.Verdict.UNKNOWN);
        assertThat(Bottleneck.unavailable("no connection").verdict())
                .isEqualTo(Bottleneck.Verdict.UNKNOWN);
    }

    @Test
    void anIdleDatabaseIsNotSaturated() {
        assertThat(bottleneck(50, 0.0, 0, 0, 0, 100, Map.of()).verdict())
                .isEqualTo(Bottleneck.Verdict.NOT_SATURATED);
    }

    @Test
    void sharesAndTopEventsAreComputedOverTheTotal() {
        var bottleneck = bottleneck(100, 5.0, 8, 5, 60, 100,
                waits("IO", 60, "Lock", 30, Bottleneck.RUNNING, 10));

        assertThat(bottleneck.shareOfWaits("IO")).isEqualTo(0.6);
        assertThat(bottleneck.shareOfWaits("Lock")).isEqualTo(0.3);
        assertThat(bottleneck.shareOfWaits("nothing recorded")).isZero();
        assertThat(bottleneck.totalWaitObservations()).isEqualTo(100);
        assertThat(bottleneck.topWaitEvents(2)).hasSize(2);
        assertThat(bottleneck.topWaitEvents(2).get(0).getKey()).isEqualTo("IO");
    }

    // --- rendering ------------------------------------------------------------------------------

    @Test
    void nothingSampledRendersNothing() {
        assertThat(CliTables.bottleneckTable(null)).isEmpty();
    }

    @Test
    void anUnavailableSamplerSaysWhy() {
        String out = CliTables.bottleneckTable(
                Bottleneck.unavailable("could not connect to the target's database"));

        assertThat(out).contains("unavailable").contains("could not connect");
    }

    @Test
    void aConnectionCeilingIsNamedAsThePoolSize() {
        String out = CliTables.bottleneckTable(bottleneck(100, 19.6, 20, 20, 90, 100,
                waits(Bottleneck.RUNNING, 280)));

        assertThat(out).contains("bounded by connections");
        assertThat(out).contains("sat at 20");
        assertThat(out)
                .withFailMessage("the actionable part is that work was queued for a connection "
                        + "rather than for the database")
                .contains("waiting for a connection");
    }

    @Test
    void storageAndLockAndCpuEachGetTheirOwnSentence() {
        assertThat(CliTables.bottleneckTable(bottleneck(50, 4.0, 6, 4, 5, 100,
                waits("IO", 90)))).contains("bounded by storage").contains("no longer fits");
        assertThat(CliTables.bottleneckTable(bottleneck(50, 4.0, 6, 4, 5, 100,
                waits("Lock", 90)))).contains("lock contention").contains("same rows");
        assertThat(CliTables.bottleneckTable(bottleneck(50, 4.0, 6, 4, 5, 100,
                waits(Bottleneck.RUNNING, 90)))).contains("database's CPU");
    }

    @Test
    void anUnsaturatedRunSaysSoPlainly() {
        String out = CliTables.bottleneckTable(bottleneck(50, 1.0, 2, 1, 10, 100,
                waits("IO", 10, "Lock", 10, Bottleneck.RUNNING, 10, "LWLock", 10)));

        assertThat(out).contains("nothing was near a limit");
    }

    @Test
    void theSamplingCaveatsAreStated() {
        String out = CliTables.bottleneckTable(bottleneck(50, 4.0, 6, 4, 5, 100,
                waits("IO", 90)));

        assertThat(out)
                .withFailMessage("a sampled picture must not read as a census")
                .contains("statistical picture");
        assertThat(out).contains("every client of this database");
    }
}
