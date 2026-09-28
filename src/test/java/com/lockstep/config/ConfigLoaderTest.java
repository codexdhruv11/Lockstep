package com.lockstep.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

final class ConfigLoaderTest {
    private static final Path COMPAT = Path.of("src", "test", "resources", "compat");

    @Test
    void loadsTheFullFeaturedConfig() {
        Config config = ConfigLoader.load(COMPAT.resolve("config.yaml"));

        assertThat(config.duration()).isEqualTo(15_000_000_000L);
        assertThat(config.bucketWidth()).isEqualTo(1_000_000_000L);
        assertThat(config.ramp()).isEqualTo(3_000_000_000L);
        assertThat(config.concurrency()).isEqualTo(10);
        assertThat(config.http().rate()).isEqualTo(10);
        assertThat(config.http().target().method()).isEqualTo("POST");
        assertThat(config.http().target().url()).isEqualTo("http://localhost:8080/api/orders");
        assertThat(config.http().target().header().get("content-type")).containsExactly("application/json");
        assertThat(config.http().target().header().get("Authorization")).containsExactly("Bearer some-token");
        assertThat(config.db().rate()).isEqualTo(5);
        assertThat(config.db().target().driver()).isEqualTo("postgres");
        assertThat(config.db().target().queries()).hasSize(5);
        assertThat(config.db().target().queries().get(0).type()).isEqualTo("read");
        assertThat(config.redis().target().addr()).isEqualTo("localhost:6379");
        assertThat(config.redis().target().db()).isZero();
        assertThat(config.redis().target().queries()).hasSize(1);
        assertThat(config.scenario()).isEmpty();
    }

    @Test
    void loadsLightExample() {
        Config config = ConfigLoader.load(COMPAT.resolve("light.yaml"));

        assertThat(config.duration()).isEqualTo(20_000_000_000L);
        assertThat(config.http().target().header().get("accept")).containsExactly("application/json");
        assertThat(config.db()).isNull();
        assertThat(config.redis().target().queries()).hasSize(2);
        assertThat(config.redis().target().queries().get(1).query()).isEqualTo("SET sess:loadtest ok");
    }

    @Test
    void loadsHeavyExample() {
        Config config = ConfigLoader.load(COMPAT.resolve("heavy.yaml"));

        assertThat(config.concurrency()).isEqualTo(50);
        assertThat(config.db().target().driver()).isEqualTo("sqlite");
        assertThat(config.db().target().conn()).isEqualTo("/tmp/lockstep-load.db");
        assertThat(config.db().target().queries().get(1).type()).isEqualTo("write");
        assertThat(config.redis().target().queries()).hasSize(3);
    }

    @Test
    void loadsScenarioLoginExample() {
        Config config = ConfigLoader.load(COMPAT.resolve("scenario-login.yaml"));

        assertThat(config.http()).isNull();
        assertThat(config.scenario()).hasSize(1);
        ScenarioConfig scenario = config.scenario().get(0);
        assertThat(scenario.name()).isEqualTo("login-flow");
        assertThat(scenario.weight()).isZero();
        assertThat(scenario.steps()).hasSize(3);
        assertThat(scenario.steps().get(0).method()).isEqualTo("POST");
        assertThat(scenario.steps().get(0).extract()).containsEntry("token", "$.token");
        assertThat(scenario.steps().get(1).headers()).containsEntry("Authorization", "Bearer {{token}}");
    }

    @Test
    void loadsWeightedScenariosExample() {
        Config config = ConfigLoader.load(COMPAT.resolve("scenarios-weighted.yaml"));

        assertThat(config.scenario()).hasSize(2);
        assertThat(config.scenario().get(0).weight()).isEqualTo(70);
        assertThat(config.scenario().get(1).weight()).isEqualTo(30);
        assertThat(config.scenario().get(1).steps().get(1).url()).contains("{{token}}");
    }

