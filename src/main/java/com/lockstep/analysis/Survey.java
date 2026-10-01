package com.lockstep.analysis;

import java.util.List;

/**
 * What the target's own counters already know about which query, table, index and endpoint costs
 * the most.
 *
 * <p>Every other command here needs a target named in a config, which means they cannot answer the
 * first question anyone has: on a service with hundreds of endpoints and hundreds of statements,
 * *which one is the problem*. A load test cannot answer it either — only real traffic can, and the
 * counters holding that answer are already in the target.
 *
 * <p>So this generates no load and changes nothing. It reads, ranks, and says what to point the
 * rest of the tool at.
 *
 * <h2>What the numbers are, and are not</h2>
 *
 * <p>They are cumulative since the counters were last reset, under whatever traffic actually
 * happened. That is exactly what makes them the right instrument for this question and exactly
 * what makes them useless for some others: they say nothing about capacity, nothing about what
 * happens at twice the load, and nothing at all if the database has not served real traffic.
 */
public record Survey(
        boolean available,
        String unavailableReason,
        String database,
        String countersSince,
        long countersAgeDays,
        boolean statementsAvailable,
        String statementsUnavailableReason,
        List<Statement> statements,
        List<Relation> relations,
        List<Index> indexes,
        List<Endpoint> endpoints,
        String endpointsUnavailableReason) {

    /**
     * Keeps the older eleven-argument shape working. A null reason means nothing was attempted,
     * which is different from an attempt that failed.
     */
    public Survey(boolean available, String unavailableReason, String database,
            String countersSince, long countersAgeDays, boolean statementsAvailable,
            String statementsUnavailableReason, List<Statement> statements,
            List<Relation> relations, List<Index> indexes, List<Endpoint> endpoints) {
        this(available, unavailableReason, database, countersSince, countersAgeDays,
                statementsAvailable, statementsUnavailableReason, statements, relations, indexes,
                endpoints, null);
    }

    /** A table this large that is scanned sequentially is worth an index. */
    public static final long LARGE_TABLE_BYTES = 8L * 1024 * 1024;

    /**
     * Rows read sequentially from one table before it is worth pointing at.
     *
     * <p>Keyed on rows rather than on scan count, which was the first version and was wrong: a
     * table scanned 61 times at 59,000 rows a scan reads 3.6M rows and is obviously the problem,
     * yet a threshold of 1,000 scans missed it entirely. The work done is the signal; how many
     * statements it was spread across is not.
     */
    public static final long NOTABLE_SEQUENTIAL_ROWS = 1_000_000;

    /** Below this a sequential scan is usually the right plan, not a problem. */
    public static final long SMALL_TABLE_BYTES = 1024L * 1024;

    /** An unused index this large is worth the trouble of dropping. */
    public static final long NOTABLE_INDEX_BYTES = 256L * 1024;

    public Survey {
        statements = statements == null ? List.of() : List.copyOf(statements);
        relations = relations == null ? List.of() : List.copyOf(relations);
        indexes = indexes == null ? List.of() : List.copyOf(indexes);
        endpoints = endpoints == null ? List.of() : List.copyOf(endpoints);
    }

    public static Survey unavailable(String database, String reason) {
        return new Survey(false, reason, database, null, 0, false, null,
                List.of(), List.of(), List.of(), List.of());
    }

    public record Statement(String query, long calls, double totalMillis, double meanMillis) {
        /** A single line, collapsed and clipped, because a report is not a place to read SQL. */
        public String oneLine(int limit) {
            String flat = query == null ? "" : query.replaceAll("\\s+", " ").strip();
            return flat.length() <= limit ? flat : flat.substring(0, limit - 1) + "…";
        }
    }

    public record Relation(String name, long sequentialScans, long rowsReadSequentially,
            long indexScans, long tableBytes, long liveRows) {

        public long rowsPerScan() {
            return sequentialScans <= 0 ? 0 : rowsReadSequentially / sequentialScans;
        }

        /**
         * A table that is both large and scanned sequentially many times is the classic shape of a
         * missing index. Size matters: scanning a small table is often the cheapest plan available,
         * and flagging it would bury the real finding.
         */
        public boolean likelyMissingIndex() {
            return tableBytes >= LARGE_TABLE_BYTES
                    && rowsReadSequentially >= NOTABLE_SEQUENTIAL_ROWS
                    && rowsPerScan() >= 1_000;
        }

        public boolean smallEnoughThatScanningIsFine() {
            return tableBytes < SMALL_TABLE_BYTES;
        }
    }

    public record Index(String name, String table, long scans, long indexBytes,
            boolean unique, boolean primaryKey) {

        /**
         * Whether this index can be dropped on the strength of never being read.
         *
         * <p>Never for a primary key or a unique index: those enforce a constraint, and a report
         * that listed them as dead weight would be advising someone to delete a correctness
         * guarantee because it happened not to be used for a lookup. The write cost is real and is
         * still worth reporting — the recommendation is not.
         */
        public boolean droppable() {
            return scans == 0 && !unique && !primaryKey;
        }

        public boolean enforcesAConstraint() {
            return unique || primaryKey;
        }

        public boolean worthReporting() {
            return scans == 0 && indexBytes >= NOTABLE_INDEX_BYTES;
        }
    }

    public record Endpoint(String uri, String method, long count, double totalSeconds) {
        public double meanMillis() {
            return count <= 0 ? -1 : totalSeconds / count * 1_000;
        }

        public String label() {
            return method == null || method.isBlank() ? uri : method + " " + uri;
        }
    }

    // --- rankings --------------------------------------------------------------------------------

    /**
     * Statements by total time, which is the ranking that matters. Mean time flatters a statement
     * called once and buries one called a million times that is individually quick — and the
     * second is usually where the server's time actually goes.
     */
    public List<Statement> statementsByTotalTime(int limit) {
        return statements.stream()
                .sorted((a, b) -> Double.compare(b.totalMillis(), a.totalMillis()))
                .limit(Math.max(0, limit))
                .toList();
    }

    public double totalStatementMillis() {
        return statements.stream().mapToDouble(Statement::totalMillis).sum();
    }

    public double shareOfTotalTime(Statement statement) {
        double total = totalStatementMillis();
        return total <= 0 ? -1 : statement.totalMillis() / total;
    }

    public List<Relation> relationsBySequentialRows(int limit) {
        return relations.stream()
                .filter(relation -> relation.sequentialScans() > 0)
                .sorted((a, b) -> Long.compare(b.rowsReadSequentially(), a.rowsReadSequentially()))
                .limit(Math.max(0, limit))
                .toList();
    }

    public List<Index> unusedIndexes(int limit) {
        return indexes.stream()
                .filter(Index::worthReporting)
                .sorted((a, b) -> Long.compare(b.indexBytes(), a.indexBytes()))
                .limit(Math.max(0, limit))
                .toList();
    }

    public long unusedIndexBytes() {
        return indexes.stream().filter(Index::droppable).mapToLong(Index::indexBytes).sum();
    }

    public List<Endpoint> endpointsByTotalTime(int limit) {
        return endpoints.stream()
                .sorted((a, b) -> Double.compare(b.totalSeconds(), a.totalSeconds()))
                .limit(Math.max(0, limit))
                .toList();
    }

    public double totalEndpointSeconds() {
        return endpoints.stream().mapToDouble(Endpoint::totalSeconds).sum();
    }

    /**
     * True when the database looks as though it has not served real traffic, in which case every
     * ranking here is meaningless and saying so matters more than printing it.
     */
    public boolean looksIdle() {
        long totalScans = relations.stream()
                .mapToLong(relation -> relation.sequentialScans() + relation.indexScans())
                .sum();
        // Counts application statements only. Schema creation leaves DDL in the view, so a
        // database that has only ever been migrated would otherwise look busy.
        return totalScans < 500 && statements.size() < 5;
    }

    public boolean hasAnything() {
        return !statements.isEmpty() || !relations.isEmpty() || !indexes.isEmpty()
                || !endpoints.isEmpty();
    }
}
