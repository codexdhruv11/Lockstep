package com.lockstep.demo;

import com.lockstep.runner.db.ConnectionStrings;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.ThreadLocalRandom;

public final class DbSeeder {
    private static final int BATCH_SIZE = 10_000;

    private DbSeeder() {}

    @FunctionalInterface
    public interface Progress {
        void rowsInserted(long total);
    }

    public static long seed(String conn, String driver, long rows, Progress progress) throws SQLException {
        ConnectionStrings.JdbcTarget target = ConnectionStrings.toJdbc(conn, driver);
        try (Connection connection = target.username() == null
                ? DriverManager.getConnection(target.url())
                : DriverManager.getConnection(target.url(), target.username(), target.password())) {
            createTable(connection, ConnectionStrings.normalizeDriver(driver));

            boolean autoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            long inserted = 0;
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO orders (customer, amount) VALUES (?, ?)")) {
                for (long row = 1; row <= rows; row++) {
                    statement.setString(1, "customer-" + ThreadLocalRandom.current().nextInt(10_000));
                    statement.setInt(2, ThreadLocalRandom.current().nextInt(1, 100_000));
                    statement.addBatch();
                    if (row % BATCH_SIZE == 0) {
                        statement.executeBatch();
                        connection.commit();
                        inserted = row;
                        if (progress != null) {
                            progress.rowsInserted(inserted);
                        }
                    }
                }
                statement.executeBatch();
                connection.commit();
                inserted = rows;
            } finally {
                connection.setAutoCommit(autoCommit);
            }
            if (progress != null) {
                progress.rowsInserted(inserted);
            }
            return inserted;
        }
    }

    private static void createTable(Connection connection, String driver) throws SQLException {
        String key = switch (driver) {
            case "postgresql" -> "id SERIAL PRIMARY KEY";
            case "mysql" -> "id INT AUTO_INCREMENT PRIMARY KEY";
            default -> "id INTEGER PRIMARY KEY";
        };
        try (Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE IF NOT EXISTS orders (" + key
                    + ", customer VARCHAR(64), amount INT)");
        }
    }
}
