package com.lockstep.analysis;

import static org.assertj.core.api.Assertions.assertThat;

import com.lockstep.report.CliTables;
import java.util.List;
import org.junit.jupiter.api.Test;

final class ResourceAccountingTest {

    private static final long KB = 1024;
    private static final long MB = 1024 * KB;
    private static final long BLOCK = 8 * KB;
    private static final long SECOND = 1_000_000_000L;

    private static ResourceAccounting.Relation relation(long rows, long tableBytes,
            long indexBytes, long hit, long read) {
        return new ResourceAccounting.Relation("signals", rows, tableBytes, indexBytes, hit, read);
    }

    private static ResourceAccounting accounting(ResourceAccounting.Relation relation,
            long sharedBuffers, long requests) {
        return new ResourceAccounting(true, null, List.of(relation), sharedBuffers, BLOCK,
                0, requests, 10 * SECOND);
    }

    // --- arithmetic ---------------------------------------------------------------------------

    @Test
    void bytesPerRowDividesTheTableByItsRows() {
        var relation = relation(1_000, 4 * MB, MB, 0, 0);

        assertThat(relation.bytesPerRow()).isEqualTo(4 * MB / 1_000);
        assertThat(relation.totalBytes()).isEqualTo(5 * MB);
    }

    @Test
    void anUnknownRowCountDoesNotDivideByZero() {
        assertThat(relation(0, 4 * MB, 0, 0, 0).bytesPerRow()).isZero();
        assertThat(relation(-1, 4 * MB, 0, 0, 0).bytesPerRow()).isZero();
    }

    @Test
    void blocksPerRequestIsTheDeltaDividedByWhatActuallyRan() {
        var accounting = accounting(relation(1_000, 4 * MB, 0, 900, 100), 128 * MB, 10);

        assertThat(accounting.blocksPerRequest(accounting.relations().get(0))).isEqualTo(100);
        assertThat(accounting.bytesPerRequest(accounting.relations().get(0)))
                .isEqualTo(100 * BLOCK);
    }

    @Test
    void aRunThatExecutedNothingReportsZeroRatherThanInfinity() {
        var accounting = accounting(relation(1_000, 4 * MB, 0, 900, 100), 128 * MB, 0);

        assertThat(accounting.blocksPerRequest(accounting.relations().get(0))).isZero();
        assertThat(accounting.bytesPerRequest(accounting.relations().get(0))).isZero();
        assertThat(accounting.generatorBytesPerRequest()).isZero();
    }

    @Test
    void hitRatioIsHitsOverEverythingTouchedAndIsNegativeWhenNothingWasTouched() {
        assertThat(relation(1, MB, 0, 99, 1).hitRatio()).isEqualTo(0.99);
        assertThat(relation(1, MB, 0, 0, 0).hitRatio())
                .withFailMessage("an untouched relation must not report a 0%% hit ratio, which "
                        + "would read as a total cache miss")
                .isNegative();
    }

    @Test
    void theSharedBuffersCrossoverIsBuffersDividedByRowSize() {
        // 4KB per row, 128MB of buffers: 32,768 rows fit.
        var accounting = accounting(relation(1_000, 1_000 * 4 * KB, 0, 0, 0), 128 * MB, 1);

        assertThat(accounting.rowsAtSharedBuffersLimit(accounting.relations().get(0)))
                .isEqualTo(128 * MB / (4 * KB));
    }

    @Test
    void theCrossoverIsUnknownRatherThanZeroWhenAnInputIsMissing() {
        assertThat(accounting(relation(0, MB, 0, 0, 0), 128 * MB, 1)
                .rowsAtSharedBuffersLimit(relation(0, MB, 0, 0, 0)))
                .isEqualTo(-1);
        assertThat(accounting(relation(10, MB, 0, 0, 0), 0, 1)
                .rowsAtSharedBuffersLimit(relation(10, MB, 0, 0, 0)))
                .isEqualTo(-1);
    }

    @Test
    void aTableLargerThanSharedBuffersIsFlagged() {
        assertThat(accounting(relation(10, 200 * MB, 0, 0, 0), 128 * MB, 1)
                .exceedsSharedBuffers(relation(10, 200 * MB, 0, 0, 0))).isTrue();
        assertThat(accounting(relation(10, 10 * MB, 0, 0, 0), 128 * MB, 1)
                .exceedsSharedBuffers(relation(10, 10 * MB, 0, 0, 0))).isFalse();
    }

    @Test
    void readingTheWholeTableEveryRequestIsDetected() {
        // 8MB table, 1,024 blocks touched per request = 8MB per request.
        var walks = accounting(relation(1_000, 8 * MB, 0, 1_024, 0), 128 * MB, 1);
        assertThat(walks.readsWholeTablePerRequest(walks.relations().get(0)))
                .withFailMessage("a request that reads as many bytes as the table holds is the "
                        + "signal this feature exists to surface")
                .isTrue();

        // Same table, 2 blocks per request: an index lookup.
        var seeks = accounting(relation(1_000, 8 * MB, 0, 2, 0), 128 * MB, 1);
        assertThat(seeks.readsWholeTablePerRequest(seeks.relations().get(0))).isFalse();
    }

