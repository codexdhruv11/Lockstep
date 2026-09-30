package com.lockstep.analysis;

import static org.assertj.core.api.Assertions.assertThat;

import com.lockstep.report.CliTables;
import java.util.List;
import org.junit.jupiter.api.Test;

final class WriteAmplificationTest {

    private static final long KB = 1024;

    private static WriteAmplification.Relation relation(long inserts, long updates, long deletes,
            long hotUpdates, int indexes, int partial, long bytesPerRow) {
        return new WriteAmplification.Relation("signals", inserts, updates, deletes, hotUpdates,
                indexes, partial, bytesPerRow);
    }

    private static WriteAmplification amplification(WriteAmplification.Relation relation,
            long walBytes, long walRecords, long fpi, long buffersFull, long syncs,
            long syncMicros, boolean timingTracked) {
        return new WriteAmplification(true, null, walBytes, walRecords, fpi, buffersFull, syncs,
                syncMicros, timingTracked, List.of(relation));
    }

    // --- arithmetic -----------------------------------------------------------------------------

    @Test
    void perWriteFiguresDivideByEveryLogicalChange() {
        var relation = relation(600, 300, 100, 0, 3, 0, 500);
        var amp = amplification(relation, 1_000 * KB, 4_000, 100, 0, 200, 0, false);

        assertThat(relation.logicalWrites()).isEqualTo(1_000);
        assertThat(amp.totalLogicalWrites()).isEqualTo(1_000);
        assertThat(amp.walBytesPerWrite()).isEqualTo(KB);
        assertThat(amp.walRecordsPerWrite()).isEqualTo(4.0);
        assertThat(amp.fullPageImagesPerWrite()).isEqualTo(0.1);
        assertThat(amp.syncsPerWrite()).isEqualTo(0.2);
    }

    @Test
    void aReadOnlyRunDividesByNothing() {
        var amp = amplification(relation(0, 0, 0, 0, 3, 0, 500), 0, 0, 0, 0, 0, 0, false);

        assertThat(amp.hasWrites()).isFalse();
        assertThat(amp.walBytesPerWrite()).isZero();
        assertThat(amp.walRecordsPerWrite()).isZero();
        assertThat(amp.syncsPerWrite()).isZero();
        assertThat(amp.amplificationFactor())
                .withFailMessage("with no writes there is no amplification to report, and 0 would "
                        + "read as 'the log wrote nothing extra'")
                .isNegative();
    }

    @Test
    void theAmplificationFactorComparesLogBytesToTheRow() {
        // 1,000 writes, 2MB of WAL = 2KB a write, against a 500 byte row: 4.1x.
        var amp = amplification(relation(1_000, 0, 0, 0, 3, 0, 500),
                2_000 * KB, 4_000, 0, 0, 0, 0, false);

        assertThat(amp.amplificationFactor()).isCloseTo(4.096,
                org.assertj.core.data.Offset.offset(0.01));
    }

    @Test
    void anUnknownRowSizeMeansNoFactorRatherThanZero() {
        var amp = amplification(relation(1_000, 0, 0, 0, 3, 0, 0),
                2_000 * KB, 4_000, 0, 0, 0, 0, false);

        assertThat(amp.amplificationFactor()).isNegative();
    }

    @Test
    void theFactorUsesTheRelationThatTookTheMostWrites() {
        var amp = new WriteAmplification(true, null, 1_000 * KB, 2_000, 0, 0, 0, 0, false,
                List.of(new WriteAmplification.Relation("tiny", 10, 0, 0, 0, 1, 0, 10_000),
                        new WriteAmplification.Relation("busy", 990, 0, 0, 0, 1, 0, 500)));

        // 1,000 writes, 1,024,000 bytes of WAL = 1,024 each; against `busy`'s 500 bytes, 2.048x.
        assertThat(amp.amplificationFactor()).isCloseTo(2.048,
                org.assertj.core.data.Offset.offset(0.01));
    }

    @Test
    void hotUpdateFractionIsUnknownWhenNothingWasUpdated() {
        assertThat(relation(100, 0, 0, 0, 3, 0, 500).hotUpdateFraction())
                .withFailMessage("0%% HOT would read as 'every update rewrote every index'")
                .isNegative();
        assertThat(relation(0, 100, 0, 40, 3, 0, 500).hotUpdateFraction()).isEqualTo(0.4);
    }

    @Test
    void aTableIsHeavilyIndexedAtTenIndexes() {
        assertThat(relation(1, 0, 0, 0, 9, 0, 500).heavilyIndexed()).isFalse();
        assertThat(relation(1, 0, 0, 0, 10, 0, 500).heavilyIndexed()).isTrue();
        assertThat(relation(1, 0, 0, 0, 30, 7, 500).heavilyIndexed()).isTrue();
    }

