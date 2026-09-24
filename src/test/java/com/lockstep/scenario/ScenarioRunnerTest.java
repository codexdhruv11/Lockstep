package com.lockstep.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.lockstep.config.ScenarioConfig;
import com.lockstep.core.PacedLoop;
import com.lockstep.core.RunContext;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

final class ScenarioRunnerTest {
    private static final long MS = 1_000_000L;
    private static final long SECOND = 1_000_000_000L;

    private DemoServer server;

    @BeforeEach
    void start() throws Exception {
        server = new DemoServer();
    }

    @AfterEach
    void stop() {
        server.close();
    }

    private String url(String path) {
        return "http://127.0.0.1:" + server.port() + path;
    }

    private ScenarioConfig.StepSpec step(String method, String path, String body,
            Map<String, String> headers, Map<String, String> extract) {
        return new ScenarioConfig.StepSpec(method, url(path), body, headers, extract);
    }

    private ScenarioConfig loginFlow(int weight) {
        return new ScenarioConfig("login-flow", weight, List.of(
                step("POST", "/api/login", "{\"user\":\"alice\"}",
                        Map.of("Content-Type", "application/json"), Map.of("token", "$.token")),
                step("GET", "/api/me", null,
                        Map.of("Authorization", "Bearer {{token}}"), Map.of())));
    }

    @Test
    void carriesACapturedTokenIntoTheNextStep() {
        try (ScenarioRunner runner = ScenarioRunner.create(List.of(loginFlow(1)), 20)) {
            PacedLoop.LoopResult result = runner.run(RunContext.startingNow(SECOND, 200 * MS, 0, 4));

            assertThat(result.executedCount()).isGreaterThan(5);
            assertThat(result.series().errorCount())
                    .withFailMessage("expected no failures, last was: %s", runner.lastFailure())
                    .isZero();

            assertThat(server.authHeaders()).isNotEmpty().allMatch("Bearer tok-123"::equals);
        }
    }

    @Test
    void oneJourneyIsOneRecordedOperationNotOneRequestPerStep() {
        try (ScenarioRunner runner = ScenarioRunner.create(List.of(loginFlow(1)), 20)) {
            PacedLoop.LoopResult result = runner.run(RunContext.startingNow(SECOND, 200 * MS, 0, 4));

            assertThat(server.paths()).hasSize((int) result.executedCount() * 2);
            assertThat(server.logins()).isEqualTo(result.executedCount());
        }
    }

    @Test
    void aFailedStepEndsItsJourneyRatherThanSendingTheRestBlind() {
        server.loginFails = true;

        try (ScenarioRunner runner = ScenarioRunner.create(List.of(loginFlow(1)), 20)) {
            PacedLoop.LoopResult result = runner.run(RunContext.startingNow(SECOND, 200 * MS, 0, 4));

            assertThat(result.executedCount()).isGreaterThan(0);
            assertThat(result.series().errorCount()).isEqualTo(result.executedCount());

            assertThat(server.paths()).isNotEmpty().allMatch("/api/login"::equals);
            assertThat(runner.lastFailure()).contains("step 0").contains("HTTP 500");
            assertThat(result.drainedCleanly()).isTrue();
        }
    }

    @Test
    void theFailingStatusIsRecordedSoTheReportCanShowIt() {
        server.loginFails = true;
        try (ScenarioRunner runner = ScenarioRunner.create(List.of(loginFlow(1)), 20)) {
            PacedLoop.LoopResult result = runner.run(RunContext.startingNow(500 * MS, 100 * MS, 0, 4));
            assertThat(result.series().statusCounts()).containsOnlyKeys(500);
        }
    }

    @Test
    void variablesInterpolateIntoUrlsAsWellAsHeaders() {
        ScenarioConfig checkout = new ScenarioConfig("checkout", 1, List.of(
                step("POST", "/api/login", "{}", Map.of(), Map.of("token", "$.token")),
                step("GET", "/api/checkout?token={{token}}", null, Map.of(), Map.of())));

        try (ScenarioRunner runner = ScenarioRunner.create(List.of(checkout), 20)) {
            PacedLoop.LoopResult result = runner.run(RunContext.startingNow(SECOND, 200 * MS, 0, 4));

            assertThat(result.series().errorCount())
                    .withFailMessage("last failure: %s", runner.lastFailure())
                    .isZero();
            assertThat(result.executedCount()).isGreaterThan(5);
        }
    }

    @Test
    void weightsDecideHowOftenEachJourneyRuns() {
        ScenarioConfig browse = new ScenarioConfig("browse", 90,
                List.of(step("GET", "/api/products", null, Map.of(), Map.of())));
        ScenarioConfig login = new ScenarioConfig("login-flow", 10,
                List.of(step("POST", "/api/login", "{}", Map.of(), Map.of())));

        try (ScenarioRunner runner = ScenarioRunner.create(List.of(browse, login), 200)) {
            PacedLoop.LoopResult result = runner.run(RunContext.startingNow(2 * SECOND, 200 * MS, 0, 8));

            long products = server.paths().stream().filter("/api/products"::equals).count();
            long logins = server.logins();
            assertThat(result.executedCount()).isGreaterThan(50);

            assertThat(products).isGreaterThan(logins * 3);
        }
    }

