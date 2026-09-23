package com.lockstep.runner.http;

import com.lockstep.config.HttpConfig;
import com.lockstep.core.Operation;
import com.lockstep.core.PacedLoop;
import com.lockstep.core.RunContext;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

public final class HttpRunner implements AutoCloseable {
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);

    private final HttpClient client;
    private final HttpRequest.Builder template;
    private final int rate;
    private final AtomicReference<String> lastFailure = new AtomicReference<>();

    private HttpRunner(HttpClient client, HttpRequest.Builder template, int rate) {
        this.client = client;
        this.template = template;
        this.rate = rate;
    }

    public static HttpRunner create(HttpConfig config, int concurrency) {
        HttpConfig.Target target = config.target();
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

        HttpClient client = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(REQUEST_TIMEOUT)
                .build();

        HttpRequest.Builder builder = HttpRequest.newBuilder(uri).timeout(REQUEST_TIMEOUT);
        applyHeaders(builder, target.header());
        builder.method(methodOf(target), bodyOf(target));

        builder.build();
        return new HttpRunner(client, builder, config.rate());
    }

    public PacedLoop.LoopResult run(RunContext context) {
        return PacedLoop.run(context, rate, this::executeOne);
    }

    private Operation.Outcome executeOne() {
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