    @Test
    void relationsAreRankedByBlocksTouchedNotBySize() {
        var small = new ResourceAccounting.Relation("hot_small", 10, KB, 0, 5_000, 0);
        var large = new ResourceAccounting.Relation("cold_large", 10, 900 * MB, 0, 3, 0);
        var accounting = new ResourceAccounting(true, null, List.of(large, small),
                128 * MB, BLOCK, 0, 10, 10 * SECOND);

        assertThat(accounting.byBytesTouchedDescending())
                .extracting(ResourceAccounting.Relation::name)
                .withFailMessage("ranking by table size would bury the relation the run actually hammered")
                .containsExactly("hot_small", "cold_large");
    }

    // --- rendering ---------------------------------------------------------------------------

    @Test
    void noAccountingAtAllRendersNothing() {
        assertThat(CliTables.resourceTable(null)).isEmpty();
    }

    @Test
    void anUnavailableProbeSaysWhyInsteadOfShowingZeroes() {
        String out = CliTables.resourceTable(
                ResourceAccounting.unavailable("driver \"sqlite\" has no statistics views"));

        assertThat(out).contains("unavailable").contains("sqlite");
        assertThat(out)
                .withFailMessage("an unavailable probe must not render a table of zeroes that "
                        + "reads as a measurement")
                .doesNotContain("BYTES/ROW");
    }

    @Test
    void anAvailableProbeThatTouchedNothingSaysSoRatherThanLookingBroken() {
        String out = CliTables.resourceTable(new ResourceAccounting(true, null, List.of(),
                128 * MB, BLOCK, 0, 100, 10 * SECOND));

        assertThat(out).contains("no relation's block counters moved");
        assertThat(out).doesNotContain("unavailable");
    }

    @Test
    void theTableReportsTheMeasurementsAndTheWholeTableWarning() {
        String out = CliTables.resourceTable(
                accounting(relation(69_720, 182 * MB, 96 * MB, 23_000, 400), 128 * MB, 1));

        assertThat(out).contains("signals").contains("69,720").contains("182MB").contains("96MB");
        assertThat(out)
                .withFailMessage("a table bigger than shared_buffers is the headline finding")
                .contains("already exceeds it");
        assertThat(out).contains("every request walks the whole thing");
    }

    @Test
    void bufferTrafficPerSecondMultipliesPerRequestByTheAchievedRate() {
        // 600 requests over 10s = 60/s; 128 blocks a request = 1MB a request.
        var accounting = new ResourceAccounting(true, null,
                List.of(relation(1_000, 8 * MB, 0, 128 * 600, 0)), 128 * MB, BLOCK,
                0, 600, 10 * SECOND);
        var relation = accounting.relations().get(0);

        assertThat(accounting.achievedRatePerSecond()).isEqualTo(60.0);
        assertThat(accounting.bytesPerRequest(relation)).isEqualTo(MB);
        assertThat(accounting.bufferBytesPerSecond(relation)).isEqualTo(60.0 * MB);
    }

    @Test
    void bufferTrafficIsZeroRatherThanInfiniteForAnEmptyRun() {
        var accounting = new ResourceAccounting(true, null,
                List.of(relation(1_000, MB, 0, 0, 0)), 128 * MB, BLOCK, 0, 0, 0);

        assertThat(accounting.achievedRatePerSecond()).isZero();
        assertThat(accounting.bufferBytesPerSecond(accounting.relations().get(0))).isZero();
    }

    @Test
    void theWholeTableWarningQuotesTheBandwidthItCosts() {
        String out = CliTables.resourceTable(new ResourceAccounting(true, null,
                List.of(relation(69_720, 44 * MB, 28 * MB, 5_468 * 600, 0)),
                128 * MB, BLOCK, 0, 600, 15 * SECOND));

        assertThat(out).contains("every request walks the whole thing");
        assertThat(out)
                .withFailMessage("megabytes per request only becomes a capacity argument once "
                        + "it is multiplied by the rate")
                .contains("of buffer traffic");
    }

    @Test
    void theGeneratorsOwnAllocationIsLabelledAsItsOwn() {
        var accounting = new ResourceAccounting(true, null,
                List.of(relation(1_000, MB, 0, 100, 0)), 128 * MB, BLOCK,
                41 * KB * 450, 450, 10 * SECOND);

        String out = CliTables.resourceTable(accounting);

        assertThat(out).contains("41KB").contains("per request");
        assertThat(out)
                .withFailMessage("the generator's allocation must never read as the target's, or "
                        + "it will be used to size the wrong heap")
                .contains("its own cost, not the target's");
    }

    @Test
    void theReportStatesThatTheCountersAreNotExclusiveToThisRun() {
        String out = CliTables.resourceTable(
                accounting(relation(1_000, MB, 0, 100, 0), 128 * MB, 10));

        assertThat(out)
                .withFailMessage("per-database counters include other clients; not saying so "
                        + "invites the numbers being read as exclusively ours")
                .contains("per-database");
        assertThat(out).contains("planner's estimate");
    }
}
