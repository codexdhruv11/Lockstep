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

    private CapacityFinder() {}

    public record Capacity(
            boolean usable,
            int strainBucketIndex,
            long strainOffsetNanos,
            int strainUsers,
            int usersAtEnd,
            long baselineP99Nanos,
            long strainLevelNanos) {
        public static Capacity notUsable(int usersAtEnd) {
            return new Capacity(false, -1, 0, 0, usersAtEnd, 0, 0);
        }

        public boolean strained() {
            return usable && strainBucketIndex >= 0;
        }

        public int suggestedNextConcurrency() {
            if (!usable) {
                return usersAtEnd;
            }
            return strained() ? Math.max(strainUsers + 1, (int) (strainUsers * 1.5)) : usersAtEnd * 2;
        }
    }

    public static Capacity find(List<Bucket> appBuckets, int concurrency, long rampNanos,
            long bucketWidthNanos) {
        int usersAtEnd = Math.max(0, concurrency);
        if (appBuckets == null || concurrency <= 0 || bucketWidthNanos <= 0) {
            return Capacity.notUsable(usersAtEnd);
        }
        List<Bucket> populated = appBuckets.stream().filter(bucket -> bucket.count() > 0).toList();
        if (populated.size() < MINIMUM_BUCKETS) {
            return Capacity.notUsable(usersAtEnd);
        }

        long baseline = baselineP99(populated);
        long strainLevel = Math.max((long) (baseline * STRAIN_MULTIPLE), baseline + STRAIN_FLOOR_NANOS);

        for (int i = 0; i + SUSTAINED_BUCKETS - 1 < populated.size(); i++) {
            boolean sustained = true;
            for (int offset = 0; offset < SUSTAINED_BUCKETS; offset++) {
                if (populated.get(i + offset).p99Nanos() < strainLevel) {
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
        return new Capacity(true, -1, 0, 0, usersAtEnd, baseline, strainLevel);
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
