package com.lockstep.report;

import static org.assertj.core.api.Assertions.assertThat;

import com.lockstep.runner.QueryLabels;
import com.lockstep.stats.BucketSeries;
import com.lockstep.stats.HistogramRecorder;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class QueryTableTest {
    private static final long MS = 1_000_000L;
    private static final long SECOND = 1_000_000_000L;

    private static BucketSeries series(long count, long serviceNanos) {
        return series(count, serviceNanos, 0);
    }

    private static BucketSeries series(long count, long serviceNanos, long queueNanos) {
        HistogramRecorder recorder = new HistogramRecorder(SECOND, 10);
        for (long i = 0; i < count; i++) {
            recorder.record((i % 10) * SECOND, serviceNanos + queueNanos, serviceNanos, true, null);
        }
        return recorder.snapshot();
    }

    @Test
    void rowsAreOrderedByShareOfTimeNotByConfigOrderOrByLatency() {
        Map<String, BucketSeries> queries = new LinkedHashMap<>();

        queries.put(QueryLabels.of(0, "SELECT * FROM audit_log"), series(10, 100 * MS));

        queries.put(QueryLabels.of(1, "SELECT count(*) FROM orders"), series(10_000, 5 * MS));

        String[] lines = CliTables.queryTable("db queries", queries, 10 * SECOND).stripTrailing().split("\n");

        assertThat(lines[0]).isEqualTo("db queries");
        assertThat(lines[1]).contains("DB QUERIES", "CALLS", "SHARE", "P99");
        assertThat(lines[2]).startsWith("q2 SELECT count(*) FROM orders").contains("10,000", "98.0%");
        assertThat(lines[3]).startsWith("q1 SELECT * FROM audit_log").contains("2.0%");

        String footnotes = lines[lines.length - 2] + " " + lines[lines.length - 1];
        assertThat(footnotes).contains("calls x mean").contains("queue delay");
    }

    @Test
    void aSharedQueueDelayDoesNotDisturbTheRanking() {
        Map<String, BucketSeries> queries = new LinkedHashMap<>();
        queries.put(QueryLabels.of(0, "SELECT pg_sleep_ish()"), series(100, 100 * MS, 2 * SECOND));
        queries.put(QueryLabels.of(1, "SELECT 1"), series(100, 5 * MS, 2 * SECOND));

        String[] lines = CliTables.queryTable("db queries", queries, 10 * SECOND).stripTrailing().split("\n");

        assertThat(lines[2]).startsWith("q1 SELECT pg_sleep_ish()").contains("95.2%");
        assertThat(lines[3]).startsWith("q2 SELECT 1").contains("4.8%");

        assertThat(lines[2]).contains("100ms");
        assertThat(lines[3]).contains("5ms");
        assertThat(lines[2]).doesNotContain("2.1s");
    }

    @Test
    void aSingleQueryMixIsNotPrintedAtAll() {
        Map<String, BucketSeries> queries = new LinkedHashMap<>();
        queries.put(QueryLabels.of(0, "SELECT 1"), series(100, MS));

        assertThat(CliTables.queryTable("db queries", queries, 10 * SECOND)).isEmpty();
        assertThat(CliTables.queryTable("db queries", Map.of(), 10 * SECOND)).isEmpty();
    }

    @Test
    void multiLineSqlIsFlattenedAndTruncatedSoTheTableStaysATable() {
        String sql = """
                SELECT count(*) FILTER (WHERE status IN ('OPEN', 'LIVE')) AS active,
                       count(*) AS total
                  FROM orders
                """;
        String label = QueryLabels.of(0, sql);

        assertThat(label).doesNotContain("\n");
        assertThat(label).startsWith("q1 SELECT count(*) FILTER");
        assertThat(label).endsWith("…");

        assertThat(label.length()).isLessThanOrEqualTo(63);
    }

    @Test
    void shortSqlIsPrintedWhole() {
        assertThat(QueryLabels.of(2, "SELECT 1")).isEqualTo("q3 SELECT 1");
    }

    @Test
    void identicalStatementsGetDistinctLabels() {
        String sql = "SELECT count(*) FROM orders";
        assertThat(QueryLabels.of(0, sql)).isNotEqualTo(QueryLabels.of(1, sql));
    }
}
