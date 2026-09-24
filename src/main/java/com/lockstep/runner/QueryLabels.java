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
        return "q" + (position + 1) + " " + squash(sql);
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
