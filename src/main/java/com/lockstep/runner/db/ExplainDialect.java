package com.lockstep.runner.db;

final class ExplainDialect {
    private ExplainDialect() {}

    record Request(String sql, boolean executes, String label) {}

    static Request forQuery(String normalizedDriver, String sql, boolean isRead) {
        return switch (normalizedDriver) {
            case "postgresql" -> isRead
                    ? new Request("EXPLAIN (ANALYZE, BUFFERS) " + sql, true, "EXPLAIN (ANALYZE, BUFFERS)")
                    : new Request("EXPLAIN " + sql, false, "EXPLAIN (estimated; a write is not re-run)");

            case "mysql" -> isRead
                    ? new Request("EXPLAIN ANALYZE " + sql, true, "EXPLAIN ANALYZE (MySQL 8.0.18+)")
                    : new Request("EXPLAIN " + sql, false, "EXPLAIN (estimated; a write is not re-run)");

            case "sqlite" -> new Request("EXPLAIN QUERY PLAN " + sql, false,
                    "EXPLAIN QUERY PLAN (estimated)");
            default -> null;
        };
    }
}
