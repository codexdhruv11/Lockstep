package com.lockstep.util;

public final class Ansi {
    private static final boolean ENABLED = detect();

    private Ansi() {}

    private static boolean detect() {
        if (System.getenv("NO_COLOR") != null) {
            return false;
        }
        String term = System.getenv("TERM");
        if (term == null || term.equals("dumb")) {
            return false;
        }

        return System.console() != null;
    }

    public static boolean enabled() {
        return ENABLED;
    }

    public static String dim(String text) {
        return wrap("\u001B[2m", text);
    }

    public static String bold(String text) {
        return wrap("\u001B[1m", text);
    }

    public static String accent(String text) {
        return wrap("\u001B[33m", text);
    }

    public static String error(String text) {
        return wrap("\u001B[31m", text);
    }

    public static String ok(String text) {
        return wrap("\u001B[32m", text);
    }

    private static String wrap(String code, String text) {
        return ENABLED ? code + text + "\u001B[0m" : text;
    }
}