    @Test
    void rejectsTypoKeyNamingTheKey() {
        String yaml = """
                duration: 10s
                bucket_widht: 1s
                http:
                  rate: 1
                  target:
                    url: http://localhost/x
                """;
        assertThatThrownBy(() -> ConfigLoader.loadString(yaml, "typo.yaml"))
                .isInstanceOf(ConfigValidationException.class)
                .hasMessageContaining("bucket_widht");
    }

    @Test
    void rejectsNegativeRateNamingTheField() {
        String yaml = """
                duration: 10s
                bucket_width: 1s
                db:
                  rate: -5
                  target:
                    driver: sqlite
                    conn: /tmp/x.db
                    queries:
                      - query: SELECT 1
                        weight: 1
                """;
        assertThatThrownBy(() -> ConfigLoader.loadString(yaml, "neg.yaml"))
                .isInstanceOf(ConfigValidationException.class)
                .hasMessageContaining("db")
                .hasMessageContaining("rate")
                .hasMessageContaining("greater than zero");
    }

    @Test
    void rejectsScenarioTogetherWithHttpNamingTheField() {
        String yaml = """
                duration: 10s
                bucket_width: 1s
                http:
                  rate: 1
                  target:
                    url: http://localhost/x
                scenario:
                  - name: s
                    steps:
                      - method: GET
                        url: http://localhost/y
                """;
        assertThatThrownBy(() -> ConfigLoader.loadString(yaml, "both.yaml"))
                .isInstanceOf(ConfigValidationException.class)
                .hasMessageContaining("scenario")
                .hasMessageContaining("http");
    }

    @Test
    void rejectsConfigWithNoRunnerSection() {
        String yaml = """
                duration: 10s
                bucket_width: 1s
                """;
        assertThatThrownBy(() -> ConfigLoader.loadString(yaml, "empty-runners.yaml"))
                .isInstanceOf(ConfigValidationException.class)
                .hasMessageContaining("at least one runner");
    }

    @Test
    void rejectsEmptyConfig() {
        assertThatThrownBy(() -> ConfigLoader.loadString("   ", "blank.yaml"))
                .isInstanceOf(ConfigValidationException.class)
                .hasMessageContaining("empty");
    }

    @Test
    void rejectsLegacyScenariosPluralKey() {
        String yaml = """
                duration: 10s
                bucket_width: 1s
                scenarios:
                  - name: s
                    steps:
                      - method: GET
                        url: http://localhost/y
                """;
        assertThatThrownBy(() -> ConfigLoader.loadString(yaml, "plural.yaml"))
                .isInstanceOf(ConfigValidationException.class)
                .hasMessageContaining("renamed to scenario");
    }

    @Test
    void rejectsUnknownKeyInsideRunnerSection() {
        String yaml = """
                duration: 10s
                bucket_width: 1s
                redis:
                  rate: 1
                  traget:
                    addr: localhost:6379
                    queries:
                      - query: PING
                        weight: 1
                """;
        assertThatThrownBy(() -> ConfigLoader.loadString(yaml, "typo-target.yaml"))
                .isInstanceOf(ConfigValidationException.class)
                .hasMessageContaining("traget");
    }

    @Test
    void rejectsDuplicateTopLevelKeys() {
        String yaml = """
                duration: 10s
                duration: 20s
                bucket_width: 1s
                redis:
                  rate: 1
                  target:
                    addr: localhost:6379
                    queries:
                      - query: PING
                        weight: 1
                """;
        assertThatThrownBy(() -> ConfigLoader.loadString(yaml, "dup.yaml"))
                .isInstanceOf(ConfigValidationException.class)
                .hasMessageContaining("duration");
    }

