package com.lockstep.verify;

import static org.assertj.core.api.Assertions.assertThat;

import com.lockstep.config.HttpConfig;
import com.lockstep.core.PacedLoop;
import com.lockstep.core.RunContext;
import com.lockstep.runner.http.HttpRunner;
import com.lockstep.runner.http.RequestTemplate;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;

/**
 * Checks per-request templating against a target that behaves like a unique index.
 *
 * <p>The oracle is the fixture's own rule: a server that remembers every body it has seen and
 * answers 409 to a repeat. With a static body the first request succeeds and every other one
 * conflicts - a known, exact expectation. With a placeholder, all of them must succeed. There is
 * no measurement and no tolerance here; the count either is or is not the request count.
 *
 * <p>This is what the feature is for. On a real target, creating a signal required
 * {@code (influencer_id, influencer_posted_at, trading_pair)} to be unique, so driving it meant
 * generating twenty thousand distinct bodies into a config that then exceeded the parser's size
 * limit. The whole workaround collapses into one {{now}}.
 */
final class RequestTemplateOracleTest {
    private static final long SECOND = 1_000_000_000L;

    private static final List<String> LEDGER = new ArrayList<>();

    private static void record(String claim, Object expected, Object reported, boolean ok) {
        LEDGER.add("  %-40s expected %-16s reported %-16s %s".formatted(
                claim, expected, reported, ok ? "MATCH" : "MISMATCH"));
    }

    private static void printLedger(String fixture) {
        System.out.println("\n=== oracle verification · " + fixture + " ===");
        LEDGER.forEach(System.out::println);
        LEDGER.clear();
    }

    /** A server that accepts each distinct body once, like a unique index. */
    private record Target(HttpServer server, int port, Set<String> seen, List<String> bodies)
            implements AutoCloseable {
        @Override
        public void close() {
            server.stop(0);
        }
    }

