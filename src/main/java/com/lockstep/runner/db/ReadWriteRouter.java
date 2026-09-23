package com.lockstep.runner.db;

import com.lockstep.config.QuerySpec;
import java.util.Set;

public final class ReadWriteRouter {
    private static final Set<String> READ_PREFIXES = Set.of(
            "select", "with", "show", "explain", "describe", "desc", "pragma", "values", "table");

    private ReadWriteRouter() {}

    public static boolean isRead(QuerySpec query) {
        String declared = query.type() == null ? "" : query.type().trim().toLowerCase();
        return switch (declared) {
            case "read" -> true;
            case "write" -> false;
            default -> looksLikeRead(query.query());
        };
    }

    static boolean looksLikeRead(String sql) {
        String firstWord = firstWordOf(sql);
        return READ_PREFIXES.contains(firstWord);
    }

    private static String firstWordOf(String sql) {
        if (sql == null) {
            return "";
        }
        int i = 0;
        int n = sql.length();
        while (i < n) {
            char c = sql.charAt(i);
            if (Character.isWhitespace(c) || c == '(') {
                i++;
            } else if (c == '-' && i + 1 < n && sql.charAt(i + 1) == '-') {
                int newline = sql.indexOf('\n', i);
                if (newline < 0) {
                    return "";
                }
                i = newline + 1;
            } else if (c == '/' && i + 1 < n && sql.charAt(i + 1) == '*') {
                int end = sql.indexOf("*/", i + 2);
                if (end < 0) {
                    return "";
                }
                i = end + 2;
            } else {
                break;
            }
        }
        int start = i;
        while (i < n && Character.isLetter(sql.charAt(i))) {
            i++;
        }
        return sql.substring(start, i).toLowerCase();
    }
}
