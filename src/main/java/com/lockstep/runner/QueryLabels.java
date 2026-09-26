package com.lockstep.runner;

import com.lockstep.config.QuerySpec;
import java.util.ArrayList;
import java.util.List;

public final class QueryLabels {
    static final int MAX_SQL_LENGTH = 60;

    private static final String ELLIPSIS = "…";

    private QueryLabels() {}

    public static List<String> forQueries(List<QuerySpec> queries) {
        List<String> labels = new ArrayList<>(queries.size());
        for (int i = 0; i < queries.size(); i++) {
            labels.add(of(i, queries.get(i).query()));
        }
        return List.copyOf(labels);
    }

    public static String of(int position, String sql) {
        return of("q", position, sql);
    }

    public static String of(String prefix, int position, String text) {
        return prefix + (position + 1) + " " + squash(text);
    }

    public static List<String> forTargets(List<String> methods, List<String> urls) {
        boolean sameHost = urls.stream().map(QueryLabels::authorityOf).distinct().count() <= 1;
        List<String> labels = new ArrayList<>(urls.size());
        for (int i = 0; i < urls.size(); i++) {
            String method = methods.get(i) == null || methods.get(i).isBlank()
                    ? "GET" : methods.get(i).toUpperCase();
            labels.add(of("t", i, method + " " + (sameHost ? pathOf(urls.get(i)) : stripScheme(urls.get(i)))));
        }
        return List.copyOf(labels);
    }

    private static String authorityOf(String url) {
        String withoutScheme = stripScheme(url);
        int slash = withoutScheme.indexOf('/');
        return slash < 0 ? withoutScheme : withoutScheme.substring(0, slash);
    }

    private static String stripScheme(String url) {
        if (url == null) {
            return "";
        }
        int schemeEnd = url.indexOf("://");
        return schemeEnd < 0 ? url : url.substring(schemeEnd + 3);
    }

    private static String pathOf(String url) {
        String withoutScheme = stripScheme(url);
        int slash = withoutScheme.indexOf('/');
        return slash < 0 ? "/" : withoutScheme.substring(slash);
    }

    private static String squash(String sql) {
        if (sql == null) {
            return "(no query)";
        }
        String oneLine = sql.replaceAll("\\s+", " ").trim();
        if (oneLine.isEmpty()) {
            return "(no query)";
        }
        if (oneLine.length() <= MAX_SQL_LENGTH) {
            return oneLine;
        }
        return oneLine.substring(0, MAX_SQL_LENGTH - 1).stripTrailing() + ELLIPSIS;
    }
}