    @Test
    void rejectsNegativeQueryWeightAndAllZeroWeights() {
        String negative = """
                duration: 10s
                bucket_width: 1s
                redis:
                  rate: 1
                  target:
                    addr: localhost:6379
                    queries:
                      - query: PING
                        weight: -1
                """;
        assertThatThrownBy(() -> ConfigLoader.loadString(negative, "negw.yaml"))
                .hasMessageContaining("weight must not be negative");

        String allZero = """
                duration: 10s
                bucket_width: 1s
                redis:
                  rate: 1
                  target:
                    addr: localhost:6379
                    queries:
                      - query: PING
                        weight: 0
                """;
        assertThatThrownBy(() -> ConfigLoader.loadString(allZero, "zerow.yaml"))
                .hasMessageContaining("total weight must be greater than zero");
    }

    @Test
    void allowsZeroWeightWhenAnotherQueryCarriesTheWeight() {
        String yaml = """
                duration: 10s
                bucket_width: 1s
                redis:
                  rate: 1
                  target:
                    addr: localhost:6379
                    queries:
                      - query: PING
                        weight: 0
                      - query: GET k
                        weight: 2
                """;
        Config config = ConfigLoader.loadString(yaml, "mixedw.yaml");
        assertThat(config.redis().target().queries().get(0).weight()).isZero();
    }

    @Test
    void rejectsEmptyQueriesListAndEmptyQueryText() {
        String none = """
                duration: 10s
                bucket_width: 1s
                redis:
                  rate: 1
                  target:
                    addr: localhost:6379
                    queries: []
                """;
        assertThatThrownBy(() -> ConfigLoader.loadString(none, "noq.yaml"))
                .hasMessageContaining("at least one query");

        String blank = """
                duration: 10s
                bucket_width: 1s
                db:
                  rate: 1
                  target:
                    driver: sqlite
                    conn: /tmp/x.db
                    queries:
                      - query: "   "
                        weight: 1
                """;
        assertThatThrownBy(() -> ConfigLoader.loadString(blank, "blankq.yaml"))
                .hasMessageContaining("query must not be empty");
    }

    @Test
    void rejectsMissingDbConnAndDriverAndRedisAddr() {
        String db = """
                duration: 10s
                bucket_width: 1s
                db:
                  rate: 1
                  target:
                    driver: sqlite
                    queries:
                      - query: SELECT 1
                        weight: 1
                """;
        assertThatThrownBy(() -> ConfigLoader.loadString(db, "noconn.yaml"))
                .hasMessageContaining("conn must not be empty");

        String redis = """
                duration: 10s
                bucket_width: 1s
                redis:
                  rate: 1
                  target:
                    queries:
                      - query: PING
                        weight: 1
                """;
        assertThatThrownBy(() -> ConfigLoader.loadString(redis, "noaddr.yaml"))
                .hasMessageContaining("addr must not be empty");
    }

    @Test
    void rejectsInvalidDurationValuesNamingTheField() {
        String yaml = """
                duration: 10x
                bucket_width: 1s
                redis:
                  rate: 1
                  target:
                    addr: localhost:6379
                    queries:
                      - query: PING
                        weight: 1
                """;
        assertThatThrownBy(() -> ConfigLoader.loadString(yaml, "baddur.yaml"))
                .isInstanceOf(ConfigValidationException.class)
                .hasMessageContaining("duration")
                .hasMessageContaining("10x");
    }

    @Test
    void rejectsMissingDurationAndBucketWidth() {
        String noDuration = """
                bucket_width: 1s
                redis:
                  rate: 1
                  target:
                    addr: localhost:6379
                    queries:
                      - query: PING
                        weight: 1
                """;
        assertThatThrownBy(() -> ConfigLoader.loadString(noDuration, "nodur.yaml"))
                .hasMessageContaining("duration must be greater than zero");

        String noBucket = """
                duration: 10s
                redis:
                  rate: 1
                  target:
                    addr: localhost:6379
                    queries:
                      - query: PING
                        weight: 1
                """;
        Config noBucketConfig = ConfigLoader.loadString(noBucket, "nobucket.yaml");
        assertThat(noBucketConfig.bucketWidth()).isZero();
    }

