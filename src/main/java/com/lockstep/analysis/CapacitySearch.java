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

    public record Measurement(
            double requestedRatePerSecond,
            double achievedRatePerSecond,
            long p99Nanos,
            long scheduledCount,
            long executedCount,
            String runnerName) {
        public double deliveryRatio() {
            return scheduledCount <= 0 ? 1.0 : (double) executedCount / scheduledCount;
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
