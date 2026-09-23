package com.lockstep.config;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

@JsonIgnoreProperties(ignoreUnknown = false)
public record HttpConfig(Target target, int rate) {
    public HttpConfig {
        target = target == null ? new Target("", "", "", Map.of()) : target;
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record Target(
            String method,
            String url,
            String body,
            Map<String, List<String>> header) {
        @JsonCreator
        @SuppressWarnings("unchecked")
        public static Target fromJson(
                @JsonProperty("method") String method,
                @JsonProperty("url") String url,
                @JsonProperty("body") String body,
                @JsonProperty("header") Map<String, Object> rawHeader) {
            Map<String, List<String>> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
            if (rawHeader != null) {
                for (Map.Entry<String, Object> entry : rawHeader.entrySet()) {
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
                    headers.isEmpty() ? Map.of() : java.util.Collections.unmodifiableMap(headers));
        }
    }
}