    @Test
    void missingRampDefaultsToZeroLikeTheReference() {
        String yaml = """
                duration: 10s
                bucket_width: 1s
                http:
                  rate: 1
                  target:
                    url: http://localhost/x
                """;
        Config config = ConfigLoader.loadString(yaml, "noramp.yaml");
        assertThat(config.ramp()).isZero();
        assertThat(config.concurrency()).isZero();
    }

    @Test
    void rejectsNegativeRampAndConcurrency() {
        String yaml = """
                duration: 10s
                bucket_width: 1s
                ramp: -1s
                concurrency: -3
                redis:
                  rate: 1
                  target:
                    addr: localhost:6379
                    queries:
                      - query: PING
                        weight: 1
                """;
        assertThatThrownBy(() -> ConfigLoader.loadString(yaml, "negramp.yaml"))
                .hasMessageContaining("ramp must not be negative");

        String yaml2 = yaml.replace("ramp: -1s", "ramp: 1s");
        assertThatThrownBy(() -> ConfigLoader.loadString(yaml2, "negconc.yaml"))
                .hasMessageContaining("concurrency must not be negative");
    }

    @Test
    void rejectsInvalidScenarioStepsNamingIndexAndProblem() {
        String badMethod = """
                duration: 10s
                bucket_width: 1s
                scenario:
                  - name: s
                    steps:
                      - method: FETCH
                        url: http://localhost/y
                """;
        assertThatThrownBy(() -> ConfigLoader.loadString(badMethod, "badmethod.yaml"))
                .hasMessageContaining("step 0")
                .hasMessageContaining("invalid method");

        String emptyUrl = """
                duration: 10s
                bucket_width: 1s
                scenario:
                  - name: s
                    steps:
                      - method: GET
                """;
        assertThatThrownBy(() -> ConfigLoader.loadString(emptyUrl, "nourl.yaml"))
                .hasMessageContaining("url must not be empty");

        String noSteps = """
                duration: 10s
                bucket_width: 1s
                scenario:
                  - name: s
                """;
        assertThatThrownBy(() -> ConfigLoader.loadString(noSteps, "nosteps.yaml"))
                .hasMessageContaining("must have at least one step");

        String negativeWeight = """
                duration: 10s
                bucket_width: 1s
                scenario:
                  - name: s
                    weight: -2
                    steps:
                      - method: GET
                        url: http://localhost/y
                """;
        assertThatThrownBy(() -> ConfigLoader.loadString(negativeWeight, "negw.yaml"))
                .hasMessageContaining("weight must not be negative");
    }

    @Test
    void rejectsNonMappingDocumentAndBadHeaderShapes() {
        assertThatThrownBy(() -> ConfigLoader.loadString("- just\n- a\n- list\n", "list.yaml"))
                .hasMessageContaining("must be a YAML mapping");

        String numericHeader = """
                duration: 10s
                bucket_width: 1s
                http:
                  rate: 1
                  target:
                    url: http://localhost/x
                    header:
                      content-length: 42
                """;
        assertThatThrownBy(() -> ConfigLoader.loadString(numericHeader, "numheader.yaml"))
                .hasMessageContaining("must be a string or list of strings");
    }

    @Test
    void rejectsCaseInsensitiveDuplicateHeaders() {
        String yaml = """
                duration: 10s
                bucket_width: 1s
                http:
                  rate: 1
                  target:
                    url: http://localhost/x
                    header:
                      Accept: application/json
                      accept: text/plain
                """;
        assertThatThrownBy(() -> ConfigLoader.loadString(yaml, "dupheader.yaml"))
                .isInstanceOf(ConfigValidationException.class)
                .hasMessageContaining("accept")
                .hasMessageContaining("Accept")
                .hasMessageContaining("case-insensitive duplicate");
    }

