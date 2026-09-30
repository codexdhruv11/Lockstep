package com.lockstep.analysis;

import java.util.List;

/**
 * How latency scales with the amount of data, fitted from measurements at several row counts.
 *
 * <p>The model is a power law, {@code p99 = a · rows^k}, fitted by least squares on log-log axes.
 * k is the number that matters: near 0 the query does not care how much data there is, near 1 its
 * cost is proportional to the table, above 1 it is getting worse faster than the table grows.
 *
 * <p>A power law is the right shape for the common cases — an index lookup is roughly flat, a
 * table scan is roughly linear — and the wrong shape across a cache cliff, where latency steps
 * rather than curves. {@link #spansCacheCliff()} says when the fit is being asked to cross one,
 * because a single exponent through a discontinuity is a number that looks authoritative and
 * is not.
 */
public record GrowthCurve(
        List<Point> points,
        double exponent,
        double logIntercept,
        double rSquared) {

    /** Below this the fit is reported but not used to extrapolate. */
    public static final double MIN_R_SQUARED = 0.90;

    /** Fewer points than this and there is no curve, only a line through noise. */
    public static final int MIN_POINTS = 3;

    /**
     * How far past the largest measured row count a crossing may be reported.
     *
     * <p>A good fit is not a licence to extrapolate without limit. Measured here: an indexed
     * lookup over 25,000 to 100,000 rows fitted rows^0.20 at R² 0.982 — a real fit on a nearly
     * flat line, 2.0ms to 2.7ms — and solving it for a 500ms budget gave 22 quadrillion rows.
     * The arithmetic was right and the answer was worthless, because nothing in three
     * measurements spanning 4× says anything about 10^16. A crossing beyond this multiple is
     * refused rather than printed.
     */
    public static final double MAX_EXTRAPOLATION_FACTOR = 10.0;

    /** A drop in buffer-cache hit ratio of at least this much between steps is a cliff. */
    public static final double CACHE_CLIFF_DROP = 0.02;

    /**
     * One measurement.
     *
     * <p>The curve is fitted on {@code serviceP99Nanos} — how long the query itself took — and not
     * on scheduled-time latency, which also contains queue delay. At a fixed request rate the
     * queue grows nonlinearly as the target approaches saturation, so latency stops tracking data
     * size and starts tracking utilisation: measured on postgres:16, a whole-table aggregate whose
     * bytes-per-request scaled cleanly from 5.8MB to 23MB reported p99 latencies of 65.8ms, 32.2ms
     * and 64.2ms, which fits nothing. Service time is the query's own cost and is what data size
     * actually drives. Latency is kept alongside it because it is what a caller experiences.
     */
    public record Point(
            long rows,
            long serviceP99Nanos,
            long latencyP99Nanos,
            long bytesPerRequest,
            double hitRatio,
            double deliveredRatePerSecond) {}

    public GrowthCurve {
        points = points == null ? List.of() : List.copyOf(points);
    }

    public static GrowthCurve fit(List<Point> points) {
        if (points == null || points.size() < 2) {
            return new GrowthCurve(points, 0, 0, 0);
        }
        List<Point> usable = points.stream()
                .filter(point -> point.rows() > 0 && point.serviceP99Nanos() > 0)
                .toList();
        if (usable.size() < 2) {
            return new GrowthCurve(points, 0, 0, 0);
        }

        int n = usable.size();
        double sumX = 0;
        double sumY = 0;
        for (Point point : usable) {
            sumX += Math.log(point.rows());
            sumY += Math.log(point.serviceP99Nanos());
        }
        double meanX = sumX / n;
        double meanY = sumY / n;

        double covariance = 0;
        double varianceX = 0;
        for (Point point : usable) {
            double dx = Math.log(point.rows()) - meanX;
            double dy = Math.log(point.serviceP99Nanos()) - meanY;
            covariance += dx * dy;
            varianceX += dx * dx;
        }
        if (varianceX == 0) {
            // Every measurement at the same row count: no curve to fit.
            return new GrowthCurve(points, 0, 0, 0);
        }
        double slope = covariance / varianceX;
        double intercept = meanY - slope * meanX;

        double residual = 0;
        double total = 0;
        for (Point point : usable) {
            double predicted = intercept + slope * Math.log(point.rows());
            double actual = Math.log(point.serviceP99Nanos());
            residual += (actual - predicted) * (actual - predicted);
            total += (actual - meanY) * (actual - meanY);
        }
        double rSquared = total == 0 ? 1.0 : 1.0 - residual / total;

        return new GrowthCurve(points, slope, intercept, rSquared);
    }

    public enum Shape {
        /** Latency does not depend on how much data there is — an index is doing its job. */
        FLAT,

        /** Grows, but more slowly than the data. */
        SUBLINEAR,

        /** Cost is proportional to the row count — the shape of a table scan. */
        LINEAR,

        /** Getting worse faster than the table grows. */
        SUPERLINEAR,

        /** Not enough measurements, or they do not describe a curve. */
        UNKNOWN
    }

    public Shape shape() {
        if (points.size() < 2 || rSquared <= 0) {
            return Shape.UNKNOWN;
        }
        if (exponent < 0.15) {
            return Shape.FLAT;
        }
        if (exponent < 0.75) {
            return Shape.SUBLINEAR;
        }
        if (exponent <= 1.25) {
            return Shape.LINEAR;
        }
        return Shape.SUPERLINEAR;
    }

    /** True when the fit is good enough, and built on enough points, to extrapolate from. */
    public boolean extrapolatable() {
        return points.size() >= MIN_POINTS
                && rSquared >= MIN_R_SQUARED
                && exponent >= 0.15
                && !spansCacheCliff();
    }

    /** Predicted service time at a row count, from the fit. */
    public long predictServiceNanos(long rows) {
        if (rows <= 0 || points.size() < 2) {
            return -1;
        }
        double predicted = Math.exp(logIntercept + exponent * Math.log(rows));
        if (predicted > Long.MAX_VALUE / 2.0 || Double.isNaN(predicted)) {
            return -1;
        }
        return Math.round(predicted);
    }

    /**
     * The row count at which p99 reaches {@code budgetNanos}, or -1 when that cannot be derived —
     * because the fit is poor, the curve is flat, or the budget is already exceeded at the
     * smallest measurement.
     */
    public long rowsAtBudget(long budgetNanos) {
        if (budgetNanos <= 0 || !extrapolatable()) {
            return -1;
        }
        double rows = Math.exp((Math.log(budgetNanos) - logIntercept) / exponent);
        if (Double.isNaN(rows) || Double.isInfinite(rows) || rows <= 0
                || rows > Long.MAX_VALUE / 2.0) {
            return -1;
        }
        if (rows > largestMeasuredRows() * MAX_EXTRAPOLATION_FACTOR) {
            return -1;
        }
        // Rounded, not truncated: this is an estimate derived through a log/exp round trip, and
        // truncation biases every answer downward (30,000 rows came back as 29,999).
        return Math.round(rows);
    }

    /** The largest row count actually measured — the edge of what the fit is entitled to say. */
    public long largestMeasuredRows() {
        long largest = 0;
        for (Point point : points) {
            largest = Math.max(largest, point.rows());
        }
        return largest;
    }

    /**
     * True when the fit is sound but the budget is only reached implausibly far beyond the data.
     * Distinct from "cannot say": the curve is fine, the question is out of range.
     */
    public boolean budgetBeyondExtrapolationRange(long budgetNanos) {
        if (budgetNanos <= 0 || !extrapolatable() || budgetAlreadyExceeded(budgetNanos)) {
            return false;
        }
        double rows = Math.exp((Math.log(budgetNanos) - logIntercept) / exponent);
        return Double.isInfinite(rows) || Double.isNaN(rows)
                || rows > largestMeasuredRows() * MAX_EXTRAPOLATION_FACTOR;
    }

    public boolean budgetAlreadyExceeded(long budgetNanos) {
        return budgetNanos > 0 && !points.isEmpty()
                && points.get(0).serviceP99Nanos() > budgetNanos;
    }

    /**
     * The row count at which the buffer-cache hit ratio first fell away, or -1 if it never did.
     * This is usually the real knee, and it is a step rather than a curve.
     */
    public long cacheCliffAtRows() {
        for (int i = 1; i < points.size(); i++) {
            double before = points.get(i - 1).hitRatio();
            double after = points.get(i).hitRatio();
            if (before >= 0 && after >= 0 && before - after >= CACHE_CLIFF_DROP) {
                return points.get(i).rows();
            }
        }
        return -1;
    }

    public boolean spansCacheCliff() {
        return cacheCliffAtRows() > 0;
    }

    /**
     * Whether bytes read per request grew with the table. When it did not, an index is bounding
     * the work and the latency growth has some other cause.
     */
    public boolean bytesPerRequestGrew() {
        if (points.size() < 2) {
            return false;
        }
        long first = points.get(0).bytesPerRequest();
        long last = points.get(points.size() - 1).bytesPerRequest();
        return first > 0 && last > first * 1.5;
    }
}
