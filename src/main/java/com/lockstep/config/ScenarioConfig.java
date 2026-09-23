package com.lockstep.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;
import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = false)
public record ScenarioConfig(String name, int weight, List<StepSpec> steps) {
    public ScenarioConfig {
        steps = steps == null ? List.of() : List.copyOf(steps);
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record StepSpec(
            String method,
            String url,
            String body,
            Map<String, String> headers,
            Map<String, String> extract) {
        public StepSpec {
            headers = headers == null ? Map.of() : Map.copyOf(headers);
            extract = extract == null ? Map.of() : Map.copyOf(extract);
        }
    }
}
