package com.lockstep.core;

import com.lockstep.stats.BucketSeries;
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

    private static final long MIN_LATENESS_TOLERANCE_NANOS = 1_000_000L;

    private PacedLoop() {}

    public static LoopResult run(RunContext context, int ratePerSecond, Operation operation) {
        return run(context, ratePerSecond, operation, null);
    }

    public static LoopResult run(RunContext context, int ratePerSecond, Operation operation,
            RunProgress.Counter progress) {
        Pacer pacer = new Pacer(ratePerSecond, context.rampNanos());
        int buckets = HistogramRecorder.bucketsFor(context.durationNanos(), context.bucketWidthNanos());
        HistogramRecorder recorder = new HistogramRecorder(context.bucketWidthNanos(), buckets);

        int workerCount = context.concurrency();
        BlockingQueue<Long> queue = new ArrayBlockingQueue<>(workerCount * QUEUE_DEPTH_MULTIPLIER);
        AtomicBoolean accepting = new AtomicBoolean(true);
        LongAdder shed = new LongAdder();
        LongAdder lateFires = new LongAdder();
        AtomicLong maxLatenessNanos = new AtomicLong();
        long scheduledCount = 0;
        long latenessTolerance = latenessToleranceNanos(ratePerSecond);

        try (ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < workerCount; i++) {
                workers.submit(() -> drain(queue, accepting, recorder, operation, context, progress));
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
            }
            long unprocessed = queue.size();
            shed.add(unprocessed);
            return new LoopResult(recorder.snapshot(), scheduledCount, shed.sum(),
                    lateFires.sum(), maxLatenessNanos.get(),
                    pacer.expectedHits(context.durationNanos()), drained);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new LoopResult(recorder.snapshot(), scheduledCount, shed.sum(),
                    lateFires.sum(), maxLatenessNanos.get(),
                    pacer.expectedHits(context.durationNanos()), false);
        }
    }

    private static void drain(BlockingQueue<Long> queue, AtomicBoolean accepting,
            HistogramRecorder recorder, Operation operation, RunContext context,
            RunProgress.Counter progress) {
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
            execute(recorder, operation, context, scheduledOffset, progress);
        }
    }

    private static void execute(HistogramRecorder recorder, Operation operation,
            RunContext context, long scheduledOffset, RunProgress.Counter progress) {
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
            long lateFireCount,
            long maxLatenessNanos,
            long expectedHits,
            boolean drainedCleanly) {
        public long executedCount() {
            return series.totalCount();
        }

        public boolean fellShort() {
            return shedCount > 0 || lateFireCount > 0 || !drainedCleanly;
        }
    }
}
