package com.lockstep.core;

@FunctionalInterface
public interface Operation {
    Outcome execute(long scheduledOffsetNanos) throws Exception;

    record Outcome(boolean success, Integer statusCode, String failure) {
        public static final Outcome OK = new Outcome(true, null, null);

        public static Outcome ok(int statusCode) {
            return new Outcome(true, statusCode, null);
        }

        public static Outcome failed(String failure) {
            return new Outcome(false, null, failure);
        }

        public static Outcome failed(int statusCode, String failure) {
            return new Outcome(false, statusCode, failure);
        }
    }
}
