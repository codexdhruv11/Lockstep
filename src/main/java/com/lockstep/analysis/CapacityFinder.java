package com.lockstep.analysis;

import com.lockstep.stats.Bucket;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class CapacityFinder {
    static final int SUSTAINED_BUCKETS = 3;

    static final double STRAIN_MULTIPLE = 2.0;

    static final double BASELINE_QUANTILE = 0.25;

    static final long STRAIN_FLOOR_NANOS = 50_000_000L;

    public static final int MINIMUM_BUCKETS = 10;

    static final double DELIVERY_TOLERANCE = 0.98;

    private CapacityFinder() {}

    public record Capacity(
            boolean usable,
            int strainBucketIndex,
            long strainOffsetNanos,
            int strainUsers,
            int usersAtEnd,
            long baselineP99Nanos,
            long strainLevelNanos,
            boolean overCapacityThroughout) {
        public Capacity(boolean usable, int strainBucketIndex, long strainOffsetNanos,
                int strainUsers, int usersAtEnd, long baselineP99Nanos, long strainLevelNanos) {
            this(usable, strainBucketIndex, strainOffsetNanos, strainUsers, usersAtEnd,
                    baselineP99Nanos, strainLevelNanos, false);
        }

        public static Capacity notUsable(int usersAtEnd) {
            return new Capacity(false, -1, 0, 0, usersAtEnd, 0, 0, false);
        }

        public boolean strained() {
            return usable && strainBucketIndex >= 0;
        }

        public boolean overCapacityThroughout() {
            return usable && overCapacityThroughout;
        }

        public int suggestedNextConcurrency() {
            if (!usable) {
                return usersAtEnd;
            }
            if (overCapacityThroughout()) {
                return Math.max(1, usersAtEnd / 2);
            }
            return strained() ? Math.max(strainUsers + 1, (int) (strainUsers * 1.5)) : usersAtEnd * 2;
        }
    }

    public static Capacity find(List<Bucket> appBuckets, int concurrency, long rampNanos,
            long bucketWidthNanos) {
        return find(appBuckets, concurrency, rampNanos, bucketWidthNanos, 0);
    }

    public static Capacity find(List<Bucket> appBuckets, int concurrency, long rampNanos,
            long bucketWidthNanos, long warmupNanos) {
        return find(appBuckets, concurrency, rampNanos, bucketWidthNanos, warmupNanos, 1.0);
    }

    public static Capacity find(List<Bucket> appBuckets, int concurrency, long rampNanos,
            long bucketWidthNanos, long warmupNanos, double deliveryRatio) {
        int usersAtEnd = Math.max(0, concurrency);
        if (appBuckets == null || concurrency <= 0 || bucketWidthNanos <= 0) {
            return Capacity.notUsable(usersAtEnd);
        }
        List<Bucket> populated = appBuckets.stream()
                .filter(bucket -> bucket.count() > 0)
                .filter(bucket -> warmupNanos <= 0 || bucket.startOffsetNanos() >= warmupNanos)
                .toList();

        boolean shedLoad = deliveryRatio < DELIVERY_TOLERANCE;

        // Too few buckets to look for a knee. A run that nonetheless failed to deliver its load
        // still has an answer — the shortfall is the proof, and no healthy stretch is needed to
        // establish it.
        //
        // Checking the bucket count first was a real defect, and the worse the saturation the
        // more certainly it struck: the deeper the overload, the fewer buckets contain completed
        // work, because late requests are abandoned and never recorded. Measured — a 20/s target
        // given 150/s delivered 58% with a p99 of 36.5 seconds, populated 7 buckets, and was
        // reported as "run too short to say". The one case where the answer is unmistakable was
        // the one case with no answer.
        //
        // The strain point is still preferred where it can be found, because "strain begins at
        // bucket 12" tells the reader more than "over capacity throughout".
        if (populated.size() < MINIMUM_BUCKETS) {
            if (shedLoad && !populated.isEmpty()) {
                long saturated = baselineP99(populated);
                return new Capacity(true, -1, 0, 0, usersAtEnd, saturated,
                        Math.max((long) (saturated * STRAIN_MULTIPLE),
                                saturated + STRAIN_FLOOR_NANOS),
                        true);
            }
            return Capacity.notUsable(usersAtEnd);
        }

        long baseline = baselineP99(populated);
        long strainLevel = Math.max((long) (baseline * STRAIN_MULTIPLE), baseline + STRAIN_FLOOR_NANOS);

        for (int i = 0; i + SUSTAINED_BUCKETS - 1 < populated.size(); i++) {
            boolean sustained = true;
            for (int offset = 0; offset < SUSTAINED_BUCKETS; offset++) {
                Bucket candidate = populated.get(i + offset);

                if (candidate.index() != populated.get(i).index() + offset
                        || candidate.p99Nanos() < strainLevel) {
                    sustained = false;
                    break;
                }
            }
            if (sustained) {
                Bucket knee = populated.get(i);
                return new Capacity(true, knee.index(), knee.startOffsetNanos(),
                        usersAt(knee.startOffsetNanos(), concurrency, rampNanos, bucketWidthNanos),
                        usersAtEnd, baseline, strainLevel);
            }
        }

        return new Capacity(true, -1, 0, 0, usersAtEnd, baseline, strainLevel, shedLoad);
    }

    static int usersAt(long offsetNanos, int concurrency, long rampNanos, long bucketWidthNanos) {
        if (rampNanos <= 0) {
            return concurrency;
        }
        long elapsed = offsetNanos + bucketWidthNanos;
        double fraction = Math.min(1.0, (double) elapsed / rampNanos);
        return Math.max(1, (int) Math.round(concurrency * fraction));
    }

    static long baselineP99(List<Bucket> buckets) {
        List<Long> sorted = new ArrayList<>(buckets.size());
        for (Bucket bucket : buckets) {
            sorted.add(bucket.p99Nanos());
        }
        Collections.sort(sorted);
        int index = (int) (sorted.size() * BASELINE_QUANTILE);
        return sorted.get(Math.min(index, sorted.size() - 1));
    }
}
