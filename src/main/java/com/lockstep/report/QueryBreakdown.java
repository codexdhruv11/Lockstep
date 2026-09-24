package com.lockstep.report;

import com.lockstep.stats.BucketSeries;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

public final class QueryBreakdown {
    private QueryBreakdown() {}

    public record Row(
            String label,
            long count,
            long errorCount,
            double shareOfServiceTime,
            long serviceMeanNanos,
            long serviceP50Nanos,
            long serviceP95Nanos,
            long serviceP99Nanos,
            long serviceMaxNanos,
            long p99Nanos) {}

    public static List<Row> rank(Map<String, BucketSeries> breakdown) {
        if (breakdown == null || breakdown.size() < 2) {
            return List.of();
        }
        double totalTime = 0;
        for (BucketSeries series : breakdown.values()) {
            totalTime += serviceTimeNanos(series);
        }
        List<Row> rows = new ArrayList<>(breakdown.size());
        for (Map.Entry<String, BucketSeries> entry : breakdown.entrySet()) {
            BucketSeries series = entry.getValue();
            var service = series.mergedServiceTime();
            rows.add(new Row(
                    entry.getKey(),
                    series.totalCount(),
                    series.errorCount(),
                    totalTime > 0 ? serviceTimeNanos(series) / totalTime : 0,
                    (long) service.getMean(),
                    service.getValueAtPercentile(50),
                    service.getValueAtPercentile(95),
                    service.getValueAtPercentile(99),
                    service.getMaxValue(),
                    series.mergedLatency().getValueAtPercentile(99)));
        }
        rows.sort(Comparator.comparingDouble(Row::shareOfServiceTime).reversed());
        return List.copyOf(rows);
    }

    private static double serviceTimeNanos(BucketSeries series) {
        return series.totalCount() * series.mergedServiceTime().getMean();
    }
}