    @Test
    void scenarioWeightZeroSurvivesLoadUnchanged() {
        String yaml = """
                duration: 10s
                bucket_width: 1s
                scenario:
                  - name: zero-weight
                    weight: 0
                    steps:
                      - method: GET
                        url: http://localhost/y
                """;
        Config config = ConfigLoader.loadString(yaml, "zerow.yaml");
        assertThat(config.scenario().get(0).weight()).isZero();
    }

    @Test
    void missingFileFailsWithReadableMessage() {
        assertThatThrownBy(() -> ConfigLoader.load(Path.of("does-not-exist.yaml")))
                .isInstanceOf(ConfigValidationException.class)
                .hasMessageContaining("does-not-exist.yaml");
    }

    @Test
    void integerDurationMeansNanoseconds() {
        String yaml = """
                duration: 10000000000
                bucket_width: 1000000000
                redis:
                  rate: 1
                  target:
                    addr: localhost:6379
                    queries:
                      - query: PING
                        weight: 1
                """;
        Config config = ConfigLoader.loadString(yaml, "intdur.yaml");
        assertThat(config.duration()).isEqualTo(10_000_000_000L);
        assertThat(config.bucketWidth()).isEqualTo(1_000_000_000L);
    }

    @Test
    void fieldNameIsCarriedOnTheExceptionForCliUse() {
        String yaml = """
                duration: 10s
                bucket_width: 1s
                http:
                  rate: 0
                  target:
                    url: http://localhost/x
                """;
        assertThatThrownBy(() -> ConfigLoader.loadString(yaml, "rate0.yaml"))
                .isInstanceOfSatisfying(ConfigValidationException.class,
                        e -> assertThat(e.fieldName()).isEqualTo("http.rate"));
    }

    @Test
    void loadStringWithoutSourceNameStillValidates() {
        String yaml = """
                duration: 5s
                bucket_width: 1s
                redis:
                  rate: 2
                  target:
                    addr: localhost:6379
                    queries:
                      - query: PING
                        weight: 1
                """;
        Config config = ConfigLoader.loadString(yaml, null);
        assertThat(config.duration()).isEqualTo(5_000_000_000L);
        assertThat(config.redis().rate()).isEqualTo(2);
    }

    @Test
    void aBlankOrDuplicateScenarioNameIsRejected() {
        String duplicate = """
            duration: 5s
            scenario:
              - name: checkout
                steps:
                  - method: GET
                    url: http://x/a
              - name: checkout
                steps:
                  - method: GET
                    url: http://x/b
            """;
        assertThatThrownBy(() -> ConfigLoader.loadString(duplicate, "dup.yaml"))
                .isInstanceOf(ConfigValidationException.class)
                .hasMessageContaining("duplicate scenario name");

        String blank = """
            duration: 5s
            scenario:
              - name: ""
                steps:
                  - method: GET
                    url: http://x/a
            """;
        assertThatThrownBy(() -> ConfigLoader.loadString(blank, "blank.yaml"))
                .isInstanceOf(ConfigValidationException.class)
                .hasMessageContaining("must have a name");
    }

    @Test
    void poolSizeIsConfigurableAndDefaultsToConcurrency() {
        String yaml = """
            duration: 5s
            concurrency: 8
            db:
              rate: 5
              target:
                driver: postgres
                conn: postgres://u:p@h:5432/d
                pool_size: 2
                queries:
                  - query: SELECT 1
                    weight: 1
                    type: read
            """;
        assertThat(ConfigLoader.loadString(yaml, "pool.yaml").db().target().poolSize()).isEqualTo(2);

        String without = yaml.lines()
                .filter(line -> !line.contains("pool_size"))
                .collect(java.util.stream.Collectors.joining("\n"));
        assertThat(ConfigLoader.loadString(without, "pool.yaml").db().target().poolSize()).isZero();

        String negative = yaml.replace("pool_size: 2", "pool_size: -1");
        assertThatThrownBy(() -> ConfigLoader.loadString(negative, "pool.yaml"))
                .isInstanceOf(ConfigValidationException.class)
                .hasMessageContaining("pool_size");
    }
}