    @Test
    void fullPageImagesAreFlaggedOnlyWhenTheyAreALargeShare() {
        assertThat(amplification(relation(100, 0, 0, 0, 1, 0, 500),
                KB, 1_000, 100, 0, 0, 0, false).fullPageImagesDominate()).isFalse();
        assertThat(amplification(relation(100, 0, 0, 0, 1, 0, 500),
                KB, 1_000, 400, 0, 0, 0, false).fullPageImagesDominate()).isTrue();
    }

    @Test
    void relationsWithNoWritesAreLeftOutOfTheRanking() {
        var amp = new WriteAmplification(true, null, KB, 10, 0, 0, 0, 0, false,
                List.of(new WriteAmplification.Relation("quiet", 0, 0, 0, 0, 3, 0, 500),
                        new WriteAmplification.Relation("busy", 100, 0, 0, 0, 3, 0, 500)));

        assertThat(amp.byWritesDescending())
                .extracting(WriteAmplification.Relation::name)
                .containsExactly("busy");
    }

    // --- measured growth ------------------------------------------------------------------------

    @Test
    void perRowSizeIsMeasuredFromGrowthRatherThanTakenFromStaleStatistics() {
        // 1,000 inserts grew the heap by 500KB and the indexes by 1MB. reltuples says 0 because
        // the table was analysed while empty, which is the common case for a fresh table.
        var relation = new WriteAmplification.Relation("signals", 1_000, 0, 0, 0, 13, 0,
                0, 500 * KB, 1_000 * KB);

        assertThat(relation.onDiskBytesPerInsert()).isEqualTo(512);
        assertThat(relation.indexBytesPerInsert()).isEqualTo(1_024);
        assertThat(relation.effectiveBytesPerRow())
                .withFailMessage("a stale reltuples of 0 must not win over a real measurement")
                .isEqualTo(512);
        assertThat(relation.indexTaxRatio()).isEqualTo(2.0);
    }

    @Test
    void theStatisticsEstimateIsTheFallbackWhenNothingGrew() {
        var relation = new WriteAmplification.Relation("signals", 0, 100, 0, 0, 13, 0,
                663, 0, 0);

        assertThat(relation.onDiskBytesPerInsert()).isZero();
        assertThat(relation.effectiveBytesPerRow()).isEqualTo(663);
        assertThat(relation.indexTaxRatio())
                .withFailMessage("with no measured growth the tax is unknown, and 0 would read as "
                        + "'the indexes cost nothing'")
                .isNegative();
    }

    @Test
    void theAmplificationFactorPrefersMeasuredGrowth() {
        var amp = new WriteAmplification(true, null, 2_000 * KB, 4_000, 0, 0, 0, 0, false,
                List.of(new WriteAmplification.Relation("signals", 1_000, 0, 0, 0, 13, 0,
                        99_999, 500 * KB, 0)));

        // 2,048,000 WAL bytes / 1,000 writes = 2,048 each, against the measured 512 per row.
        assertThat(amp.amplificationFactor()).isCloseTo(4.0,
                org.assertj.core.data.Offset.offset(0.01));
    }

    // --- rendering ------------------------------------------------------------------------------

    @Test
    void noMeasurementRendersNothing() {
        assertThat(CliTables.writeAmplificationTable(null)).isEmpty();
    }

    @Test
    void aReadOnlyRunRendersNothingRatherThanASectionOfZeroes() {
        String out = CliTables.writeAmplificationTable(
                amplification(relation(0, 0, 0, 0, 3, 0, 500), 0, 0, 0, 0, 0, 0, false));

        assertThat(out)
                .withFailMessage("a read-only run has nothing to amplify; a table of zeroes would "
                        + "suggest the writes were free rather than absent")
                .isEmpty();
    }

    @Test
    void anUnavailableProbeSaysWhy() {
        String out = CliTables.writeAmplificationTable(
                WriteAmplification.unavailable("driver \"sqlite\" has no pg_stat_wal"));

        assertThat(out).contains("unavailable").contains("sqlite");
        assertThat(out).doesNotContain("INSERTS");
    }

    @Test
    void theHeadlineIsTheMultipleOfTheRowSize() {
        String out = CliTables.writeAmplificationTable(
                amplification(relation(1_000, 0, 0, 0, 3, 0, 500),
                        2_000 * KB, 4_000, 0, 0, 100, 0, false));

        assertThat(out).contains("1,000 logical writes");
        assertThat(out)
                .withFailMessage("the multiple is the whole point of the feature")
                .contains("the log carries 4.1× the row's own size");
    }

