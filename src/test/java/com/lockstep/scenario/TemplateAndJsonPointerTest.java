package com.lockstep.scenario;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;

final class TemplateAndJsonPointerTest {
    @Test
    void substitutesKnownVariables() {
        Map<String, Object> vars = Map.of("token", "tok-123", "id", 42);
        assertThat(Template.render("Bearer {{token}}", vars)).isEqualTo("Bearer tok-123");
        assertThat(Template.render("/api/users/{{id}}/orders", vars)).isEqualTo("/api/users/42/orders");
        assertThat(Template.render("{{token}}{{token}}", vars)).isEqualTo("tok-123tok-123");
        assertThat(Template.render("{{ token }}", vars)).isEqualTo("tok-123");
    }

    @Test
    void leavesAnUnknownVariableVisibleRatherThanBlankingIt() {
        assertThat(Template.render("Bearer {{token}}", Map.of())).isEqualTo("Bearer {{token}}");
        assertThat(Template.hasUnresolved("Bearer {{token}}")).isTrue();
        assertThat(Template.hasUnresolved("Bearer tok-123")).isFalse();
    }

    @Test
    void textWithoutPlaceholdersIsUntouched() {
        assertThat(Template.render("plain", Map.of("a", "b"))).isEqualTo("plain");
        assertThat(Template.render("", Map.of("a", "b"))).isEmpty();
        assertThat(Template.render(null, Map.of("a", "b"))).isNull();
        assertThat(Template.render("unclosed {{token", Map.of("token", "x"))).isEqualTo("unclosed {{token");
    }

    @Test
    void extractsValuesByPathInBothSupportedSpellings() {
        String json = "{\"token\":\"tok-123\",\"user\":{\"id\":42},\"items\":[{\"sku\":\"A-1\"}]}";

        assertThat(JsonPointer.extract(json, "$.token")).contains("tok-123");
        assertThat(JsonPointer.extract(json, "token")).contains("tok-123");
        assertThat(JsonPointer.extract(json, "$.user.id")).contains("42");
        assertThat(JsonPointer.extract(json, "items[0].sku")).contains("A-1");
    }

    @Test
    void aMissingPathIsEmptyRatherThanBlank() {
        String json = "{\"token\":\"tok-123\"}";

        assertThat(JsonPointer.extract(json, "$.nope")).isEmpty();
        assertThat(JsonPointer.extract(json, "$.token.deeper")).isEmpty();
        assertThat(JsonPointer.extract(json, "items[3]")).isEmpty();
        assertThat(JsonPointer.extract("{\"a\":null}", "a")).isEmpty();
    }

    @Test
    void aNonJsonBodyIsANormalOutcomeNotAFailure() {
        assertThat(JsonPointer.extract("<html>502 Bad Gateway</html>", "$.token")).isEmpty();
        assertThat(JsonPointer.extract("", "$.token")).isEmpty();
        assertThat(JsonPointer.extract("{\"a\":1}", "")).isEmpty();
    }

    @Test
    void anObjectValueComesBackAsJsonSoItCanBeReplayed() {
        String json = "{\"user\":{\"id\":42,\"name\":\"alice\"}}";
        assertThat(JsonPointer.extract(json, "$.user")).contains("{\"id\":42,\"name\":\"alice\"}");
    }
}
