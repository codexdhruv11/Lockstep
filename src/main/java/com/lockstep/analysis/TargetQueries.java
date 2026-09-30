package com.lockstep.analysis;

import java.util.List;

/**
 * How many database statements the target executed to serve each request.
 *
 * <p>Latency tools cannot see this. An endpoint that issues one statement and an endpoint that
 * issues forty-seven can have the same p99 on a warm cache and diverge completely under load, and
 * no amount of latency measurement distinguishes them. The statement count does.
 *
 * <h2>Why the statement count, and not scan counts</h2>
 *
 * <p>The obvious approach is to watch {@code pg_stat_user_tables.idx_scan} and call a table
 * scanned many times per request an N+1. That does not work, and the first version of this
 * feature was wrong because of it. {@code idx_scan} counts executions of an index-scan
 * <em>plan node</em>, not statements: a single {@code JOIN} planned as a nested loop scans the
 * inner table once per outer row. Measured here — a two-table join returning ten rows registered
 * twelve index scans on the inner table per request, while the hand-written loop over the same
 * ten rows registered eleven. Indistinguishable, and in that run the join looked worse.
 *
 * <p>What separates them is the number of statements the application sent, which is
 * {@code pg_stat_statements}. Without that extension this class reports scan counts as context
 * and says plainly that it cannot tell a nested loop from a loop in application code.
 *
 * <h2>What the numbers include</h2>
 *
 * <p>The counters are per-database, so the target's own background jobs, other clients and
 * autovacuum are in them. They are also a <strong>lower bound</strong>: a backend reports at
 * transaction end and no more than once a second, and a connection that goes idle in the target's
 * pool has no next transaction, so its final second is never reported. Lockstep can force its own
 * connections to flush and has no way to reach the target's.
 */
public record TargetQueries(
        boolean available,
        String unavailableReason,
        long requests,
        long statementCalls,
        boolean statementCallsAvailable,
        long rowsRead,
        List<Relation> relations) {

    /**
     * Statements per request at or above this is worth pointing at. A request that sends three or
     * more statements is often a loop that should have been one query — though a legitimately
     * multi-step endpoint will also land here, so the report suggests rather than accuses.
     */
    public static final double SUSPICIOUS_STATEMENTS_PER_REQUEST = 3.0;

    public TargetQueries {
        relations = relations == null ? List.of() : List.copyOf(relations);
    }

    public static TargetQueries unavailable(String reason) {
        return new TargetQueries(false, reason, 0, 0, false, 0, List.of());
    }

    public record Relation(String name, long sequentialScans, long indexScans, long rowsRead) {
        /**
         * Plan-node executions against this table, not statements. A nested loop inflates this
         * by the number of outer rows.
         */
        public long scans() {
            return sequentialScans + indexScans;
        }
    }

    public boolean hasObservations() {
        return relations.stream().anyMatch(relation -> relation.scans() > 0);
    }

    /** Negative when pg_stat_statements is not installed — the only sound N+1 signal. */
    public double statementsPerRequest() {
        return requests <= 0 || !statementCallsAvailable ? -1 : (double) statementCalls / requests;
    }

    /** True only when statements were actually counted and the count is high. */
    public boolean manyStatementsPerRequest() {
        double perRequest = statementsPerRequest();
        return perRequest >= SUSPICIOUS_STATEMENTS_PER_REQUEST;
    }

    /** Whether an N+1 can be ruled on at all. Without statement counts it cannot. */
    public boolean canJudgeStatementCount() {
        return statementCallsAvailable && requests > 0;
    }

    public double scansPerRequest(Relation relation) {
        return requests <= 0 ? 0 : (double) relation.scans() / requests;
    }

    public double totalScansPerRequest() {
        long total = 0;
        for (Relation relation : relations) {
            total += relation.scans();
        }
        return requests <= 0 ? 0 : (double) total / requests;
    }

    public double rowsPerRequest() {
        return requests <= 0 ? 0 : (double) rowsRead / requests;
    }

    public List<Relation> byScansDescending() {
        return relations.stream()
                .filter(relation -> relation.scans() > 0)
                .sorted((a, b) -> Long.compare(b.scans(), a.scans()))
                .toList();
    }
}
