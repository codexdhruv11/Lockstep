package com.lockstep.util;

import java.util.Locale;

public final class Numbers {
    private Numbers() {}

    public static String withSeparators(long value) {
        return String.format(Locale.ROOT, "%,d", value);
    }

    public static String latency(long nanos) {
        if (nanos < 0) {
            return "-" + latency(-nanos);
        }

        if (nanos < 1_000L) {
            return nanos + "ns";
        }
        if (nanos < 1_000_000L && roundsBelowAThousand(nanos / 1_000.0)) {
            return trim(nanos / 1_000.0) + "µs";
        }
        if (nanos < 1_000_000_000L && roundsBelowAThousand(nanos / 1_000_000.0)) {
            return trim(nanos / 1_000_000.0) + "ms";
        }
        if (nanos < 1_000_000L) {
            return trim(nanos / 1_000_000.0) + "ms";
        }
        if (nanos < 1_000_000_000L) {
            return trim(nanos / 1_000_000_000.0) + "s";
        }
        return trim(nanos / 1_000_000_000.0) + "s";
    }

    private static boolean roundsBelowAThousand(double value) {
        return Double.parseDouble(String.format(Locale.ROOT, value >= 100 ? "%.0f" : "%.1f", value)) < 1000;
    }

    public static String rate(double perSecond) {
        return String.format(Locale.ROOT, "%.1f/s", perSecond);
    }

    public static String percent(double fraction) {
        return String.format(Locale.ROOT, "%.1f%%", fraction * 100);
    }

    /**
     * Binary units, because every tool that reports database and memory sizes uses them: a
     * Postgres 8kB block is 8192 bytes, and shared_buffers of "128MB" is 134217728.
     */
    public static String bytes(long value) {
        if (value < 0) {
            return "-" + bytes(-value);
        }
        if (value < 1024) {
            return value + "B";
        }
        double kb = value / 1024.0;
        if (kb < 1024) {
            return trim(kb) + "KB";
        }
        double mb = kb / 1024.0;
        if (mb < 1024) {
            return trim(mb) + "MB";
        }
        double gb = mb / 1024.0;
        if (gb < 1024) {
            return trim(gb) + "GB";
        }
        return trim(gb / 1024.0) + "TB";
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
