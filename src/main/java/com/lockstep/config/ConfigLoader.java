package com.lockstep.config;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;

public final class ConfigLoader {
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT);

    private static final Set<String> VALID_METHODS = Set.of(
            "GET", "POST", "PUT", "DELETE", "PATCH", "HEAD", "OPTIONS", "TRACE", "CONNECT");

    private ConfigLoader() {}

    public static Config load(Path path) {
        String yaml;
        try {
            yaml = Files.readString(path);
        } catch (IOException e) {
            throw new ConfigValidationException("config file " + path + " cannot be read: " + e.getMessage());
        }
        return loadString(yaml, path.toString());
    }

    public static Config loadString(String yaml, String sourceName) {
        if (yaml == null || yaml.isBlank()) {
            throw new ConfigValidationException(sourceName == null ? "config is empty"
                    : "config file \"" + sourceName + "\" is empty");
        }
        Map<String, Object> root = parse(yaml, sourceName);
        rejectLegacyPlural(root);
        rejectUnknownKeys(root, "");
        Config config = bind(root, sourceName);
        validate(config, sourceName);
        return config;
    }

    private static Map<String, Object> parse(String yaml, String sourceName) {
        try {
            LoaderOptions options = new LoaderOptions();
            options.setAllowDuplicateKeys(false);
            Yaml yamlParser = new Yaml(options);
            Object document = yamlParser.load(yaml);
            if (!(document instanceof Map)) {
                throw new ConfigValidationException(
                        "config \"" + sourceName + "\" must be a YAML mapping, got " + typeName(document));
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> root = (Map<String, Object>) document;
            return root;
        } catch (org.yaml.snakeyaml.error.YAMLException e) {
            throw new ConfigValidationException("config \"" + sourceName + "\" is not valid YAML: " + e.getMessage());
        }
    }

    private static void rejectLegacyPlural(Map<String, Object> root) {
        if (root.containsKey("scenarios")) {
            throw new ConfigValidationException("scenarios",
                    "scenarios: is renamed to scenario: (singular)");
        }
    }

    private static final Map<String, Set<String>> KNOWN_KEYS_BY_PATH = Map.ofEntries(
            Map.entry("", Set.of("duration", "bucket_width", "ramp", "concurrency",
                    "http", "db", "redis", "scenario")),
            Map.entry("http", Set.of("rate", "target")),
            Map.entry("db", Set.of("rate", "target")),
            Map.entry("redis", Set.of("rate", "target")),
            Map.entry("http.target", Set.of("method", "url", "body", "header")),
            Map.entry("db.target", Set.of("conn", "driver", "queries")),
            Map.entry("redis.target", Set.of("addr", "password", "db", "queries")),
            Map.entry("db.target.queries.*", Set.of("query", "weight", "type", "args")),
            Map.entry("redis.target.queries.*", Set.of("query", "weight", "type", "args")),
            Map.entry("scenario.*", Set.of("name", "weight", "steps")),
            Map.entry("scenario.*.steps.*", Set.of("method", "url", "body", "headers", "extract")));

    private static final Set<String> FREE_FORM_MAP_PATHS = Set.of(
            "http.target.header", "scenario.*.steps.*.headers", "scenario.*.steps.*.extract");

    private static void rejectUnknownKeys(Map<String, Object> node, String containerPath) {
        Set<String> known = KNOWN_KEYS_BY_PATH.get(containerPath);
        if (known == null) {
            throw new ConfigValidationException(containerPath.isEmpty() ? "config" : containerPath,
                    "unexpected mapping" + (containerPath.isEmpty() ? "" : " at " + containerPath));
        }
        for (String key : node.keySet()) {
            if (!known.contains(key)) {
                throw new ConfigValidationException(
                        containerPath.isEmpty() ? key : containerPath + "." + key,
                        "unknown field \"" + key + "\""
                                + (containerPath.isEmpty() ? "" : " in \"" + containerPath + "\""));
            }
        }
        for (Map.Entry<String, Object> entry : node.entrySet()) {
            String childPath = containerPath.isEmpty() ? entry.getKey() : containerPath + "." + entry.getKey();
            Object value = entry.getValue();
            if (value instanceof Map<?, ?> nested) {
                if (!FREE_FORM_MAP_PATHS.contains(childPath)) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> nestedMap = (Map<String, Object>) nested;
                    rejectUnknownKeys(nestedMap, childPath);
                }
            } else if (value instanceof List<?> list) {
                for (Object element : list) {
                    if (element instanceof Map<?, ?> elementMap) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> elementMapTyped = (Map<String, Object>) elementMap;
                        rejectUnknownKeys(elementMapTyped, childPath + ".*");
                    }
                }
            }
        }
    }

    private static Config bind(Map<String, Object> root, String sourceName) {
        try {
            return MAPPER.convertValue(root, Config.class);
        } catch (IllegalArgumentException e) {
            Throwable cause = e.getCause();
            while (cause != null) {
                if (cause instanceof ConfigValidationException cve) {
                    throw cve;
                }
                cause = cause.getCause();
            }

            cause = e.getCause();
            if (cause instanceof com.fasterxml.jackson.databind.JsonMappingException jme) {
                String field = jme.getPath().stream()
                        .map(ref -> ref.getFieldName() != null ? ref.getFieldName()
                                : "[" + ref.getIndex() + "]")
                        .reduce((a, b) -> a + "." + b)
                        .orElse(null);
                String original = jme.getOriginalMessage();
                if (field != null) {
                    throw new ConfigValidationException(field,
                            "config \"" + sourceName + "\": invalid value for " + field
                                    + (original != null ? " — " + original : ""));
                }
            }
            throw new ConfigValidationException("config \"" + sourceName + "\": " + e.getMessage());
        }
    }

    private static void validate(Config config, String sourceName) {
        String at = sourceName == null ? "config" : "config \"" + sourceName + "\"";

        if (config.http() == null && config.db() == null && config.redis() == null
                && config.scenario().isEmpty()) {
            throw new ConfigValidationException(at + " must specify at least one runner: http, db, redis, or scenario");
        }
        if (!config.scenario().isEmpty() && config.http() != null) {
            throw new ConfigValidationException("scenario",
                    "scenario mode cannot be combined with http section");
        }
        if (config.duration() <= 0) {
            throw new ConfigValidationException("duration", at + ": duration must be greater than zero");
        }

        if (config.ramp() < 0) {
            throw new ConfigValidationException("ramp", at + ": ramp must not be negative");
        }
        if (config.concurrency() < 0) {
            throw new ConfigValidationException("concurrency", at + ": concurrency must not be negative");
        }

        if (config.http() != null) {
            validateHttp(config.http(), at);
        }
        if (config.db() != null) {
            validateDb(config.db(), at);
        }
        if (config.redis() != null) {
            validateRedis(config.redis(), at);
        }
        validateScenarios(config.scenario(), at);
    }

    private static void validateHttp(HttpConfig http, String at) {
        if (http.rate() <= 0) {
            throw new ConfigValidationException("http.rate", at + ": http rate must be greater than zero");
        }
        String method = http.target().method();
        if (!method.isBlank() && !VALID_METHODS.contains(method.toUpperCase())) {
            throw new ConfigValidationException("http.target.method", at + ": http target: invalid method \"" + method + "\"");
        }
        if (http.target().url().isBlank()) {
            throw new ConfigValidationException("http.target.url", at + ": http target url must not be empty");
        }
    }

    private static void validateDb(DbConfig db, String at) {
        if (db.rate() <= 0) {
            throw new ConfigValidationException("db.rate", at + ": db rate must be greater than zero");
        }
        if (db.target().conn().isBlank()) {
            throw new ConfigValidationException("db.target.conn", at + ": db target conn must not be empty");
        }
        if (db.target().driver().isBlank()) {
            throw new ConfigValidationException("db.target.driver", at + ": db target driver must not be empty");
        }
        validateQueries(db.target().queries(), "db", at);
    }

    private static void validateRedis(RedisConfig redis, String at) {
        if (redis.rate() <= 0) {
            throw new ConfigValidationException("redis.rate", at + ": redis rate must be greater than zero");
        }
        if (redis.target().addr().isBlank()) {
            throw new ConfigValidationException("redis.target.addr", at + ": redis target addr must not be empty");
        }
        validateQueries(redis.target().queries(), "redis", at);
    }

    private static void validateScenarios(List<ScenarioConfig> scenarios, String at) {
        for (int i = 0; i < scenarios.size(); i++) {
            ScenarioConfig scenario = scenarios.get(i);
            String name = scenario.name() == null || scenario.name().isBlank()
                    ? "scenario[" + i + "]"
                    : scenario.name();
            if (scenario.weight() < 0) {
                throw new ConfigValidationException("scenario.weight",
                        at + ": scenario[" + i + "] \"" + name + "\": weight must not be negative");
            }
            if (scenario.steps().isEmpty()) {
                throw new ConfigValidationException("scenario.steps",
                        at + ": scenario[" + i + "] \"" + name + "\" must have at least one step");
            }
            for (int s = 0; s < scenario.steps().size(); s++) {
                ScenarioConfig.StepSpec step = scenario.steps().get(s);
                String method = step.method() == null ? "" : step.method();
                if (!isValidMethod(method)) {
                    throw new ConfigValidationException("scenario.steps.method",
                            at + ": scenario[" + i + "] \"" + name + "\" step " + s + ": invalid method \"" + method + "\"");
                }
                if (step.url() == null || step.url().isBlank()) {
                    throw new ConfigValidationException("scenario.steps.url",
                            at + ": scenario[" + i + "] \"" + name + "\" step " + s + ": url must not be empty");
                }
            }
        }
    }

    private static void validateQueries(List<QuerySpec> queries, String runner, String at) {
        if (queries.isEmpty()) {
            throw new ConfigValidationException(runner + ".target.queries",
                    at + ": " + runner + " target must list at least one query");
        }
        long total = 0;
        for (int i = 0; i < queries.size(); i++) {
            QuerySpec query = queries.get(i);
            if (query.query() == null || query.query().isBlank()) {
                throw new ConfigValidationException(runner + ".target.queries.query",
                        at + ": " + runner + " queries[" + i + "]: query must not be empty");
            }
            if (query.weight() < 0) {
                throw new ConfigValidationException(runner + ".target.queries.weight",
                        at + ": " + runner + " queries[" + i + "]: weight must not be negative");
            }
            total += query.weight();
        }
        if (total <= 0) {
            throw new ConfigValidationException(runner + ".target.queries.weight",
                    at + ": " + runner + " queries: total weight must be greater than zero");
        }
    }

    private static boolean isValidMethod(String method) {
        return !method.isBlank() && VALID_METHODS.contains(method.toUpperCase());
    }

    private static String typeName(Object value) {
        return value == null ? "nothing" : value.getClass().getSimpleName();
    }
}