    @Test
    void anUnweightedScenarioIsAnEqualParticipantNotAnIgnoredOne() {
        ScenarioConfig a = new ScenarioConfig("a", 0,
                List.of(step("GET", "/api/products", null, Map.of(), Map.of())));
        ScenarioConfig b = new ScenarioConfig("b", 0,
                List.of(step("POST", "/api/login", "{}", Map.of(), Map.of())));

        try (ScenarioRunner runner = ScenarioRunner.create(List.of(a, b), 100)) {
            runner.run(RunContext.startingNow(SECOND, 200 * MS, 0, 8));

            assertThat(server.logins()).isGreaterThan(0);
            assertThat(server.paths()).anyMatch("/api/products"::equals);
        }
    }

    @Test
    void aMissingCaptureFailsTheJourneyInsteadOfSendingAnEmptyToken() {
        ScenarioConfig broken = new ScenarioConfig("broken", 1, List.of(
                step("POST", "/api/login", "{}", Map.of(), Map.of("missing", "$.nope")),
                step("GET", "/api/checkout?token={{missing}}", null, Map.of(), Map.of())));

        try (ScenarioRunner runner = ScenarioRunner.create(List.of(broken), 20)) {
            PacedLoop.LoopResult result = runner.run(RunContext.startingNow(500 * MS, 100 * MS, 0, 4));

            assertThat(result.series().errorCount()).isEqualTo(result.executedCount());
            assertThat(runner.lastFailure()).contains("unresolved").contains("{{missing}}");
        }
    }

    @Test
    void capturedVariablesDoNotLeakBetweenJourneys() {
        ScenarioConfig capturer = new ScenarioConfig("capturer", 50, List.of(
                step("POST", "/api/login", "{}", Map.of(), Map.of("token", "$.token"))));
        ScenarioConfig borrower = new ScenarioConfig("borrower", 50, List.of(
                step("GET", "/api/checkout?token={{token}}", null, Map.of(), Map.of())));

        try (ScenarioRunner runner = ScenarioRunner.create(List.of(capturer, borrower), 40)) {
            PacedLoop.LoopResult result = runner.run(RunContext.startingNow(SECOND, 200 * MS, 0, 4));

            assertThat(result.executedCount()).isGreaterThan(10);

            assertThat(result.series().errorCount()).isGreaterThan(0);
            assertThat(runner.lastFailure()).contains("unresolved").contains("{{token}}");
            var borrowerSeries = runner.journeySeries().get("borrower");
            assertThat(borrowerSeries.errorCount()).isEqualTo(borrowerSeries.totalCount());

            assertThat(runner.journeySeries().get("capturer").errorCount()).isZero();
        }
    }

    @Test
    void journeyLatencyIsMeasuredFromTheScheduledInstantNotFromWorkerPickup() {
        ScenarioConfig slow = new ScenarioConfig("slow", 1,
                List.of(step("GET", "/api/slow-step", null, Map.of(), Map.of())));

        try (ScenarioRunner runner = ScenarioRunner.create(List.of(slow), 60)) {
            var context = RunContext.startingAfterSetup(3 * SECOND, 500 * MS, 0, 4);
            PacedLoop.LoopResult result = runner.run(context);

            long truth = result.series().summarize("x", 3 * SECOND).p99Nanos();
            long journey = runner.journeySeries().get("slow").summarize("x", 3 * SECOND).p99Nanos();

            assertThat(result.shedCount()).isGreaterThan(0);
            assertThat(journey).isCloseTo(truth, org.assertj.core.data.Percentage.withPercentage(15));
            assertThat(journey).isGreaterThan(400 * MS);
        }
    }

    @Test
    void anAbsoluteUrlIsRequiredAtStartupWhenItHasNoPlaceholder() {
        ScenarioConfig bad = new ScenarioConfig("bad", 1, List.of(
                new ScenarioConfig.StepSpec("GET", "/api/products", null, Map.of(), Map.of())));

        assertThatThrownBy(() -> ScenarioRunner.create(List.of(bad), 10))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must be absolute");
    }

    @Test
    void theRunnerIsNamedScenarioSoReportsCanLabelIt() {
        try (ScenarioRunner runner = ScenarioRunner.create(List.of(loginFlow(1)), 10)) {
            assertThat(runner.name()).isEqualTo("scenario");
            assertThat(runner.journeys()).hasSize(1);
            assertThat(runner.journeys().get(0).steps()).hasSize(2);
        }
    }
}