    @Test
    void aHeavilyIndexedTableHasItsIndexWritesSpelledOut() {
        String out = CliTables.writeAmplificationTable(
                amplification(relation(12_000, 0, 0, 0, 30, 7, 663),
                        40_000 * KB, 50_000, 0, 0, 1_200, 0, false));

        assertThat(out).contains("carries 30 indexes");
        assertThat(out)
                .withFailMessage("12,000 inserts against 30 indexes is 360,000 index entries, and "
                        + "that number is the argument for dropping some")
                .contains("360,000 index entries");
        assertThat(out).contains("partial index's predicate does not match");
    }

    @Test
    void aLowHotUpdateRateOnAnIndexedTableIsCalledOut() {
        String out = CliTables.writeAmplificationTable(
                amplification(relation(0, 1_000, 0, 100, 30, 0, 663),
                        40_000 * KB, 50_000, 0, 0, 100, 0, false));

        assertThat(out).contains("only 10.0% of signals updates were HOT");
        assertThat(out).contains("rewrote every index entry");
    }

    @Test
    void aHealthyHotUpdateRateIsNotComplainedAbout() {
        String out = CliTables.writeAmplificationTable(
                amplification(relation(0, 1_000, 0, 900, 30, 0, 663),
                        40_000 * KB, 50_000, 0, 0, 100, 0, false));

        assertThat(out).doesNotContain("were HOT");
    }

    @Test
    void untrackedFsyncTimeSaysSoInsteadOfReportingZero() {
        String out = CliTables.writeAmplificationTable(
                amplification(relation(1_000, 0, 0, 0, 3, 0, 500),
                        KB * 1_000, 4_000, 0, 0, 100, 0, false));

        assertThat(out).contains("track_wal_io_timing is off");
        assertThat(out)
                .withFailMessage("reporting 0 spent in fsync would be a measurement claim, and "
                        + "the timing was simply never collected")
                .doesNotContain("spent in fsync");
    }

    @Test
    void trackedFsyncTimeIsReported() {
        String out = CliTables.writeAmplificationTable(
                amplification(relation(1_000, 0, 0, 0, 3, 0, 500),
                        KB * 1_000, 4_000, 0, 0, 100, 250_000, true));

        assertThat(out).contains("spent in fsync");
        assertThat(out).doesNotContain("track_wal_io_timing is off");
    }

    @Test
    void fullWalBuffersAreReportedAsAConfigurationProblem() {
        String out = CliTables.writeAmplificationTable(
                amplification(relation(1_000, 0, 0, 0, 3, 0, 500),
                        KB * 1_000, 4_000, 0, 42, 100, 0, false));

        assertThat(out).contains("WAL buffers filled").contains("42");
        assertThat(out).contains("wal_buffers is too small");
    }

    @Test
    void theRowSizeColumnFallsBackToMeasuredGrowth() {
        // reltuples is 0 (analysed while empty) but the heap grew 500KB over 1,000 inserts.
        String out = CliTables.writeAmplificationTable(new WriteAmplification(true, null,
                2_000 * KB, 4_000, 0, 0, 100, 0, false,
                List.of(new WriteAmplification.Relation("signals", 1_000, 0, 0, 0, 13, 0,
                        0, 500 * KB, 1_000 * KB))));

        assertThat(out)
                .withFailMessage("the column showed \"?\" while the measured growth was right "
                        + "there: %s", out)
                .contains("512B");
    }

    @Test
    void theIndexTaxIsSpelledOutInBytes() {
        String out = CliTables.writeAmplificationTable(new WriteAmplification(true, null,
                2_000 * KB, 4_000, 0, 0, 100, 0, false,
                List.of(new WriteAmplification.Relation("signals", 1_000, 0, 0, 0, 13, 0,
                        0, 500 * KB, 1_000 * KB))));

        assertThat(out).contains("grew 500KB of table and 1000KB of indexes");
        assertThat(out)
                .withFailMessage("the ratio of index bytes to row bytes is the argument for "
                        + "dropping an index, expressed on disk rather than in the log")
                .contains("indexes cost 2.0× the data they point at");
    }

    @Test
    void theClusterWideCaveatIsAlwaysStated() {
        String out = CliTables.writeAmplificationTable(
                amplification(relation(1_000, 0, 0, 0, 3, 0, 500),
                        KB * 1_000, 4_000, 0, 0, 100, 0, false));

        assertThat(out)
                .withFailMessage("pg_stat_wal covers the whole server; not saying so invites the "
                        + "bytes being read as this run's alone")
                .contains("cluster-wide");
        assertThat(out).contains("upper bound");
    }
}
