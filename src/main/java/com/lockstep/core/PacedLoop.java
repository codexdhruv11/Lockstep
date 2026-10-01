package com.lockstep.core;

import com.lockstep.stats.BucketSeries;
import com.lockstep.stats.ErrorCounts;
import com.lockstep.stats.HistogramRecorder;
import com.lockstep.stats.SlowestRequests;
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
        SlowestRequests slowest = new SlowestRequests();
        LongAdder lateFires = new LongAdder();
        AtomicLong maxLatenessNanos = new AtomicLong();
        long scheduledCount = 0;
        long latenessTolerance = latenessToleranceNanos(ratePerSecond);

        long startedAtNanoTime = System.nanoTime();
        ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
        try {
            for (int i = 0; i < workerCount; i++) {
                workers.submit(() -> drain(queue, accepting, recorder, operation, context, progress,
                        inFlight, errors, slowest));
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
                    pacer.expectedHits(context.durationNanos()), drained, errors.snapshot(),
                    slowest.snapshot(), System.nanoTime() - startedAtNanoTime);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            workers.shutdownNow();
            return new LoopResult(recorder.snapshot(), scheduledCount, shed.sum(), inFlight.sum(),
                    lateFires.sum(), maxLatenessNanos.get(),
                    pacer.expectedHits(context.durationNanos()), false, errors.snapshot(),
                    slowest.snapshot(), System.nanoTime() - startedAtNanoTime);
        }
    }

    private static void drain(BlockingQueue<Long> queue, AtomicBoolean accepting,
            HistogramRecorder recorder, Operation operation, RunContext context,
            RunProgress.Counter progress, LongAdder inFlight, ErrorCounts errors,
            SlowestRequests slowest) {
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
                execute(recorder, operation, context, scheduledOffset, progress, errors, slowest);
            } finally {
                inFlight.decrement();
            }
        }
    }

    private static void execute(HistogramRecorder recorder, Operation operation,
            RunContext context, long scheduledOffset, RunProgress.Counter progress,
            ErrorCounts errors, SlowestRequests slowest) {
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
        long latencyNanos = finishedAt - scheduledNanoTime;
        recorder.record(
                scheduledOffset,
                latencyNanos,
                finishedAt - startedAt,
                outcome.success(),
                outcome.statusCode());
        slowest.record(latencyNanos, outcome.traceId(), scheduledOffset);
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
            java.util.Map<String, Long> errorCounts,
            java.util.List<SlowestRequests.Entry> slowestRequests,
            long elapsedNanos) {
        public LoopResult {
            errorCounts = errorCounts == null
                    ? java.util.Map.of()
                    : java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(errorCounts));
            slowestRequests = slowestRequests == null
                    ? java.util.List.of() : java.util.List.copyOf(slowestRequests);
        }

        public LoopResult(BucketSeries series, long scheduledCount, long shedCount,
                long abandonedCount, long lateFireCount, long maxLatenessNanos, long expectedHits,
                boolean drainedCleanly) {
            this(series, scheduledCount, shedCount, abandonedCount, lateFireCount, maxLatenessNanos,
                    expectedHits, drainedCleanly, java.util.Map.of(), java.util.List.of(), 0);
        }

        public LoopResult(BucketSeries series, long scheduledCount, long shedCount,
                long abandonedCount, long lateFireCount, long maxLatenessNanos, long expectedHits,
                boolean drainedCleanly, java.util.Map<String, Long> errorCounts) {
            this(series, scheduledCount, shedCount, abandonedCount, lateFireCount, maxLatenessNanos,
                    expectedHits, drainedCleanly, errorCounts, java.util.List.of(), 0);
        }

        public long executedCount() {
            return series.totalCount();
        }

        /**
         * Operations completed per second of <em>wall clock</em>, including the drain after the
         * measurement window closed.
         *
         * <p>Distinct from the rate in the summary table, which divides by the configured
         * duration. When a target cannot keep up, work queues and finishes during the drain, so
         * the configured-duration figure approaches the rate that was <em>offered</em> rather than
         * the one the target achieved. Measured on a server capped at 50/s and offered 200/s for
         * six seconds: all 1,200 operations completed, which over the configured six seconds reads
         * as 200/s and over the twenty-four seconds it really took is 50/s. The second number is
         * the target's.
         *
         * <p>Negative when the elapsed time was not recorded.
         */
        public double goodputPerSecond() {
            if (elapsedNanos <= 0) {
                return -1;
            }
            return executedCount() / (elapsedNanos / 1_000_000_000.0);
        }

        /** True when the drain ran long enough that the summary's rate overstates the target. */
        public boolean drainDominated(long configuredDurationNanos) {
            return elapsedNanos > configuredDurationNanos * 1.25;
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
