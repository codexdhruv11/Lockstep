package com.lockstep.runner.db;

import java.net.URI;
import java.net.URISyntaxException;

public final class ConnectionStrings {
    private ConnectionStrings() {}

    public record JdbcTarget(String url, String username, String password) {}

    public static JdbcTarget toJdbc(String conn, String driver) {
        String trimmed = conn == null ? "" : conn.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("connection string must not be empty");
        }
        if (trimmed.startsWith("jdbc:")) {
            return new JdbcTarget(trimmed, null, null);
        }
        return switch (normalizeDriver(driver)) {
            case "postgresql" -> fromUri(trimmed, "postgresql");
            case "mysql" -> fromUri(trimmed, "mysql");
            case "sqlite" -> new JdbcTarget("jdbc:sqlite:" + stripScheme(trimmed), null, null);
            default -> throw new IllegalArgumentException(
                    "unsupported driver \"" + driver + "\" (supported: postgres, mysql, sqlite)");
        };
    }

    public static String normalizeDriver(String driver) {
        String lower = driver == null ? "" : driver.trim().toLowerCase();
        return switch (lower) {
            case "postgres", "postgresql", "pgx" -> "postgresql";
            case "mysql" -> "mysql";
            case "sqlite", "sqlite3" -> "sqlite";
            default -> lower;
        };
    }

    private static JdbcTarget fromUri(String conn, String subProtocol) {
        URI uri;
        try {
            uri = new URI(conn);
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("connection string is not a valid URI: " + e.getMessage(), e);
        }
        String username = null;
        String password = null;
        String userInfo = uri.getUserInfo();
        if (userInfo != null && !userInfo.isEmpty()) {
            int separator = userInfo.indexOf(':');
            username = separator < 0 ? userInfo : userInfo.substring(0, separator);
            password = separator < 0 ? null : userInfo.substring(separator + 1);
        }
        StringBuilder url = new StringBuilder("jdbc:").append(subProtocol).append("://");
        url.append(uri.getHost() == null ? "localhost" : uri.getHost());
        if (uri.getPort() > 0) {
            url.append(':').append(uri.getPort());
        }
        url.append(uri.getPath() == null ? "" : uri.getPath());
        if (uri.getQuery() != null && !uri.getQuery().isEmpty()) {
            url.append('?').append(uri.getQuery());
        }
        return new JdbcTarget(url.toString(), username, password);
    }

    private static String stripScheme(String conn) {
        if (conn.startsWith("sqlite://")) {
            return conn.substring("sqlite://".length());
        }
        if (conn.startsWith("sqlite:")) {
            return conn.substring("sqlite:".length());
        }
        if (conn.startsWith("file:")) {
            return conn.substring("file:".length());
        }
        return conn;
    }
}
