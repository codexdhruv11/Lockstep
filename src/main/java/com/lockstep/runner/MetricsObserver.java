package com.lockstep.runner;

import com.lockstep.analysis.PrometheusScrape;
import com.lockstep.analysis.TargetMetrics;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Reads the target's own metrics endpoint while the load runs.
 *
 * <p>Counters need only a before and an after. Gauges — connections in use, CPU — describe an
 * instant, so they are sampled through the run; a before-and-after pair of a gauge says nothing
 * about what happened between.
 *
 * <p>The scrape is one HTTP GET four times a second against an endpoint that almost certainly
 * already has a Prometheus server polling it, so it adds nothing meaningful to the target's load.
 * If it fails the run is unaffected: the report says the metrics were unavailable and why.
 */
public final class MetricsObserver implements AutoCloseable {

    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    private final HttpClient client;
    private final String endpoint;
    private final AtomicBoolean running = new AtomicBoolean();
    private Thread thread;

    private PrometheusScrape before;
    private String failure;

    // Gauge samples, collected through the run.
    private double poolActiveSum;
    private double poolActiveMax = -1;
    private double poolMaxConfigured = -1;
    private double cpuSum;
    private double cpuMax = -1;
    private int samples;

    private MetricsObserver(HttpClient client, String endpoint) {
        this.client = client;
        this.endpoint = endpoint;
    }

