package com.lockstep.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class MultiTargetConfigTest {
    @TempDir
    Path tempDir;

    private Path config(String yaml) {
        try {
            Path file = tempDir.resolve("config.yaml");
            Files.writeString(file, yaml);
            return file;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Test
    void aWeightedListOfTargetsLoads() {
        Config loaded = ConfigLoader.load(config("""
                duration: 10s
                http:
                  rate: 50
                  targets:
                    - method: GET
                      url: http://localhost:8080/api/products
                      weight: 50
                    - method: POST
                      url: http://localhost:8080/api/orders
                      body: '{"customer": 42}'
                      header:
                        content-type: [application/json]
                      weight: 30
                """));

        assertThat(loaded.http().isMultiTarget()).isTrue();
        assertThat(loaded.http().allTargets()).hasSize(2);
        assertThat(loaded.http().allTargets().get(0).weight()).isEqualTo(50);
        assertThat(loaded.http().allTargets().get(1).method()).isEqualTo("POST");
        assertThat(loaded.http().allTargets().get(1).header().get("content-type"))
                .containsExactly("application/json");
    }

    @Test
    void aSingleTargetStillLoadsAndReadsAsOneTarget() {
        Config loaded = ConfigLoader.load(config("""
                duration: 10s
                http:
                  rate: 50
                  target:
                    method: GET
                    url: http://localhost:8080/api/products
                """));

        assertThat(loaded.http().isMultiTarget()).isFalse();
        assertThat(loaded.http().allTargets()).hasSize(1);
        assertThat(loaded.http().target().url()).isEqualTo("http://localhost:8080/api/products");
    }

    @Test
    void bothTargetAndTargetsIsRejectedRatherThanOnePreferredSilently() {
        assertThatThrownBy(() -> ConfigLoader.load(config("""
                duration: 10s
                http:
                  rate: 50
                  target:
                    url: http://localhost:8080/a
                  targets:
                    - url: http://localhost:8080/b
                """)))
                .isInstanceOf(ConfigValidationException.class)
                .hasMessageContaining("either target: or targets:");
    }

    @Test
    void everyTargetIsValidatedNotJustTheFirst() {
        assertThatThrownBy(() -> ConfigLoader.load(config("""
                duration: 10s
                http:
                  rate: 50
                  targets:
                    - url: http://localhost:8080/ok
                    - url: http://localhost:8080/also-ok
                    - method: FETCH
                      url: http://localhost:8080/typo
                """)))
                .isInstanceOf(ConfigValidationException.class)
                .hasMessageContaining("http.targets[2]")
                .hasMessageContaining("FETCH");
    }

    @Test
    void anEmptyUrlInTheListIsNamedByItsPosition() {
        assertThatThrownBy(() -> ConfigLoader.load(config("""
                duration: 10s
                http:
                  rate: 50
                  targets:
                    - url: http://localhost:8080/ok
                    - method: GET
                """)))
                .isInstanceOf(ConfigValidationException.class)
                .hasMessageContaining("http.targets[1]")
                .hasMessageContaining("url must not be empty");
    }

    @Test
    void anUnknownKeyInsideATargetIsStillRejected() {
        assertThatThrownBy(() -> ConfigLoader.load(config("""
                duration: 10s
                http:
                  rate: 50
                  targets:
                    - url: http://localhost:8080/ok
                      methd: GET
                """)))
                .isInstanceOf(ConfigValidationException.class)
                .hasMessageContaining("methd");
    }

    @Test
    void aNegativeWeightIsRejected() {
        assertThatThrownBy(() -> ConfigLoader.load(config("""
                duration: 10s
                http:
                  rate: 50
                  targets:
                    - url: http://localhost:8080/ok
                      weight: -5
                """)))
                .isInstanceOf(ConfigValidationException.class)
                .hasMessageContaining("weight");
    }
}
