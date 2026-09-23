package com.lockstep.util;

public final class Durations {
    private static final long NANOS_PER_MICROSECOND = 1_000L;
    private static final long NANOS_PER_MILLISECOND = 1_000_000L;
    private static final long NANOS_PER_SECOND = 1_000_000_000L;
    private static final long NANOS_PER_MINUTE = 60 * NANOS_PER_SECOND;
    private static final long NANOS_PER_HOUR = 60 * NANOS_PER_MINUTE;

    private Durations() {}

    public static long parseToNanos(String input) {
        String trimmed = input.trim();
        if (trimmed.isEmpty()) {
            throw new ConfigDurationException("duration must not be empty");
        }
        try {
            return Long.parseLong(trimmed);
        } catch (NumberFormatException notAnInteger) {
        }

        boolean negative = trimmed.startsWith("-");
        if (trimmed.startsWith("-") || trimmed.startsWith("+")) {
            trimmed = trimmed.substring(1);
        }
        if (trimmed.isEmpty()) {
            throw new ConfigDurationException("invalid duration \"" + input + "\"");
        }
        long total = 0;
        int index = 0;
        while (index < trimmed.length()) {
            int numberStart = index;
            while (index < trimmed.length() && (Character.isDigit(trimmed.charAt(index)) || trimmed.charAt(index) == '.')) {
                index++;
            }
            if (numberStart == index) {
                throw new ConfigDurationException("invalid duration \"" + input + "\"");
            }
            double number;
            try {
                number = Double.parseDouble(trimmed.substring(numberStart, index));
            } catch (NumberFormatException badNumber) {
                throw new ConfigDurationException("invalid duration \"" + input + "\"");
            }
            int unitStart = index;
            while (index < trimmed.length() && !Character.isDigit(trimmed.charAt(index)) && trimmed.charAt(index) != '.') {
                index++;
            }
            long unitNanos = unitNanos(trimmed.substring(unitStart, index), input);
            total += Math.round(number * unitNanos);
        }
        return negative ? -total : total;
    }

    public static String formatNanos(long nanos) {
        if (nanos == 0) {
            return "0s";
        }
        boolean negative = nanos < 0;
        long value = Math.abs(nanos);
        if (value % NANOS_PER_HOUR == 0) {
            return signed(negative, value / NANOS_PER_HOUR + "h");
        }
        if (value % NANOS_PER_MINUTE == 0) {
            return signed(negative, value / NANOS_PER_MINUTE + "m");
        }
        if (value % NANOS_PER_SECOND == 0) {
            return signed(negative, value / NANOS_PER_SECOND + "s");
        }
        if (value % NANOS_PER_MILLISECOND == 0) {
            return signed(negative, value / NANOS_PER_MILLISECOND + "ms");
        }
        if (value % NANOS_PER_MICROSECOND == 0) {
            return signed(negative, value / NANOS_PER_MICROSECOND + "us");
        }
        return signed(negative, value + "ns");
    }

    private static String signed(boolean negative, String magnitude) {
        return negative ? "-" + magnitude : magnitude;
    }

    private static long unitNanos(String unit, String input) {
        return switch (unit) {
            case "h" -> NANOS_PER_HOUR;
            case "m" -> NANOS_PER_MINUTE;
            case "s" -> NANOS_PER_SECOND;
            case "ms" -> NANOS_PER_MILLISECOND;
            case "us", "µs" -> NANOS_PER_MICROSECOND;
            case "ns" -> 1L;
            default -> throw new ConfigDurationException("invalid duration \"" + input + "\"");
        };
    }
}
