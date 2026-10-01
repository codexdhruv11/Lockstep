package com.lockstep.analysis;

import java.util.ArrayList;
import java.util.List;

public final class CapacitySearch {
    static final double DELIVERY_TOLERANCE = 0.98;

    static final double STRAIN_MULTIPLE = 3.0;

    static final long STRAIN_FLOOR_NANOS = 50_000_000L;

    static final double MIN_MULTIPLIER = 1.0 / 64;
    static final double MAX_MULTIPLIER = 1024;

    static final double RESOLUTION = 0.05;

    private CapacitySearch() {}

    /**
     * How much of a step's own duration the p99 may occupy before the step is too short to have
     * observed anything. A step only shows steady state if work issued at its start finishes
     * inside it; once the p99 approaches the step length, most of what the step scheduled
     * completes during the drain afterwards and is counted as delivered anyway, so the step
     * reports full delivery for a rate it never actually sustained.
     *
     * <p>Measured on a real target: an endpoint whose true sustainable rate was 6/s reported
     * "sustains 21/s" with the default 10s step, because its p99 was over 30 seconds. A third of
     * the step is the point past which that distortion is large enough to refuse to report.
     */
    static final double STEP_P99_FRACTION = 1.0 / 3;

    /** How much longer than the p99 a step should be for its numbers to mean anything. */
    static final int RECOMMENDED_STEP_MULTIPLE = 10;

    public record Measurement(
            double requestedRatePerSecond,
            double achievedRatePerSecond,
            long p99Nanos,
            long scheduledCount,
            long executedCount,
            String runnerName,
            long stepDurationNanos) {

        public Measurement(double requestedRatePerSecond, double achievedRatePerSecond,
                long p99Nanos, long scheduledCount, long executedCount, String runnerName) {
            this(requestedRatePerSecond, achievedRatePerSecond, p99Nanos, scheduledCount,
                    executedCount, runnerName, 0);
        }

        public double deliveryRatio() {
            return scheduledCount <= 0 ? 1.0 : (double) executedCount / scheduledCount;
        }

        /**
         * Whether this step was too short for its own result to mean anything. Zero step duration
         * means the caller did not supply one, in which case nothing can be said.
         */
        public boolean stepTooShort() {
            return stepDurationNanos > 0 && p99Nanos > stepDurationNanos * STEP_P99_FRACTION;
        }

        /** The step length this measurement's own p99 calls for. */
        public long recommendedStepNanos() {
            return p99Nanos * RECOMMENDED_STEP_MULTIPLE;
        }
    }

    @FunctionalInterface
    public interface Probe {
        Measurement at(double multiplier);
    }

    public enum Strain {
        NONE,

        SHED,

        LATENCY
    }

    public record Observation(double multiplier, Measurement measurement, Strain strain) {
        public boolean sustained() {
            return strain == Strain.NONE;
        }
    }

    public record Result(List<Observation> steps, Observation sustained, Observation strained,
            Strain strain, boolean ranOutOfSteps) {
        public Result {
            steps = List.copyOf(steps);
        }

        public boolean bracketed() {
            return sustained != null && strained != null;
        }

        /**
         * Whether the step was too short for the reported figures to be trusted. True when the
         * step the search believed was sustained had a p99 large enough to have spilled past the
         * step's own end - the case that produced "sustains 21/s" for a target whose real answer
         * was 6/s.
         */
        public boolean stepTooShort() {
            // Only the step that was declared SUSTAINED matters. A strained step is supposed to
            // have a latency past the step - that is the strain being detected, not a broken
            // measurement - so including it would make this fire on every healthy run that found
            // a limit, which is every useful run.
            return sustained != null && sustained.measurement().stepTooShort();
        }

        /** The step length the sustained observation's own latency calls for. */
        public long recommendedStepNanos() {
            return sustained == null ? 0 : sustained.measurement().recommendedStepNanos();
        }
    }

    public static Result search(Probe probe, int maxSteps, int refineSteps) {
        List<Observation> steps = new ArrayList<>();
        Observation bestSustained = null;
        Observation worstStrained = null;

        long baselineP99 = -1;

        double multiplier = 1.0;
        boolean climbing = true;
        boolean directionChosen = false;
        int bracketBudget = Math.max(1, maxSteps - refineSteps);

        while (steps.size() < bracketBudget) {
            Observation observation = observe(probe, multiplier, baselineP99);
            steps.add(observation);
            if (observation.sustained()) {
                if (baselineP99 <= 0) {
                    baselineP99 = observation.measurement().p99Nanos();
                }
                if (bestSustained == null || multiplier > bestSustained.multiplier()) {
                    bestSustained = observation;
                }
            } else if (worstStrained == null || multiplier < worstStrained.multiplier()) {
                worstStrained = observation;
            }

            if (!directionChosen) {
                climbing = observation.sustained();
                directionChosen = true;
            }

            if (bestSustained != null && worstStrained != null) {
                break;
            }
            double next = climbing ? multiplier * 2 : multiplier / 2;
            if (next > MAX_MULTIPLIER || next < MIN_MULTIPLIER) {
                break;
            }
            multiplier = next;
        }

        while (bestSustained != null && worstStrained != null && steps.size() < maxSteps) {
            double gap = worstStrained.multiplier() - bestSustained.multiplier();
            if (gap <= bestSustained.multiplier() * RESOLUTION) {
                break;
            }
            double midpoint = bestSustained.multiplier() + gap / 2;
            Observation observation = observe(probe, midpoint, baselineP99);
            steps.add(observation);
            if (observation.sustained()) {
                bestSustained = observation;
            } else {
                worstStrained = observation;
            }
        }

        boolean ranOut = bestSustained == null || worstStrained == null;
        return new Result(steps, bestSustained, worstStrained,
                worstStrained == null ? Strain.NONE : worstStrained.strain(), ranOut);
    }

    private static Observation observe(Probe probe, double multiplier, long baselineP99Nanos) {
        Measurement measurement = probe.at(multiplier);
        return new Observation(multiplier, measurement, judge(measurement, baselineP99Nanos));
    }

    static Strain judge(Measurement measurement, long baselineP99Nanos) {
        if (measurement.deliveryRatio() < DELIVERY_TOLERANCE) {
            return Strain.SHED;
        }
        if (baselineP99Nanos <= 0) {
            return Strain.NONE;
        }
        long limit = Math.max((long) (baselineP99Nanos * STRAIN_MULTIPLE),
                baselineP99Nanos + STRAIN_FLOOR_NANOS);
        return measurement.p99Nanos() > limit ? Strain.LATENCY : Strain.NONE;
    }
}
