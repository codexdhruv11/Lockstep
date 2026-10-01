package com.lockstep.analysis;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Parses the Prometheus text exposition format, which is what almost every service already
 * exposes: Spring Boot through Micrometer, anything instrumented with OpenTelemetry, and most
 * runtimes' own exporters.
 *
 * <p>Reading it rather than requiring a push means a target needs no change to be observed — the
 * endpoint is usually already there, and already scraped by something else.
 *
 * <p>Deliberately a small parser rather than a dependency. The format is four lines of grammar:
 * comments start with {@code #}, and a sample is a name, optional {@code {label="value"}} pairs, a
 * value, and an optional timestamp. Histograms and summaries are exposed as ordinary samples with
 * {@code _count}, {@code _sum} and {@code _bucket} suffixes, so no special handling is needed.
 */
public final class PrometheusScrape {

    /** One sample: a metric name, its labels, and its value. */
    public record Sample(String name, Map<String, String> labels, double value) {
        public Sample {
            labels = labels == null
                    ? Map.of() : java.util.Collections.unmodifiableMap(new LinkedHashMap<>(labels));
        }

        public String label(String key) {
            return labels.get(key);
        }
    }

    private final List<Sample> samples;

    private PrometheusScrape(List<Sample> samples) {
        this.samples = List.copyOf(samples);
    }

    public static PrometheusScrape parse(String body) {
        List<Sample> parsed = new ArrayList<>();
        if (body == null) {
            return new PrometheusScrape(parsed);
        }
        for (String raw : body.split("\n")) {
            String line = raw.strip();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            Sample sample = parseLine(line);
            if (sample != null) {
                parsed.add(sample);
            }
        }
        return new PrometheusScrape(parsed);
    }

    private static Sample parseLine(String line) {
        int brace = line.indexOf('{');
        String name;
        Map<String, String> labels = new LinkedHashMap<>();
        String remainder;

        if (brace < 0) {
            int space = line.indexOf(' ');
            if (space < 0) {
                return null;
            }
            name = line.substring(0, space);
            remainder = line.substring(space + 1);
        } else {
            int close = line.lastIndexOf('}');
            if (close < brace) {
                return null;
            }
            name = line.substring(0, brace);
            parseLabels(line.substring(brace + 1, close), labels);
            remainder = line.substring(close + 1).strip();
        }

        // A value may be followed by a timestamp; only the value is wanted.
        String[] parts = remainder.strip().split("\\s+");
        if (parts.length == 0 || parts[0].isEmpty()) {
            return null;
        }
        double value = parseValue(parts[0]);
        if (Double.isNaN(value)) {
            return null;
        }
        return new Sample(name, labels, value);
    }

    /**
     * Prometheus permits {@code +Inf}, {@code -Inf} and {@code NaN} as values, and histogram
     * {@code +Inf} buckets use the first of those routinely. Treating them as parse failures would
     * silently drop real samples.
     */
    private static double parseValue(String text) {
        try {
            return switch (text) {
                case "+Inf", "Inf" -> Double.POSITIVE_INFINITY;
                case "-Inf" -> Double.NEGATIVE_INFINITY;
                case "NaN" -> Double.NaN;
                default -> Double.parseDouble(text);
            };
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
    }

    private static void parseLabels(String text, Map<String, String> into) {
        int i = 0;
        while (i < text.length()) {
            int equals = text.indexOf('=', i);
            if (equals < 0) {
                return;
            }
            String key = text.substring(i, equals).strip();
            int open = text.indexOf('"', equals);
            if (open < 0) {
                return;
            }
            // Label values may contain escaped quotes, so scan for an unescaped closing one.
            StringBuilder value = new StringBuilder();
            int j = open + 1;
            while (j < text.length()) {
                char c = text.charAt(j);
                if (c == '\\' && j + 1 < text.length()) {
                    char next = text.charAt(j + 1);
                    value.append(switch (next) {
                        case 'n' -> '\n';
                        case '"' -> '"';
                        case '\\' -> '\\';
                        default -> next;
                    });
                    j += 2;
                    continue;
                }
                if (c == '"') {
                    break;
                }
                value.append(c);
                j++;
            }
            into.put(key, value.toString());
            int comma = text.indexOf(',', j);
            if (comma < 0) {
                return;
            }
            i = comma + 1;
        }
    }

    public List<Sample> samples() {
        return samples;
    }

    /** Every sample with this exact metric name. */
    public List<Sample> named(String name) {
        return samples.stream().filter(sample -> sample.name().equals(name)).toList();
    }

    /**
     * The summed value of a metric across all its label combinations, or -1 when absent.
     *
     * <p>-1 rather than 0 because the difference matters: a pool with no active connections and a
     * pool whose metric is not exported should not read the same.
     */
    public double sum(String name) {
        List<Sample> matching = named(name);
        if (matching.isEmpty()) {
            return -1;
        }
        double total = 0;
        for (Sample sample : matching) {
            if (!Double.isNaN(sample.value()) && !Double.isInfinite(sample.value())) {
                total += sample.value();
            }
        }
        return total;
    }

    /** The first metric name present out of several candidates, or null. Handles naming drift. */
    public String firstPresent(String... candidates) {
        for (String candidate : candidates) {
            if (!named(candidate).isEmpty()) {
                return candidate;
            }
        }
        return null;
    }

    public boolean isEmpty() {
        return samples.isEmpty();
    }
}