    private static Target start() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        Set<String> seen = ConcurrentHashMap.newKeySet();
        List<String> bodies = Collections.synchronizedList(new ArrayList<>());
        server.createContext("/create", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(),
                    StandardCharsets.UTF_8);
            bodies.add(body);
            int status = seen.add(body) ? 201 : 409;
            byte[] out = "{}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, out.length);
            try (OutputStream o = exchange.getResponseBody()) {
                o.write(out);
            }
        });
        server.start();
        return new Target(server, server.getAddress().getPort(), seen, bodies);
    }

    private static PacedLoop.LoopResult drive(int port, String body, int rate, long nanos) {
        HttpConfig config = new HttpConfig(new HttpConfig.Target("POST",
                "http://localhost:" + port + "/create", body, Map.of(), 1), rate);
        try (HttpRunner runner = HttpRunner.create(config, 20)) {
            return runner.run(RunContext.startingNow(nanos, SECOND, 0, 20));
        }
    }

    // --- the problem, reproduced -----------------------------------------------------------

    @Test
    void aStaticBodyIsAcceptedOnceAndConflictsForeverAfter() throws Exception {
        try (Target target = start()) {
            PacedLoop.LoopResult result =
                    drive(target.port(), "{\"posted_at\":\"fixed\"}", 20, 2 * SECOND);
            long sent = result.executedCount();
            long distinct = target.seen().size();

            record("requests sent", "> 10", sent, sent > 10);
            record("distinct bodies the target saw", 1, distinct, distinct == 1);
            record("conflicts", "all but the first", (sent - 1) + " of " + sent, sent > 1);
            printLedger("static body against a unique-index target");

            assertThat(distinct)
                    .withFailMessage("""
                            a static body means every request carries identical bytes, so a target \
                            that enforces uniqueness accepts exactly one. This is why write paths \
                            could not be load-tested at all.""")
                    .isEqualTo(1);
        }
    }

    // --- the fix, verified -----------------------------------------------------------------

    @Test
    void aSeqPlaceholderMakesEveryRequestUnique() throws Exception {
        try (Target target = start()) {
            PacedLoop.LoopResult result =
                    drive(target.port(), "{\"posted_at\":\"{{seq}}\"}", 20, 2 * SECOND);
            long sent = result.executedCount();
            long distinct = target.seen().size();

            record("requests sent", "> 10", sent, sent > 10);
            record("distinct bodies", "one per request", distinct + " of " + sent,
                    distinct == sent);
            record("sequence starts at 1", true,
                    target.bodies().contains("{\"posted_at\":\"1\"}"),
                    target.bodies().contains("{\"posted_at\":\"1\"}"));
            printLedger("{{seq}} against a unique-index target");

            assertThat(distinct)
                    .withFailMessage("""
                            every request must carry its own value, so the target - which accepts \
                            each distinct body exactly once - must see as many distinct bodies as \
                            requests. Saw %d distinct for %d requests.""", distinct, sent)
                    .isEqualTo(sent);
        }
    }

    @Test
    void aUuidPlaceholderIsAlsoUniquePerRequest() throws Exception {
        try (Target target = start()) {
            PacedLoop.LoopResult result =
                    drive(target.port(), "{\"id\":\"{{uuid}}\"}", 20, 2 * SECOND);

            assertThat(target.seen().size()).isEqualTo(result.executedCount());
            assertThat(result.executedCount()).isGreaterThan(10);
        }
    }

    @Test
    void aTemplatedUrlIsRenderedToo() throws Exception {
        try (Target target = start()) {
            HttpServer echo = HttpServer.create(new InetSocketAddress(0), 0);
            echo.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
            List<String> paths = Collections.synchronizedList(new ArrayList<>());
            echo.createContext("/", exchange -> {
                paths.add(exchange.getRequestURI().toString());
                exchange.sendResponseHeaders(200, 0);
                exchange.getResponseBody().close();
            });
            echo.start();
            try {
                HttpConfig config = new HttpConfig(new HttpConfig.Target("GET",
                        "http://localhost:" + echo.getAddress().getPort() + "/item/{{seq}}",
                        null, Map.of(), 1), 20);
                try (HttpRunner runner = HttpRunner.create(config, 10)) {
                    runner.run(RunContext.startingNow(SECOND, SECOND, 0, 10));
                }
                long distinct = paths.stream().distinct().count();
                record("distinct paths requested", "one per request",
                        distinct + " of " + paths.size(), distinct == paths.size());
                record("path shape", "/item/1", paths.isEmpty() ? "-" : paths.get(0),
                        paths.stream().anyMatch(p -> p.equals("/item/1")));
                printLedger("{{seq}} in the URL");

                assertThat(distinct)
                        .withFailMessage("a placeholder in the URL must be rendered as well, or "
                                + "per-resource load cannot be driven: paths were %s", paths)
                        .isEqualTo(paths.size());
            } finally {
                echo.stop(0);
            }
        }
    }

    // --- the renderer's own rules ----------------------------------------------------------

    @Test
    void renderingIsExactAndLeavesEverythingElseAlone() {
        assertThat(RequestTemplate.render("{\"n\":{{seq}}}", 42)).isEqualTo("{\"n\":42}");
        assertThat(RequestTemplate.render("a {{seq}} b {{seq}} c", 7))
                .withFailMessage("one sequence value per request, so both read the same")
                .isEqualTo("a 7 b 7 c");
        assertThat(RequestTemplate.render("no placeholder here", 1))
                .isEqualTo("no placeholder here");
        assertThat(RequestTemplate.render(null, 1)).isNull();
        assertThat(RequestTemplate.render("{{unknown}}", 1))
                .withFailMessage("an unrecognised placeholder must be left exactly as written "
                        + "rather than silently blanked, which would send a body nobody meant")
                .isEqualTo("{{unknown}}");
    }

    @Test
    void twoUuidsInOneBodyDifferButTwoSeqsDoNot() {
        String rendered = RequestTemplate.render("{\"a\":\"{{uuid}}\",\"b\":\"{{uuid}}\"}", 1);
        String first = rendered.split("\"")[3];
        String second = rendered.split("\"")[7];
        assertThat(first)
                .withFailMessage("each {{uuid}} is its own value; use {{seq}} when two fields "
                        + "must match")
                .isNotEqualTo(second);
    }

    @Test
    void isTemplatedRecognisesOnlyWhatItCanRender() {
        assertThat(RequestTemplate.isTemplated("{{seq}}")).isTrue();
        assertThat(RequestTemplate.isTemplated("{{uuid}}")).isTrue();
        assertThat(RequestTemplate.isTemplated("{{now}}")).isTrue();
        assertThat(RequestTemplate.isTemplated("{{nope}}"))
                .withFailMessage("claiming to template something it cannot render would make "
                        + "every request pay the rebuild cost for nothing")
                .isFalse();
        assertThat(RequestTemplate.isTemplated("plain")).isFalse();
        assertThat(RequestTemplate.isTemplated(null)).isFalse();
    }

    @Test
    void nowIsAnInstantThatParsesBack() {
        String rendered = RequestTemplate.render("{{now}}", 1);
        assertThat(java.time.Instant.parse(rendered)).isNotNull();
    }
}
