package com.lockstep.demo;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class DemoHelpersTest {
    @TempDir
    Path tempDir;

    private static HttpResponse<String> get(String url, String... headers) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url));
        for (int i = 0; i + 1 < headers.length; i += 2) {
            builder.header(headers[i], headers[i + 1]);
        }
        try (HttpClient client = HttpClient.newHttpClient()) {
            return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        }
    }

    @Test
    void theDemoServerSupportsAWholeLoginJourney() throws Exception {
        try (DemoServer server = DemoServer.start(0)) {
            String base = "http://127.0.0.1:" + server.port();

            HttpResponse<String> login;
            try (HttpClient client = HttpClient.newHttpClient()) {
                login = client.send(HttpRequest.newBuilder(URI.create(base + "/api/login"))
                        .POST(HttpRequest.BodyPublishers.ofString("{\"user\":\"alice\"}")).build(),
                        HttpResponse.BodyHandlers.ofString());
            }
            assertThat(login.statusCode()).isEqualTo(200);
            assertThat(login.body()).contains("\"token\":\"tok-123\"");

            assertThat(get(base + "/api/me").statusCode()).isEqualTo(401);
            assertThat(get(base + "/api/me", "Authorization", "Bearer tok-123").statusCode()).isEqualTo(200);

            assertThat(get(base + "/api/products").body()).contains("\"sku\"");
            assertThat(get(base + "/api/checkout?token=tok-123").statusCode()).isEqualTo(200);
            assertThat(get(base + "/api/checkout").statusCode()).isEqualTo(401);
            assertThat(server.requestCount()).isGreaterThan(4);
        }
    }

    @Test
    void theSlowRouteIsActuallySlowSoASpikeCanBeProducedOnPurpose() throws Exception {
        try (DemoServer server = DemoServer.start(0)) {
            long start = System.nanoTime();
            assertThat(get("http://127.0.0.1:" + server.port() + "/api/slow").statusCode()).isEqualTo(200);
            long elapsedMillis = (System.nanoTime() - start) / 1_000_000;
            assertThat(elapsedMillis).isGreaterThan(200);
        }
    }

    @Test
    void theSeederCreatesTheTableAndInsertsTheRequestedRows() throws Exception {
        String file = tempDir.resolve("seed.db").toString();

        long inserted = DbSeeder.seed(file, "sqlite", 25_000, null);

        assertThat(inserted).isEqualTo(25_000);
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + file);
                Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery("SELECT count(*), count(DISTINCT customer) FROM orders")) {
            rows.next();
            assertThat(rows.getLong(1)).isEqualTo(25_000);

            assertThat(rows.getLong(2)).isGreaterThan(100);
        }
    }

    @Test
    void seedingTwiceAddsToTheTableRatherThanFailingOnIt() throws Exception {
        String file = tempDir.resolve("twice.db").toString();

        DbSeeder.seed(file, "sqlite", 5_000, null);
        DbSeeder.seed(file, "sqlite", 5_000, null);

        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + file);
                Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery("SELECT count(*) FROM orders")) {
            rows.next();
            assertThat(rows.getLong(1)).isEqualTo(10_000);
        }
    }

    @Test
    void progressIsReportedWhileSeedingSoALongRunIsNotSilent() throws Exception {
        java.util.List<Long> updates = new java.util.ArrayList<>();

        DbSeeder.seed(tempDir.resolve("progress.db").toString(), "sqlite", 30_000, updates::add);

        assertThat(updates).hasSizeGreaterThan(2);
        assertThat(updates.get(updates.size() - 1)).isEqualTo(30_000);
    }
}
