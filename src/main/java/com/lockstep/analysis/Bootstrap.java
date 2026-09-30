package com.lockstep.analysis;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

/**
 * A percentile bootstrap over a run's per-second measurements, used to decide whether a difference
 * between two runs is larger than the runs' own variability.
 *
 * <p>Comparing two runs on a single number and a fixed budget cannot distinguish a real change
 * from an unlucky pair of runs. A tail latency that wanders by 20% between identical runs will
 * cross any budget smaller than that, and reporting it as a regression trains the reader to
 * ignore the tool. So: resample the run's own per-bucket figures, and treat two runs as different
 * only when their intervals do not overlap.
 *
 * <h2>What the interval does and does not mean</h2>
 *
 * <p>It is the variability of per-second tail latency <em>within one run</em>. That is a
 * reasonable proxy for how much the same measurement would move if the run were repeated, and it
 * is available from a single run's report, which matters because it is what people actually have.
 *
 * <p>It is not the sampling error of the true percentile, and the buckets are not independent —
 * a slow stretch spans several of them, so autocorrelation makes the interval narrower than the
 * truth. The honest reading is a lower bound on the noise: a difference inside the interval is
 * certainly not established, while one outside it is suggestive rather than proven. Comparing
 * several independent runs of each version remains stronger, and nothing here prevents that.
 */
public record Bootstrap(double confidence, int resamples) {

    public static final double DEFAULT_CONFIDENCE = 0.95;

    public static final int DEFAULT_RESAMPLES = 2_000;

    /** Below this many buckets there is nothing to resample from. */
    public static final int MIN_SAMPLES = 5;

    public static Bootstrap defaults() {
        return new Bootstrap(DEFAULT_CONFIDENCE, DEFAULT_RESAMPLES);
    }

    /**
     * @param low the lower bound of the interval
     * @param high the upper bound
     * @param point the observed statistic — the mean of the samples, not a resampled value
     * @param samples how many measurements the interval rests on
     */
    public record Interval(long low, long high, long point, int samples) {

        public boolean usable() {
            return samples >= MIN_SAMPLES && high > low;
        }

        /** Half the interval's width as a fraction of the point estimate. */
        public double relativeHalfWidth() {
            if (point <= 0) {
                return -1;
            }
            return (high - low) / 2.0 / point;
        }

        public boolean overlaps(Interval other) {
            if (other == null) {
                return true;
            }
            return low <= other.high() && other.low() <= high;
        }

        public static Interval unusable(int samples) {
            return new Interval(0, 0, 0, samples);
        }
    }

    /**
     * A confidence interval for the mean of {@code samples}.
     *
     * <p>Seeded, because a run's report should be reproducible: the same inputs must produce the
     * same verdict, and an unseeded bootstrap would let a build pass or fail on the draw.
     */
    public Interval interval(List<Long> samples, long seed) {
        if (samples == null) {
            return Interval.unusable(0);
        }
        List<Long> usable = samples.stream().filter(value -> value != null && value > 0).toList();
        if (usable.size() < MIN_SAMPLES) {
            return Interval.unusable(usable.size());
        }

        long point = Math.round(usable.stream().mapToLong(Long::longValue).average().orElseThrow());

        Random random = new Random(seed);
        double[] means = new double[resamples];
        int n = usable.size();
        for (int r = 0; r < resamples; r++) {
            long total = 0;
            for (int i = 0; i < n; i++) {
                total += usable.get(random.nextInt(n));
            }
            means[r] = (double) total / n;
        }
        Arrays.sort(means);

        double alpha = (1.0 - confidence) / 2.0;
        int lowIndex = (int) Math.floor(alpha * resamples);
        int highIndex = (int) Math.ceil((1.0 - alpha) * resamples) - 1;
        lowIndex = Math.max(0, Math.min(lowIndex, resamples - 1));
        highIndex = Math.max(lowIndex, Math.min(highIndex, resamples - 1));

        return new Interval(Math.round(means[lowIndex]), Math.round(means[highIndex]), point,
                usable.size());
    }

    /**
     * Whether two runs' intervals are far enough apart to call the difference real. Overlapping
     * intervals mean the runs are not distinguishable from each other's noise, whatever their
     * point estimates say.
     */
    public static boolean distinguishable(Interval baseline, Interval current) {
        if (baseline == null || current == null || !baseline.usable() || !current.usable()) {
            return false;
        }
        return !baseline.overlaps(current);
    }

    /** Convenience for callers holding bucket reports rather than a list of longs. */
    public static List<Long> valuesOf(List<? extends Number> raw) {
        List<Long> values = new ArrayList<>();
        if (raw == null) {
            return values;
        }
        for (Number number : raw) {
            if (number != null) {
                values.add(number.longValue());
            }
        }
        return values;
    }
}