    public static MetricsObserver open(String endpoint) {
        URI uri;
        try {
            uri = URI.create(endpoint);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "--observe-metrics is not a valid URL: " + endpoint, e);
        }
        if (uri.getScheme() == null || uri.getHost() == null) {
            throw new IllegalArgumentException(
                    "--observe-metrics must be an absolute URL, got: " + endpoint);
        }
        HttpClient client = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(TIMEOUT)
                .build();
        return new MetricsObserver(client, endpoint);
    }

    public String endpoint() {
        return endpoint;
    }

    /** Takes the opening snapshot. Throws if the endpoint cannot be read, so the run can refuse. */
    public void before() {
        try {
            before = scrape();
        } catch (Exception e) {
            throw new IllegalArgumentException(
                    "could not read the target's metrics at " + endpoint + ": " + describe(e), e);
        }
        if (before.isEmpty()) {
            throw new IllegalArgumentException(
                    "the target's metrics endpoint at " + endpoint + " returned no samples");
        }
    }

    public void start() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        thread = new Thread(this::loop, "lockstep-metrics-observer");
        thread.setDaemon(true);
        thread.start();
    }

    private void loop() {
        while (running.get()) {
            try {
                sampleGauges(scrape());
            } catch (Exception e) {
                failure = describe(e);
                // Keep going: a single failed scrape should not end the observation.
            }
            try {
                Thread.sleep(TargetMetrics.SAMPLE_INTERVAL_MILLIS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private synchronized void sampleGauges(PrometheusScrape scrape) {
        String poolName = firstPresent(scrape, TargetMetrics.POOL_ACTIVE_NAMES);
        if (poolName != null) {
            double active = scrape.sum(poolName);
            if (active >= 0) {
                poolActiveSum += active;
                poolActiveMax = Math.max(poolActiveMax, active);
            }
        }
        String poolMaxName = firstPresent(scrape, TargetMetrics.POOL_MAX_NAMES);
        if (poolMaxName != null) {
            double configured = scrape.sum(poolMaxName);
            if (configured > 0) {
                poolMaxConfigured = Math.max(poolMaxConfigured, configured);
            }
        }
        String cpuName = firstPresent(scrape, TargetMetrics.CPU_NAMES);
        if (cpuName != null) {
            double cpu = scrape.sum(cpuName);
            if (cpu >= 0) {
                cpuSum += cpu;
                cpuMax = Math.max(cpuMax, cpu);
            }
        }
        samples++;
    }

    public synchronized TargetMetrics after() {
        running.set(false);
        if (thread != null) {
            thread.interrupt();
            try {
                thread.join(2_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        if (before == null) {
            return TargetMetrics.unavailable(endpoint, "no opening snapshot was taken");
        }

        PrometheusScrape after;
        try {
            after = scrape();
        } catch (Exception e) {
            return TargetMetrics.unavailable(endpoint,
                    "could not read the metrics after the run: " + describe(e));
        }

        Map<String, String> notFound = new LinkedHashMap<>();

        String requestCountName = firstPresent(after, TargetMetrics.REQUEST_COUNT_NAMES);
        String requestSecondsName = firstPresent(after, TargetMetrics.REQUEST_SECONDS_NAMES);
        long requestDelta = 0;
        double secondsDelta = 0;
        if (requestCountName == null) {
            notFound.put("server request count", String.join(", ",
                    TargetMetrics.REQUEST_COUNT_NAMES));
        } else {
            requestDelta = (long) Math.max(0,
                    after.sum(requestCountName) - orZero(before.sum(requestCountName)));
        }
        if (requestSecondsName == null) {
            notFound.put("server request time", String.join(", ",
                    TargetMetrics.REQUEST_SECONDS_NAMES));
        } else {
            secondsDelta = Math.max(0,
                    after.sum(requestSecondsName) - orZero(before.sum(requestSecondsName)));
        }

        String gcCountName = firstPresent(after, TargetMetrics.GC_COUNT_NAMES);
        String gcSecondsName = firstPresent(after, TargetMetrics.GC_SECONDS_NAMES);
        long gcCountDelta = 0;
        double gcSecondsDelta = 0;
        if (gcCountName == null) {
            notFound.put("garbage collection", String.join(", ", TargetMetrics.GC_COUNT_NAMES));
        } else {
            gcCountDelta = (long) Math.max(0,
                    after.sum(gcCountName) - orZero(before.sum(gcCountName)));
            if (gcSecondsName != null) {
                gcSecondsDelta = Math.max(0,
                        after.sum(gcSecondsName) - orZero(before.sum(gcSecondsName)));
            }
        }

        if (poolActiveMax < 0) {
            notFound.put("connection pool", String.join(", ", TargetMetrics.POOL_ACTIVE_NAMES));
        }
        if (cpuMax < 0) {
            notFound.put("process CPU", String.join(", ", TargetMetrics.CPU_NAMES));
        }

        String poolName = firstPresent(after, TargetMetrics.POOL_ACTIVE_NAMES);
        String cpuName = firstPresent(after, TargetMetrics.CPU_NAMES);

        return new TargetMetrics(true,
                failure == null ? null : "some scrapes failed: " + failure,
                endpoint,
                requestDelta, secondsDelta,
                requestSecondsName != null ? requestSecondsName : requestCountName,
                samples > 0 && poolActiveMax >= 0 ? poolActiveSum / samples : -1,
                poolActiveMax, poolMaxConfigured, poolName,
                gcCountDelta, gcSecondsDelta, gcCountName,
                samples > 0 && cpuMax >= 0 ? cpuSum / samples : -1,
                cpuMax, cpuName,
                samples, notFound);
    }

    private static double orZero(double value) {
        return value < 0 ? 0 : value;
    }

    private static String firstPresent(PrometheusScrape scrape, List<String> candidates) {
        return scrape.firstPresent(candidates.toArray(String[]::new));
    }

    private PrometheusScrape scrape() throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint))
                .timeout(TIMEOUT)
                .header("accept", "text/plain")
                .GET()
                .build();
        HttpResponse<String> response =
                client.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("HTTP " + response.statusCode() + " from " + endpoint);
        }
        return PrometheusScrape.parse(response.body());
    }

    private static String describe(Exception e) {
        String message = e.getMessage();
        return e.getClass().getSimpleName() + (message == null ? "" : ": " + message);
    }

    @Override
    public void close() {
        running.set(false);
        client.close();
    }
}
