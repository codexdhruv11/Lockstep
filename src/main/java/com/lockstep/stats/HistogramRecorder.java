package com.lockstep.stats;

import java.util.concurrent.atomic.AtomicLongArray;
import java.util.concurrent.atomic.LongAdder;
import org.HdrHistogram.ConcurrentHistogram;
import org.HdrHistogram.Histogram;

public final class HistogramRecorder {
    private static final long MAX_TRACKABLE_NANOS = 3_600_000_000_000L;
    private static final int SIGNIFICANT_DIGITS = 2;

    public static final double PERCENTILE_PRECISION = 0.01;

    private final long bucketWidthNanos;
    private final int bucketCount;
    private final ConcurrentHistogram[] latency;
    private final ConcurrentHistogram[] serviceTime;
    private final AtomicLongArray errorsPerBucket;
    private final LongAdder totalRecorded = new LongAdder();
    private final LongAdder totalErrors = new LongAdder();
    private final LongAdder clampedEarly = new LongAdder();
    private final LongAdder clampedLate = new LongAdder();
    private final StatusCounts statusCounts = new StatusCounts();

    public HistogramRecorder(long bucketWidthNanos, int bucketCount) {
        if (bucketWidthNanos <= 0) {
            throw new IllegalArgumentException("bucketWidthNanos must be positive, got " + bucketWidthNanos);
        }
        if (bucketCount <= 0) {
            throw new IllegalArgumentException("bucketCount must be positive, got " + bucketCount);
        }
        this.bucketWidthNanos = bucketWidthNanos;
        this.bucketCount = bucketCount;
        this.latency = new ConcurrentHistogram[bucketCount];
        this.serviceTime = new ConcurrentHistogram[bucketCount];
        this.errorsPerBucket = new AtomicLongArray(bucketCount);
        for (int i = 0; i < bucketCount; i++) {
            latency[i] = new ConcurrentHistogram(SIGNIFICANT_DIGITS);
            serviceTime[i] = new ConcurrentHistogram(SIGNIFICANT_DIGITS);
        }
    }

    public static int bucketsFor(long durationNanos, long bucketWidthNanos) {
        if (bucketWidthNanos <= 0) {
            throw new IllegalArgumentException("bucketWidthNanos must be positive");
        }
        long needed = (durationNanos + bucketWidthNanos - 1) / bucketWidthNanos;
        return (int) Math.max(1, Math.min(needed, Integer.MAX_VALUE));
    }

    public void record(long scheduledOffsetNanos, long latencyNanos, long serviceTimeNanos,
            boolean success, Integer statusCode) {
        int index = bucketIndexFor(scheduledOffsetNanos);
        latency[index].recordValue(clamp(latencyNanos));
        serviceTime[index].recordValue(clamp(serviceTimeNanos));
        totalRecorded.increment();
        if (!success) {
            totalErrors.increment();
            errorsPerBucket.incrementAndGet(index);
        }
        if (statusCode != null) {
            statusCounts.record(statusCode);
        }
    }

    private int bucketIndexFor(long scheduledOffsetNanos) {
        if (scheduledOffsetNanos < 0) {
            clampedEarly.increment();
            return 0;
        }
        long index = scheduledOffsetNanos / bucketWidthNanos;
        if (index >= bucketCount) {
            clampedLate.increment();
            return bucketCount - 1;
        }
        return (int) index;
    }

    private static long clamp(long nanos) {
        if (nanos < 0) {
            return 0;
        }
        return Math.min(nanos, MAX_TRACKABLE_NANOS);
    }

    public long estimatedFootprintBytes() {
        long total = 0;
        for (int i = 0; i < bucketCount; i++) {
            total += latency[i].getEstimatedFootprintInBytes();
            total += serviceTime[i].getEstimatedFootprintInBytes();
        }
        return total;
    }

    public BucketSeries snapshot() {
        Bucket[] buckets = new Bucket[bucketCount];
        Histogram merged = new Histogram(SIGNIFICANT_DIGITS);
        Histogram mergedService = new Histogram(SIGNIFICANT_DIGITS);
        merged.setAutoResize(true);
        mergedService.setAutoResize(true);
        for (int i = 0; i < bucketCount; i++) {
            Histogram bucketLatency = latency[i].copy();
            Histogram bucketService = serviceTime[i].copy();
            merged.add(bucketLatency);
            mergedService.add(bucketService);
            buckets[i] = toBucket(i, bucketLatency, bucketService, errorsPerBucket.get(i));
        }
        return new BucketSeries(
                bucketWidthNanos,
                java.util.List.of(buckets),
                merged,
                mergedService,
                totalRecorded.sum(),
                totalErrors.sum(),
                clampedEarly.sum(),
                clampedLate.sum(),
                statusCounts.snapshot());
    }

    private Bucket toBucket(int index, Histogram bucketLatency, Histogram bucketService, long errors) {
        long start = (long) index * bucketWidthNanos;
        if (bucketLatency.getTotalCount() == 0) {
            return Bucket.empty(index, bucketWidthNanos);
        }
        return new Bucket(
                index,
                start,
                start + bucketWidthNanos,
                bucketLatency.getTotalCount(),
                errors,
                (long) bucketLatency.getMean(),
                bucketLatency.getValueAtPercentile(50),
                bucketLatency.getValueAtPercentile(95),
                bucketLatency.getValueAtPercentile(99),
                bucketLatency.getMaxValue(),
                bucketService.getValueAtPercentile(99));
    }
}
