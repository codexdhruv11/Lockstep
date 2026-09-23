package com.lockstep.runner.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.lockstep.config.QuerySpec;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

final class RoutingAndConnectionStringTest {
    private static QuerySpec query(String sql, String type) {
        return new QuerySpec(sql, 1, type, List.of());
    }

    @ParameterizedTest
    @CsvSource({
        "SELECT * FROM orders,            true",
        "  select 1,                      true",
        "WITH x AS (SELECT 1) SELECT * FROM x, true",
        "SHOW TABLES,                     true",
        "EXPLAIN SELECT 1,                true",
        "PRAGMA table_info(orders),       true",
        "INSERT INTO orders VALUES (1),   false",
        "UPDATE orders SET amount = 1,    false",
        "DELETE FROM orders,              false",
        "CREATE TABLE t (id INT),         false",
        "CALL some_procedure(),           false",
    })
    void heuristicRoutesUntypedQueriesByTheirFirstKeyword(String sql, boolean expectedRead) {
        assertThat(ReadWriteRouter.isRead(query(sql, null))).isEqualTo(expectedRead);
    }

    @Test
    void declaredTypeBeatsTheHeuristic() {
        assertThat(ReadWriteRouter.isRead(query("INSERT INTO t VALUES (1)", "read"))).isTrue();
        assertThat(ReadWriteRouter.isRead(query("SELECT 1", "write"))).isFalse();
        assertThat(ReadWriteRouter.isRead(query("SELECT 1", "  READ  "))).isTrue();
    }

    @Test
    void leadingCommentsDoNotDecideTheRoute() {
        assertThat(ReadWriteRouter.isRead(query("-- fetch the totals\nSELECT count(*) FROM orders", null)))
                .isTrue();
        assertThat(ReadWriteRouter.isRead(query("/* nightly */ INSERT INTO orders VALUES (1)", null)))
                .isFalse();
    }

    @Test
    void unknownStatementsAreTreatedAsWrites() {
        assertThat(ReadWriteRouter.isRead(query("MERGE INTO t USING s ON (1=1)", null))).isFalse();
        assertThat(ReadWriteRouter.isRead(query("", null))).isFalse();
    }

    @Test
    void goStylePostgresDsnBecomesAJdbcUrlWithCredentialsSplitOut() {
        var target = ConnectionStrings.toJdbc(
                "postgres://us:2@localhost:5432/testDB?sslmode=disable", "postgres");

        assertThat(target.url()).isEqualTo("jdbc:postgresql://localhost:5432/testDB?sslmode=disable");
        assertThat(target.username()).isEqualTo("us");
        assertThat(target.password()).isEqualTo("2");
    }

    @Test
    void mysqlAndSqliteFormsAreTranslated() {
        assertThat(ConnectionStrings.toJdbc("mysql://root:pw@db:3306/app", "mysql").url())
                .isEqualTo("jdbc:mysql://db:3306/app");
        assertThat(ConnectionStrings.toJdbc("/tmp/reference-load.db", "sqlite").url())
                .isEqualTo("jdbc:sqlite:/tmp/reference-load.db");
        assertThat(ConnectionStrings.toJdbc("sqlite:///tmp/x.db", "sqlite").url())
                .isEqualTo("jdbc:sqlite:/tmp/x.db");
    }

    @Test
    void anExplicitJdbcUrlIsPassedThroughUntouched() {
        var target = ConnectionStrings.toJdbc("jdbc:postgresql://host/db?ssl=true", "postgres");
        assertThat(target.url()).isEqualTo("jdbc:postgresql://host/db?ssl=true");
        assertThat(target.username()).isNull();
    }

    @Test
    void driverAliasesAreAccepted() {
        assertThat(ConnectionStrings.normalizeDriver("postgres")).isEqualTo("postgresql");
        assertThat(ConnectionStrings.normalizeDriver("pgx")).isEqualTo("postgresql");
        assertThat(ConnectionStrings.normalizeDriver("sqlite3")).isEqualTo("sqlite");
        assertThat(ConnectionStrings.normalizeDriver(" MySQL ")).isEqualTo("mysql");
    }

    @Test
    void unsupportedDriversAndEmptyConnectionsFailLoudly() {
        assertThatThrownBy(() -> ConnectionStrings.toJdbc("whatever://x", "oracle"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unsupported driver");
        assertThatThrownBy(() -> ConnectionStrings.toJdbc("  ", "postgres"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not be empty");
    }
}
