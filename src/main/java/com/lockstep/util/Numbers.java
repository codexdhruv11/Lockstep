package com.lockstep.util;

import java.util.Locale;

public final class Numbers {
    private Numbers() {}

    public static String withSeparators(long value) {
        return String.format(Locale.ROOT, "%,d", value);
    }

    public static String latency(long nanos) {
        if (nanos < 1_000L) {
            return nanos + "ns";
        }
        if (nanos < 1_000_000L) {
            return trim(nanos / 1_000.0) + "µs";
        }
        if (nanos < 1_000_000_000L) {
            return trim(nanos / 1_000_000.0) + "ms";
        }
        return trim(nanos / 1_000_000_000.0) + "s";
    }

    public static String rate(double perSecond) {
        return String.format(Locale.ROOT, "%.1f/s", perSecond);
    }

    public static String percent(double fraction) {
        return String.format(Locale.ROOT, "%.1f%%", fraction * 100);
    }

    public static String clock(long nanos) {
        long totalSeconds = Math.max(0, nanos / 1_000_000_000L);
        return String.format(Locale.ROOT, "%02d:%02d", totalSeconds / 60, totalSeconds % 60);
    }

    private static String trim(double value) {
        String formatted = String.format(Locale.ROOT, value >= 100 ? "%.0f" : "%.1f", value);
        return formatted.endsWith(".0") ? formatted.substring(0, formatted.length() - 2) : formatted;
    }
}
