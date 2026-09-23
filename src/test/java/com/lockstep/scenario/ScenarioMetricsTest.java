package com.lockstep.scenario;

import static org.assertj.core.api.Assertions.assertThat;

import com.lockstep.config.ScenarioConfig;
import com.lockstep.core.PacedLoop;
import com.lockstep.core.RunContext;
import com.lockstep.stats.Bucket;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

final class ScenarioMetricsTest {
    private static final long MS = 1_000_000L;
    private static final long SECOND = 1_000_000_000L;

    private SlowStepServer server;

    @BeforeEach
    void start() throws Exception {
        server = new SlowStepServer();
    }

    @AfterEach
    void stop() {
        server.close();
    }

    private ScenarioConfig.StepSpec step(String path) {
        return new ScenarioConfig.StepSpec("GET", "http://127.0.0.1:" + server.port() + path,
                null, Map.of(), Map.of());
    }

    @Test
    void perStepMetricsShowWhichRequestInTheFlowIsSlow() {
        ScenarioConfig flow = new ScenarioConfig("flow", 1, List.of(step("/fast"), step("/slow")));

        try (ScenarioRunner runner = ScenarioRunner.create(List.of(flow), 10)) {
            runner.run(RunContext.startingNow(2 * SECOND, SECOND, 0, 4));

            Map<String, com.lockstep.stats.BucketSeries> steps = runner.stepSeries();
            assertThat(steps).hasSize(2);

            var fast = steps.entrySet().stream().filter(e -> e.getKey().contains("/fast"))
                    .findFirst().orElseThrow().getValue().summarize("fast", 2 * SECOND);
            var slow = steps.entrySet().stream().filter(e -> e.getKey().contains("/slow"))
                    .findFirst().orElseThrow().getValue().summarize("slow", 2 * SECOND);

            assertThat(slow.p50Nanos()).isGreaterThan(100 * MS);
            assertThat(fast.p50Nanos()).isLessThan(50 * MS);
            assertThat(slow.p50Nanos()).isGreaterThan(fast.p50Nanos() * 2);
        }
    }

    @Test
    void stepLabelsIdentifyJourneyPositionMethodAndPath() {
        ScenarioConfig flow = new ScenarioConfig("checkout", 1, List.of(step("/fast"), step("/slow")));

        try (ScenarioRunner runner = ScenarioRunner.create(List.of(flow), 10)) {
            runner.run(RunContext.startingNow(SECOND, SECOND, 0, 2));
            assertThat(runner.stepSeries().keySet())
                    .containsExactly("checkout · 0 GET /fast", "checkout · 1 GET /slow");
        }
    }

    @Test
    void aStepIsTimedByItsOwnDurationNotFromTheJourneyStart() {
        ScenarioConfig flow = new ScenarioConfig("flow", 1, List.of(step("/slow"), step("/fast")));

        try (ScenarioRunner runner = ScenarioRunner.create(List.of(flow), 10)) {
            runner.run(RunContext.startingNow(2 * SECOND, SECOND, 0, 4));

            var second = runner.stepSeries().entrySet().stream()
                    .filter(e -> e.getKey().contains("1 GET /fast"))
                    .findFirst().orElseThrow().getValue().summarize("fast", 2 * SECOND);

            assertThat(second.p50Nanos()).isLessThan(50 * MS);
        }
    }

    @Test
    void perJourneyMetricsAreKeptSeparatelyPerScenario() {
        ScenarioConfig quick = new ScenarioConfig("quick", 50, List.of(step("/fast")));
        ScenarioConfig slow = new ScenarioConfig("slow-flow", 50, List.of(step("/slow")));

        try (ScenarioRunner runner = ScenarioRunner.create(List.of(quick, slow), 40)) {
            runner.run(RunContext.startingNow(2 * SECOND, SECOND, 0, 8));

            var journeys = runner.journeySeries();
            assertThat(journeys).containsOnlyKeys("quick", "slow-flow");
            assertThat(journeys.get("slow-flow").summarize("s", 2 * SECOND).p50Nanos())
                    .isGreaterThan(journeys.get("quick").summarize("q", 2 * SECOND).p50Nanos());
        }
    }

    @Test
    void theAppTimelineTakesTheWorstJourneyPerBucketNotTheAverage() {
        ScenarioConfig quick = new ScenarioConfig("quick", 50, List.of(step("/fast")));
        ScenarioConfig slow = new ScenarioConfig("slow-flow", 50, List.of(step("/slow")));

        try (ScenarioRunner runner = ScenarioRunner.create(List.of(quick, slow), 40)) {
            PacedLoop.LoopResult result = runner.run(RunContext.startingNow(2 * SECOND, SECOND, 0, 8));

            List<Bucket> timeline = runner.appTimeline();
            assertThat(timeline).isNotEmpty();

            assertThat(timeline.get(0).p99Nanos()).isGreaterThan(100 * MS);

            long totalCounted = timeline.stream().mapToLong(Bucket::count).sum();
            assertThat(totalCounted).isEqualTo(result.executedCount());
        }
    }

    @Test
    void theAppTimelineIsOrderedAndSkipsEmptyBuckets() {
        ScenarioConfig flow = new ScenarioConfig("flow", 1, List.of(step("/fast")));

        try (ScenarioRunner runner = ScenarioRunner.create(List.of(flow), 20)) {
            runner.run(RunContext.startingNow(3 * SECOND, SECOND, 0, 4));

            List<Bucket> timeline = runner.appTimeline();
            assertThat(timeline).isNotEmpty().allMatch(bucket -> bucket.count() > 0);
            assertThat(timeline).isSortedAccordingTo(
                    java.util.Comparator.comparingInt(Bucket::index));
        }
    }

    @Test
    void afailedStepStillRecordsItsOwnTimingAndStatus() {
        ScenarioConfig flow = new ScenarioConfig("flow", 1,
                List.of(step("/fast"), step("/boom"), step("/fast")));

        try (ScenarioRunner runner = ScenarioRunner.create(List.of(flow), 10)) {
            runner.run(RunContext.startingNow(SECOND, SECOND, 0, 4));

            var steps = runner.stepSeries();

            assertThat(steps.keySet()).anyMatch(key -> key.contains("1 GET /boom"));
            var boom = steps.entrySet().stream().filter(e -> e.getKey().contains("/boom"))
                    .findFirst().orElseThrow().getValue();
            assertThat(boom.errorCount()).isEqualTo(boom.totalCount());
            assertThat(boom.statusCounts()).containsOnlyKeys(500);
            assertThat(steps.keySet()).noneMatch(key -> key.contains("2 GET /fast"));
        }
    }
}
