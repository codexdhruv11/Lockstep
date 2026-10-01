package com.lockstep.analysis;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import jdk.jfr.Configuration;
import jdk.jfr.Recording;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordingFile;

public final class SelfAudit implements AutoCloseable {

    public static final long SIGNIFICANT_PAUSE_NANOS = 10_000_000L;

    /** How many individual pauses the report names. The total counts all of them regardless. */
    private static final int LONGEST_PAUSES_LISTED = 10;

    private static final String[] PAUSE_EVENTS = {
        "jdk.GCPhasePause",
        "jdk.SafepointBegin",
    };

    private final Recording recording;
    private final Path dumpTo;

    private Instant runStartWallClock;
    private long bucketWidthNanos;
    private int bucketCount;
    private long thresholdNanos = SIGNIFICANT_PAUSE_NANOS;

    private SelfAudit(Recording recording, Path dumpTo) {
        this.recording = recording;
        this.dumpTo = dumpTo;
    }

    public static SelfAudit start() {
        try {
            Recording recording = new Recording(Configuration.getConfiguration("default"));
            recording.enable("jdk.GCPhasePause").withoutThreshold();
            recording.enable("jdk.SafepointBegin").withoutThreshold();
            recording.setToDisk(true);
            Path dump = Files.createTempFile("lockstep-selfaudit-", ".jfr");
            recording.start();
            return new SelfAudit(recording, dump);
        } catch (IOException | java.text.ParseException | RuntimeException e) {
            return null;
        }
    }

    public Report stop(Instant runStartWallClock, long bucketWidthNanos, int bucketCount) {
        return stop(runStartWallClock, bucketWidthNanos, bucketCount, SIGNIFICANT_PAUSE_NANOS);
    }

    public Report stop(Instant runStartWallClock, long bucketWidthNanos, int bucketCount,
            long thresholdNanos) {
        this.runStartWallClock = runStartWallClock;
        this.bucketWidthNanos = bucketWidthNanos;
        this.bucketCount = bucketCount;
        this.thresholdNanos = thresholdNanos;
        try {
            recording.stop();
            recording.dump(dumpTo);
        } catch (IOException | RuntimeException e) {
            return Report.unavailable("could not read the flight recording: " + describe(e));
        } finally {
            recording.close();
        }

        try {
            return analyse();
        } catch (IOException | RuntimeException e) {
            return Report.unavailable("could not parse the flight recording: " + describe(e));
        } finally {
            try {
                Files.deleteIfExists(dumpTo);
            } catch (IOException ignored) {
                // a leftover temp file is not worth failing a completed run over
            }
        }
    }

    /**
     * Sums every pause the recording holds, and only then applies the threshold to decide which
     * ones are worth naming individually.
     *
     * <p>The threshold used to be applied first, which made the total wrong in the one case that
     * matters most. A generator doing steady allocation produces a long tail of young-collection
     * pauses of two to nine milliseconds; measured against the GC MXBean, 43 of them came to
     * 120ms of real stall in a four-second run, and every one fell under the 10ms threshold. The
     * report then said "no JVM pause above 10ms; the latencies above are the target's, not this
     * process's" - an affirmative clean bill of health for a process that had been stopped for 3%
     * of the run. Frequent small pauses are exactly what moves a p99, so they are now counted.
     */
    private Report analyse() throws IOException {
        Map<Integer, Long> pausedNanosPerBucket = new LinkedHashMap<>();
        List<Pause> significant = new ArrayList<>();
        long totalPausedNanos = 0;
        long gcPausedNanos = 0;
        long gcPauses = 0;

        try (RecordingFile file = new RecordingFile(dumpTo)) {
            while (file.hasMoreEvents()) {
                RecordedEvent event = file.readEvent();
                String name = event.getEventType().getName();
                if (!isPauseEvent(name)) {
                    continue;
                }
                Duration duration = event.getDuration();
                if (duration == null || duration.isZero()) {
                    continue;
                }
                long nanos = duration.toNanos();
                int bucket = bucketFor(event.getStartTime());
                if (bucket < 0) {
                    continue;
                }
                pausedNanosPerBucket.merge(bucket, nanos, Long::sum);
                totalPausedNanos += nanos;
                if (name.equals("jdk.GCPhasePause")) {
                    gcPauses++;
                    gcPausedNanos += nanos;
                }
                if (nanos >= thresholdNanos) {
                    significant.add(new Pause(bucket, nanos, name));
                }
            }
        }

        int significantCount = significant.size();
        significant.sort((a, b) -> Long.compare(b.durationNanos(), a.durationNanos()));
        if (significant.size() > LONGEST_PAUSES_LISTED) {
            significant = new ArrayList<>(significant.subList(0, LONGEST_PAUSES_LISTED));
        }
        return new Report(true, null, pausedNanosPerBucket, List.copyOf(significant),
                totalPausedNanos, gcPauses, gcPausedNanos, significantCount);
    }

    private static boolean isPauseEvent(String name) {
        for (String candidate : PAUSE_EVENTS) {
            if (candidate.equals(name)) {
                return true;
            }
        }
        return false;
    }

    private int bucketFor(Instant eventStart) {
        long offsetNanos = Duration.between(runStartWallClock, eventStart).toNanos();
        if (offsetNanos < 0) {
            return -1;
        }
        long index = offsetNanos / bucketWidthNanos;
        if (index >= bucketCount) {
            return -1;
        }
        return (int) index;
    }

    private static String describe(Exception e) {
        String message = e.getMessage();
        return e.getClass().getSimpleName() + (message == null ? "" : ": " + message);
    }

    @Override
    public void close() {
        try {
            recording.close();
        } catch (RuntimeException ignored) {
            // already stopped
        }
    }

    public record Pause(int bucketIndex, long durationNanos, String eventName) {}

    public record Report(
            boolean available,
            String unavailableReason,
            Map<Integer, Long> pausedNanosPerBucket,
            List<Pause> longestPauses,
            long totalPausedNanos,
            long gcPauseCount,
            long gcPausedNanos,
            int significantPauseCount) {

        public Report {
            pausedNanosPerBucket = pausedNanosPerBucket == null
                    ? Map.of()
                    : java.util.Collections.unmodifiableMap(new LinkedHashMap<>(pausedNanosPerBucket));
            longestPauses = longestPauses == null ? List.of() : List.copyOf(longestPauses);
        }

        public static Report unavailable(String reason) {
            return new Report(false, reason, Map.of(), List.of(), 0, 0, 0, 0);
        }

        public long pausedNanosIn(int bucketIndex) {
            return pausedNanosPerBucket.getOrDefault(bucketIndex, 0L);
        }

        public boolean hasPauses() {
            return totalPausedNanos > 0;
        }

        /**
         * Whether any single pause was long enough to name. False with {@link #hasPauses()} true
         * means the stall was spread over many short pauses, which the report has to say
         * differently - there is no row to point at, but the time was still lost.
         */
        public boolean hasSignificantPauses() {
            return !longestPauses.isEmpty();
        }

        /** What share of the run this process spent stopped. */
        public double pausedFractionOf(long runDurationNanos) {
            return runDurationNanos <= 0 ? 0 : (double) totalPausedNanos / runDurationNanos;
        }
    }
}
