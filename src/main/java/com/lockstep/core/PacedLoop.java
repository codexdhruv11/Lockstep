package com.lockstep.core;

import com.lockstep.stats.BucketSeries;
import com.lockstep.stats.ErrorCounts;
import com.lockstep.stats.HistogramRecorder;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.locks.LockSupport;

public final class PacedLoop {
    static final int QUEUE_DEPTH_MULTIPLIER = 8;

    private static final long DRAIN_TIMEOUT_SECONDS = 30;

    private static final long ABANDON_GRACE_SECONDS = 2;

    private static final long MIN_LATENESS_TOLERANCE_NANOS = 1_000_000L;

    private PacedLoop() {}

    public static LoopResult run(RunContext context, int ratePerSecond, Operation operation) {
        return run(context, ratePerSecond, operation, null);
    }

    public static LoopResult run(RunContext context, int ratePerSecond, Operation operation,
            RunProgress.Counter progress) {
        Pacer pacer = new Pacer(ratePerSecond, context.rampNanos(),
                context.arrivals(), context.arrivalSeed());
        int buckets = HistogramRecorder.bucketsFor(context.durationNanos(), context.bucketWidthNanos());
        HistogramRecorder recorder = new HistogramRecorder(context.bucketWidthNanos(), buckets);

        int workerCount = context.concurrency();
        BlockingQueue<Long> queue = new ArrayBlockingQueue<>(workerCount * QUEUE_DEPTH_MULTIPLIER);
        AtomicBoolean accepting = new AtomicBoolean(true);
        LongAdder shed = new LongAdder();
        LongAdder inFlight = new LongAdder();

        ErrorCounts errors = new ErrorCounts();
        LongAdder lateFires = new LongAdder();
        AtomicLong maxLatenessNanos = new AtomicLong();
        long scheduledCount = 0;
        long latenessTolerance = latenessToleranceNanos(ratePerSecond);

        ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
        try {
            for (int i = 0; i < workerCount; i++) {
                workers.submit(() -> drain(queue, accepting, recorder, operation, context, progress, inFlight, errors));
            }

            for (long hit = 1; ; hit++) {
                long scheduledOffset = pacer.scheduledOffsetNanos(hit);
                if (scheduledOffset >= context.durationNanos()) {
                    break;
                }
                scheduledCount++;
                long lateness = awaitDeadline(context.deadlineFor(scheduledOffset));
                if (lateness > latenessTolerance) {
                    lateFires.increment();
                    maxLatenessNanos.accumulateAndGet(lateness, Math::max);
                }

                if (!queue.offer(scheduledOffset)) {
                    shed.increment();
                }
            }

            accepting.set(false);
            workers.shutdown();
            boolean drained = workers.awaitTermination(DRAIN_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!drained) {
                workers.shutdownNow();

                drained = workers.awaitTermination(ABANDON_GRACE_SECONDS, TimeUnit.SECONDS);
            }
            shed.add(queue.size());

            long abandoned = drained ? 0 : Math.max(0, inFlight.sum());
            return new LoopResult(recorder.snapshot(), scheduledCount, shed.sum(), abandoned,
                    lateFires.sum(), maxLatenessNanos.get(),
                    pacer.expectedHits(context.durationNanos()), drained, errors.snapshot());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            workers.shutdownNow();
            return new LoopResult(recorder.snapshot(), scheduledCount, shed.sum(), inFlight.sum(),
                    lateFires.sum(), maxLatenessNanos.get(),
                    pacer.expectedHits(context.durationNanos()), false, errors.snapshot());
        }
    }

    private static void drain(BlockingQueue<Long> queue, AtomicBoolean accepting,
            HistogramRecorder recorder, Operation operation, RunContext context,
            RunProgress.Counter progress, LongAdder inFlight, ErrorCounts errors) {
        while (true) {
            Long scheduledOffset;
            try {
                scheduledOffset = queue.poll(50, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            if (scheduledOffset == null) {
                if (!accepting.get()) {
                    return;
                }
                continue;
            }
            inFlight.increment();
            try {
                execute(recorder, operation, context, scheduledOffset, progress, errors);
            } finally {
                inFlight.decrement();
            }
        }
    }

    private static void execute(HistogramRecorder recorder, Operation operation,
            RunContext context, long scheduledOffset, RunProgress.Counter progress,
            ErrorCounts errors) {
        long scheduledNanoTime = context.deadlineFor(scheduledOffset);
        long startedAt = System.nanoTime();
        Operation.Outcome outcome;
        try {
            outcome = operation.execute(scheduledOffset);
        } catch (Exception e) {
            outcome = Operation.Outcome.failed(e.getClass().getSimpleName()
                    + (e.getMessage() == null ? "" : ": " + e.getMessage()));
        }
        long finishedAt = System.nanoTime();
        if (!outcome.success()) {
            errors.record(outcome.failure());
        }
        recorder.record(
                scheduledOffset,
                finishedAt - scheduledNanoTime,
                finishedAt - startedAt,
                outcome.success(),
                outcome.statusCode());
        if (progress != null) {
            progress.record(outcome.success());
        }
    }

    static long latenessToleranceNanos(int ratePerSecond) {
        long intervalNanos = 1_000_000_000L / Math.max(1, ratePerSecond);
        return Math.max(MIN_LATENESS_TOLERANCE_NANOS, intervalNanos / 10);
    }

    private static long awaitDeadline(long deadlineNanoTime) {
        long remaining = deadlineNanoTime - System.nanoTime();
        while (remaining > 0) {
            LockSupport.parkNanos(remaining);
            remaining = deadlineNanoTime - System.nanoTime();
        }
        return -remaining;
    }

    public record LoopResult(
            BucketSeries series,
            long scheduledCount,
            long shedCount,
            long abandonedCount,
            long lateFireCount,
            long maxLatenessNanos,
            long expectedHits,
            boolean drainedCleanly,
            java.util.Map<String, Long> errorCounts) {
        public LoopResult {
            errorCounts = errorCounts == null
                    ? java.util.Map.of()
                    : java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(errorCounts));
        }

        public LoopResult(BucketSeries series, long scheduledCount, long shedCount,
                long abandonedCount, long lateFireCount, long maxLatenessNanos, long expectedHits,
                boolean drainedCleanly) {
            this(series, scheduledCount, shedCount, abandonedCount, lateFireCount, maxLatenessNanos,
                    expectedHits, drainedCleanly, java.util.Map.of());
        }

        public long executedCount() {
            return series.totalCount();
        }

        public boolean fellShort() {
            return shedCount > 0 || abandonedCount > 0 || lateFireCount > 0 || !drainedCleanly
                    || scheduledCount < expectedHits;
        }

        public boolean accountsForEveryScheduledOperation() {
            return executedCount() + shedCount + abandonedCount == scheduledCount;
        }
    }
}
