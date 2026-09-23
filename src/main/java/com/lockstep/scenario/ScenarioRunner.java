package com.lockstep.scenario;

import com.lockstep.config.ScenarioConfig;
import com.lockstep.core.Operation;
import com.lockstep.core.PacedLoop;
import com.lockstep.core.RunContext;
import com.lockstep.core.RunProgress;
import com.lockstep.core.Runner;
import com.lockstep.runner.WeightedPicker;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

public final class ScenarioRunner implements Runner {
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);

    private final HttpClient client;
    private final WeightedPicker<Journey> journeys;
    private final int rate;
    private final AtomicReference<String> lastFailure = new AtomicReference<>();

    private ScenarioRunner(HttpClient client, WeightedPicker<Journey> journeys, int rate) {
        this.client = client;
        this.journeys = journeys;
        this.rate = rate;
    }

    public record Journey(String name, int weight, List<Step> steps) {
        public Journey {
            steps = List.copyOf(steps);
        }
    }

    public record Step(String method, String urlTemplate, String bodyTemplate,
            Map<String, String> headerTemplates, Map<String, String> extract) {
        public Step {
            headerTemplates = Map.copyOf(headerTemplates);
            extract = Map.copyOf(extract);
        }
    }

    public static ScenarioRunner create(List<ScenarioConfig> scenarios, int rate) {
        List<Journey> journeys = new ArrayList<>();
        for (ScenarioConfig scenario : scenarios) {
            List<Step> steps = new ArrayList<>();
            for (ScenarioConfig.StepSpec spec : scenario.steps()) {
                validateUrlTemplate(scenario.name(), spec.url());
                steps.add(new Step(
                        spec.method() == null || spec.method().isBlank() ? "GET" : spec.method().toUpperCase(),
                        spec.url(),
                        spec.body() == null ? "" : spec.body(),
                        spec.headers() == null ? Map.of() : spec.headers(),
                        spec.extract() == null ? Map.of() : spec.extract()));
            }
            journeys.add(new Journey(scenario.name(), scenario.weight(), steps));
        }

        HttpClient client = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(REQUEST_TIMEOUT)
                .build();

        return new ScenarioRunner(client, new WeightedPicker<>(journeys, Journey::weight, true), rate);
    }

    private static void validateUrlTemplate(String scenarioName, String url) {
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("scenario \"" + scenarioName + "\": step url must not be empty");
        }
        if (url.contains("{{")) {
            return;
        }
        URI uri = URI.create(url);
        if (uri.getScheme() == null || uri.getHost() == null) {
            throw new IllegalArgumentException(
                    "scenario \"" + scenarioName + "\": step url must be absolute, got: " + url);
        }
    }

    @Override
    public String name() {
        return "scenario";
    }

    @Override
    public PacedLoop.LoopResult run(RunContext context) {
        return run(context, null);
    }

    public PacedLoop.LoopResult run(RunContext context, RunProgress.Counter progress) {
        return PacedLoop.run(context, rate, this::runJourney, progress);
    }

    private Operation.Outcome runJourney() {
        Journey journey = journeys.pick();
        Map<String, Object> vars = new HashMap<>();
        int lastStatus = 0;

        for (int index = 0; index < journey.steps().size(); index++) {
            Step step = journey.steps().get(index);
            StepOutcome outcome = executeStep(journey, index, step, vars);
            if (!outcome.success()) {
                lastFailure.set(outcome.failure());
                return outcome.status() > 0
                        ? Operation.Outcome.failed(outcome.status(), outcome.failure())
                        : Operation.Outcome.failed(outcome.failure());
            }
            lastStatus = outcome.status();
        }
        return lastStatus > 0 ? Operation.Outcome.ok(lastStatus) : Operation.Outcome.OK;
    }

    private record StepOutcome(boolean success, int status, String failure) {}

    private StepOutcome executeStep(Journey journey, int index, Step step, Map<String, Object> vars) {
        String where = "scenario \"" + journey.name() + "\" step " + index;
        String url = Template.render(step.urlTemplate(), vars);
        if (Template.hasUnresolved(url)) {
            return new StepOutcome(false, 0, where + ": url still contains an unresolved {{variable}}: " + url);
        }
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url)).timeout(REQUEST_TIMEOUT);
            step.headerTemplates().forEach((headerName, template) ->
                    builder.header(headerName, Template.render(template, vars)));
            String body = Template.render(step.bodyTemplate(), vars);
            builder.method(step.method(), body.isEmpty()
                    ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(body));

            HttpResponse<String> response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            int status = response.statusCode();
            if (status < 200 || status >= 400) {
                return new StepOutcome(false, status, where + ": HTTP " + status);
            }
            capture(step, response.body(), vars);
            return new StepOutcome(true, status, null);
        } catch (Exception e) {
            String message = e.getMessage();
            return new StepOutcome(false, 0,
                    where + ": " + e.getClass().getSimpleName() + (message == null ? "" : ": " + message));
        }
    }

    private static void capture(Step step, String body, Map<String, Object> vars) {
        step.extract().forEach((name, path) -> {
            Optional<String> value = JsonPointer.extract(body, path);
            value.ifPresent(found -> vars.put(name, found));
        });
    }

    public String lastFailure() {
        return lastFailure.get();
    }

    public List<Journey> journeys() {
        return journeys.items();
    }

    @Override
    public void close() {
        client.close();
    }
}
