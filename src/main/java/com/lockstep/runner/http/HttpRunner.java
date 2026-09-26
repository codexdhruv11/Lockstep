package com.lockstep.runner.http;

import com.lockstep.config.HttpConfig;
import com.lockstep.core.Operation;
import com.lockstep.core.PacedLoop;
import com.lockstep.core.RunContext;
import com.lockstep.core.RunProgress;
import com.lockstep.core.Runner;
import com.lockstep.runner.QueryLabels;
import com.lockstep.runner.WeightedPicker;
import com.lockstep.stats.BucketSeries;
import com.lockstep.stats.HistogramRecorder;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

public final class HttpRunner implements Runner {
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);

    private final HttpClient client;
    private final WeightedPicker<Endpoint> endpoints;
    private final List<String> targetLabels;
    private final int rate;
    private final AtomicReference<String> lastFailure = new AtomicReference<>();

    private volatile HistogramRecorder[] targetRecorders;
    private volatile RunContext runContext;

    private HttpRunner(HttpClient client, WeightedPicker<Endpoint> endpoints,
            List<String> targetLabels, int rate) {
        this.client = client;
        this.endpoints = endpoints;
        this.targetLabels = targetLabels;
        this.rate = rate;
    }

    public static HttpRunner create(HttpConfig config, int concurrency) {
        List<HttpConfig.Target> targets = config.allTargets();
        HttpClient client = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(REQUEST_TIMEOUT)
                .build();

        List<Endpoint> endpoints = new ArrayList<>(targets.size());
        List<String> methods = new ArrayList<>(targets.size());
        List<String> urls = new ArrayList<>(targets.size());
        for (HttpConfig.Target target : targets) {
            endpoints.add(new Endpoint(templateFor(target), target.weight()));
            methods.add(target.method());
            urls.add(target.url());
        }

        return new HttpRunner(client, new WeightedPicker<>(endpoints, Endpoint::weight, true),
                QueryLabels.forTargets(methods, urls), config.rate());
    }

    private record Endpoint(HttpRequest.Builder request, int weight) {}

    private static HttpRequest.Builder templateFor(HttpConfig.Target target) {
        URI uri;
        try {
            uri = URI.create(target.url());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("http target url is not a valid URI: " + target.url(), e);
        }
        if (uri.getScheme() == null || uri.getHost() == null) {
            throw new IllegalArgumentException(
                    "http target url must be absolute (scheme and host), got: " + target.url());
        }
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri).timeout(REQUEST_TIMEOUT);
        applyHeaders(builder, target.header());
        builder.method(methodOf(target), bodyOf(target));

        builder.build();
        return builder;
    }

    @Override
    public PacedLoop.LoopResult run(RunContext context) {
        return run(context, null);
    }

    public PacedLoop.LoopResult run(RunContext context, RunProgress.Counter progress) {
        this.runContext = context;
        int buckets = HistogramRecorder.bucketsFor(context.durationNanos(), context.bucketWidthNanos());
        HistogramRecorder[] recorders = new HistogramRecorder[targetLabels.size()];
        for (int i = 0; i < recorders.length; i++) {
            recorders[i] = new HistogramRecorder(context.bucketWidthNanos(), buckets);
        }
        this.targetRecorders = recorders;
        return PacedLoop.run(context, rate, this::executeOne, progress);
    }

    @Override
    public String name() {
        return "http";
    }

    private Operation.Outcome executeOne(long scheduledOffsetNanos) {
        int index = endpoints.pickIndex();
        long startedAt = System.nanoTime();
        Operation.Outcome outcome = Operation.Outcome.OK;
        try {
            outcome = send(endpoints.items().get(index).request());
            return outcome;
        } finally {
            recordTarget(index, scheduledOffsetNanos, startedAt, outcome);
        }
    }

    private Operation.Outcome send(HttpRequest.Builder template) {
        try {
            HttpResponse<Void> response = client.send(template.build(), HttpResponse.BodyHandlers.discarding());
            int status = response.statusCode();
            if (status >= 200 && status < 400) {
                return Operation.Outcome.ok(status);
            }
            return Operation.Outcome.failed(status, "HTTP " + status);
        } catch (Exception e) {
            String message = e.getMessage();
            String described = e.getClass().getSimpleName() + (message == null ? "" : ": " + message);
            lastFailure.set(described);
            return Operation.Outcome.failed(described);
        }
    }

    private void recordTarget(int index, long scheduledOffsetNanos, long startedAt,
            Operation.Outcome outcome) {
        HistogramRecorder[] recorders = targetRecorders;
        RunContext context = runContext;
        if (recorders == null || context == null || index >= recorders.length) {
            return;
        }
        long finishedAt = System.nanoTime();
        recorders[index].record(
                scheduledOffsetNanos,
                finishedAt - context.deadlineFor(scheduledOffsetNanos),
                finishedAt - startedAt,
                outcome.success(),
                outcome.statusCode());
    }

    public Map<String, BucketSeries> targetBreakdown() {
        HistogramRecorder[] recorders = targetRecorders;
        Map<String, BucketSeries> out = new LinkedHashMap<>();
        if (recorders == null) {
            return out;
        }
        for (int i = 0; i < recorders.length && i < targetLabels.size(); i++) {
            BucketSeries series = recorders[i].snapshot();
            if (series.totalCount() > 0) {
                out.put(targetLabels.get(i), series);
            }
        }
        return out;
    }

    private static void applyHeaders(HttpRequest.Builder builder, Map<String, List<String>> headers) {
        headers.forEach((name, values) -> values.forEach(value -> {
            try {
                builder.header(name, value);
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException(
                        "http target header \"" + name + "\" cannot be set by the client: " + e.getMessage(), e);
            }
        }));
    }

    private static String methodOf(HttpConfig.Target target) {
        String method = target.method() == null || target.method().isBlank() ? "GET" : target.method().trim();
        return method.toUpperCase();
    }

    private static HttpRequest.BodyPublisher bodyOf(HttpConfig.Target target) {
        String body = target.body();
        return body == null || body.isEmpty()
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body);
    }

    public String lastFailure() {
        return lastFailure.get();
    }

    @Override
    public void close() {
        client.close();
    }
}
