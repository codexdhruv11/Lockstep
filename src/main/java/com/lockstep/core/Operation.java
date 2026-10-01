package com.lockstep.core;

@FunctionalInterface
public interface Operation {
    Outcome execute(long scheduledOffsetNanos) throws Exception;

    /**
     * @param traceId the W3C trace id sent with this operation, or null when tracing is off.
     *     Carried here because the runner generates it and the paced loop is what learns the
     *     latency, so neither alone can pair them.
     */
    record Outcome(boolean success, Integer statusCode, String failure, String traceId) {
        public static final Outcome OK = new Outcome(true, null, null, null);

        public Outcome(boolean success, Integer statusCode, String failure) {
            this(success, statusCode, failure, null);
        }

        public static Outcome ok(int statusCode) {
            return new Outcome(true, statusCode, null, null);
        }

        public static Outcome failed(String failure) {
            return new Outcome(false, null, failure, null);
        }

        public static Outcome failed(int statusCode, String failure) {
            return new Outcome(false, statusCode, failure, null);
        }

        /** The same outcome with a trace id attached. */
        public Outcome withTraceId(String traceId) {
            return traceId == null ? this : new Outcome(success, statusCode, failure, traceId);
        }
    }
}
