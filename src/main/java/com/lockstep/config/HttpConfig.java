package com.lockstep.config;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

@JsonIgnoreProperties(ignoreUnknown = false)
public record HttpConfig(Target target, List<Target> targets, int rate) {
    public HttpConfig {
        targets = targets == null ? List.of() : List.copyOf(targets);
        target = target == null && targets.isEmpty() ? new Target("", "", "", Map.of(), 0) : target;
    }

    public HttpConfig(Target target, int rate) {
        this(target, List.of(), rate);
    }

    public List<Target> allTargets() {
        return targets.isEmpty() ? List.of(target) : targets;
    }

    public boolean isMultiTarget() {
        return !targets.isEmpty();
    }

    @JsonCreator
    static HttpConfig fromJson(
            @JsonProperty("target") Target target,
            @JsonProperty("targets") List<Target> targets,
            @JsonProperty("rate") int rate) {
        if (target != null && targets != null && !targets.isEmpty()) {
            throw new ConfigValidationException("http",
                    "use either target: or targets:, not both — with both present it is not "
                    + "possible to tell which load was intended");
        }
        return new HttpConfig(target, targets, rate);
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record Target(
            String method,
            String url,
            String body,
            Map<String, List<String>> header,
            int weight) {
        public Target(String method, String url, String body, Map<String, List<String>> header) {
            this(method, url, body, header, 0);
        }

        @JsonCreator
        @SuppressWarnings("unchecked")
        public static Target fromJson(
                @JsonProperty("method") String method,
                @JsonProperty("url") String url,
                @JsonProperty("body") String body,
                @JsonProperty("header") Map<String, Object> rawHeader,
                @JsonProperty("weight") Integer weight) {
            Map<String, List<String>> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
            if (rawHeader != null) {
                for (Map.Entry<String, Object> entry : rawHeader.entrySet()) {
                    if (headers.containsKey(entry.getKey())) {
                        String existing = headers.keySet().stream()
                                .filter(k -> k.equalsIgnoreCase(entry.getKey()))
                                .findFirst().orElse(entry.getKey());
                        throw new ConfigValidationException("http.target.header",
                                "header \"" + entry.getKey() + "\" conflicts with \""
                                        + existing + "\" (case-insensitive duplicate)");
                    }
                    Object value = entry.getValue();
                    if (value instanceof String s) {
                        headers.put(entry.getKey(), List.of(s));
                    } else if (value instanceof List<?> list) {
                        for (Object item : list) {
                            if (!(item instanceof String)) {
                                throw new ConfigValidationException("http.target.header",
                                        "header \"" + entry.getKey() + "\" values must be strings");
                            }
                        }
                        headers.put(entry.getKey(), (List<String>) (List<?>) list);
                    } else if (value != null) {
                        throw new ConfigValidationException("http.target.header",
                                "header \"" + entry.getKey() + "\" must be a string or list of strings");
                    }
                }
            }

            return new Target(method == null ? "" : method,
                    url == null ? "" : url,
                    body == null ? "" : body,
                    headers.isEmpty() ? Map.of() : java.util.Collections.unmodifiableMap(headers),
                    weight == null ? 0 : weight);
        }
    }
}
