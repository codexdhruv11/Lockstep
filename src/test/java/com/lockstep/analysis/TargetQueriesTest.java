package com.lockstep.analysis;

import static org.assertj.core.api.Assertions.assertThat;

import com.lockstep.report.CliTables;
import java.util.List;
import org.junit.jupiter.api.Test;

final class TargetQueriesTest {

    private static TargetQueries.Relation relation(String name, long seq, long idx, long rows) {
        return new TargetQueries.Relation(name, seq, idx, rows);
    }

    private static TargetQueries queries(long requests, long calls, boolean callsAvailable,
            long rowsReturned, TargetQueries.Relation... relations) {
        return new TargetQueries(true, null, requests, calls, callsAvailable, rowsReturned,
                List.of(relations));
    }

    // --- arithmetic -----------------------------------------------------------------------------

    @Test
    void scansPerRequestDividesByWhatTheLoadActuallyDid() {
        var queries = queries(100, 0, false, 0,
                relation("orders", 100, 0, 1_000),
                relation("customers", 0, 1_100, 1_100));

        assertThat(queries.scansPerRequest(queries.relations().get(0))).isEqualTo(1.0);
        assertThat(queries.scansPerRequest(queries.relations().get(1))).isEqualTo(11.0);
        assertThat(queries.totalScansPerRequest()).isEqualTo(12.0);
    }

    @Test
    void aRunThatServedNothingDividesByNothing() {
        var queries = queries(0, 500, true, 100, relation("orders", 10, 0, 10));

        assertThat(queries.totalScansPerRequest()).isZero();
        assertThat(queries.rowsPerRequest()).isZero();
        assertThat(queries.statementsPerRequest())
                .withFailMessage("with no requests there is no per-request figure, and 0 would "
                        + "read as 'the target ran no queries'")
                .isNegative();
    }

    @Test
    void statementCountsAreUnknownRatherThanZeroWithoutTheExtension() {
        assertThat(queries(100, 0, false, 0, relation("orders", 100, 0, 0)).statementsPerRequest())
                .withFailMessage("pg_stat_statements is usually absent; reporting 0 statements "
                        + "per request would be a measurement claim")
                .isNegative();
        assertThat(queries(100, 1_200, true, 0, relation("orders", 100, 0, 0)).statementsPerRequest())
                .isEqualTo(12.0);
    }

    @Test
    void rowsPerRequestComesFromRowsRead() {
        assertThat(queries(100, 0, false, 418_200, relation("orders", 100, 0, 0)).rowsPerRequest())
                .isEqualTo(4_182.0);
    }

    @Test
    void relationsTheTargetNeverTouchedAreLeftOut() {
        var queries = queries(100, 0, false, 0,
                relation("busy", 100, 0, 0), relation("untouched", 0, 0, 0));

        assertThat(queries.byScansDescending())
                .extracting(TargetQueries.Relation::name)
                .containsExactly("busy");
        assertThat(queries.hasObservations()).isTrue();
        assertThat(queries(100, 0, false, 0, relation("untouched", 0, 0, 0)).hasObservations())
                .isFalse();
    }

    @Test
    void manyStatementsPerRequestIsTheSignal() {
        assertThat(queries(100, 1_100, true, 0, relation("customers", 0, 1_100, 0))
                .manyStatementsPerRequest()).isTrue();
        assertThat(queries(100, 100, true, 0, relation("customers", 0, 1_100, 0))
                .manyStatementsPerRequest())
                .withFailMessage("one statement a request is not an N+1 no matter how many index "
                        + "scans its plan performs — a nested loop does that legitimately")
                .isFalse();
    }

    @Test
    void highScanCountsAloneNeverConvictWithoutStatementCounts() {
        // The measured case: a join returning ten rows registered twelve index scans on the inner
        // table per request, the same shape as a ten-iteration application loop.
        var queries = queries(100, 0, false, 0, relation("customers", 0, 1_200, 0));

        assertThat(queries.scansPerRequest(queries.relations().get(0))).isEqualTo(12.0);
        assertThat(queries.canJudgeStatementCount())
                .withFailMessage("without pg_stat_statements there is no sound basis for the claim")
                .isFalse();
        assertThat(queries.manyStatementsPerRequest()).isFalse();
    }

    // --- rendering ------------------------------------------------------------------------------

    @Test
    void nothingObservedRendersNothing() {
        assertThat(CliTables.targetQueriesTable(null)).isEmpty();
    }

    @Test
    void anUnavailableObserverSaysWhy() {
        String out = CliTables.targetQueriesTable(
                TargetQueries.unavailable("could not connect to the target's database"));

        assertThat(out).contains("unavailable").contains("could not connect");
        assertThat(out).doesNotContain("SCANS/REQ");
    }

    @Test
    void anIdleTargetSaysSoRatherThanLookingBroken() {
        String out = CliTables.targetQueriesTable(
                queries(100, 0, false, 0, relation("orders", 0, 0, 0)));

        assertThat(out).contains("ran no queries");
        assertThat(out).doesNotContain("unavailable");
    }

    @Test
    void manyStatementsPerRequestIsCalledOut() {
        String out = CliTables.targetQueriesTable(queries(100, 1_200, true, 110_000,
                relation("orders", 100, 0, 1_000),
                relation("customers", 0, 1_100, 110_000)));

        assertThat(out).contains("12.0 statements per request");
        assertThat(out).contains("sent 12.0 statements for every request");
        assertThat(out)
                .withFailMessage("a legitimately multi-step endpoint looks identical, so the "
                        + "wording must suggest rather than accuse")
                .contains("genuinely multi-step endpoint looks the same");
    }

    @Test
    void oneStatementPerRequestIsNotAccusedHoweverManyScansItsPlanDid() {
        String out = CliTables.targetQueriesTable(queries(100, 100, true, 110_000,
                relation("customers", 0, 1_200, 110_000)));

        assertThat(out).contains("1.0 statements per request");
        assertThat(out)
                .withFailMessage("twelve index scans from one statement is a nested loop, which "
                        + "is a plan choice and not a defect")
                .doesNotContain("for every request");
    }

    @Test
    void withoutTheExtensionTheReportRefusesToJudge() {
        String out = CliTables.targetQueriesTable(
                queries(100, 0, false, 500, relation("orders", 100, 0, 500)));

        assertThat(out).contains("pg_stat_statements is not installed");
        assertThat(out)
                .withFailMessage("the scan counts cannot distinguish a nested loop from an "
                        + "application loop, and the report has to say so")
                .contains("once per outer row");
        assertThat(out).doesNotContain("0.0 statements per request");
    }

    @Test
    void theLowerBoundAndContaminationCaveatsAreAlwaysStated() {
        String out = CliTables.targetQueriesTable(
                queries(100, 0, false, 500, relation("orders", 100, 0, 500)));

        assertThat(out)
                .withFailMessage("the target's own background jobs are counted here too, and its "
                        + "idle pooled connections may never report their last second")
                .contains("lower bound");
        assertThat(out).contains("background jobs");
    }
}
