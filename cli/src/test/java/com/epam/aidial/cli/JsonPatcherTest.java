package com.epam.aidial.cli;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.BooleanNode;
import com.fasterxml.jackson.databind.node.IntNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JsonPatcherTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static ObjectNode obj(String json) {
        try {
            return (ObjectNode) MAPPER.readTree(json);
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }

    private static JsonNode node(String json) {
        try {
            return MAPPER.readTree(json);
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void nullSetsIsNoOp() {
        ObjectNode target = obj("{\"a\":1}");
        JsonPatcher.apply(target, null);
        assertThat(target.toString()).isEqualTo("{\"a\":1}");
    }

    @Test
    void emptySetsIsNoOp() {
        ObjectNode target = obj("{\"a\":1}");
        JsonPatcher.apply(target, Map.of());
        assertThat(target.toString()).isEqualTo("{\"a\":1}");
    }

    @Test
    void topLevelStringFallback() {
        ObjectNode target = obj("{\"displayName\":\"old\"}");
        JsonPatcher.apply(target, Map.of("displayName", TextNode.valueOf("My GPT-4 Model")));
        assertThat(target.get("displayName")).isEqualTo(TextNode.valueOf("My GPT-4 Model"));
    }

    @Test
    void jsonNumberSet() {
        ObjectNode target = obj("{\"maxTokens\":4096}");
        JsonPatcher.apply(target, Map.of("maxTokens", IntNode.valueOf(8192)));
        assertThat(target.get("maxTokens").asInt()).isEqualTo(8192);
    }

    @Test
    void jsonBooleanSet() {
        ObjectNode target = obj("{\"enabled\":false}");
        JsonPatcher.apply(target, Map.of("enabled", BooleanNode.TRUE));
        assertThat(target.get("enabled").asBoolean()).isTrue();
    }

    @Test
    void nestedPathIntermediateExists() {
        ObjectNode target = obj("{\"limits\":{\"hourly\":100,\"daily\":500}}");
        JsonPatcher.apply(target, Map.of("limits.daily", IntNode.valueOf(1000)));
        assertThat(target.at("/limits/daily").asInt()).isEqualTo(1000);
        assertThat(target.at("/limits/hourly").asInt()).isEqualTo(100);
    }

    @Test
    void deepPathAutoCreatesIntermediates() {
        ObjectNode target = obj("{\"displayName\":\"Model\"}");
        JsonPatcher.apply(target, Map.of("upstream.auth.apiKey", TextNode.valueOf("sk-abc123")));
        assertThat(target.at("/upstream/auth/apiKey").asText()).isEqualTo("sk-abc123");
    }

    @Test
    void jsonArrayValue() {
        ObjectNode target = obj("{}");
        JsonPatcher.apply(target, Map.of("roles", node("[\"admin\",\"user\"]")));
        assertThat(target.get("roles").isArray()).isTrue();
        assertThat(target.get("roles").size()).isEqualTo(2);
        assertThat(target.get("roles").get(0).asText()).isEqualTo("admin");
    }

    @Test
    void jsonObjectValue() {
        ObjectNode target = obj("{}");
        JsonPatcher.apply(target, Map.of("metadata", node("{\"owner\":\"team-a\",\"version\":2}")));
        assertThat(target.at("/metadata/owner").asText()).isEqualTo("team-a");
        assertThat(target.at("/metadata/version").asInt()).isEqualTo(2);
    }

    @Test
    void errorIntermediateIsNonObject() {
        ObjectNode target = obj("{\"displayName\":\"string-value\"}");
        assertThatThrownBy(() -> JsonPatcher.apply(target, Map.of("displayName.sub", TextNode.valueOf("x"))))
                .isInstanceOf(CliException.class)
                .hasMessageContaining("non-object");
    }

    @Test
    void errorEmptySegment() {
        ObjectNode target = obj("{\"limits\":{}}");
        assertThatThrownBy(() -> JsonPatcher.apply(target, Map.of("limits..daily", IntNode.valueOf(100))))
                .isInstanceOf(CliException.class)
                .hasMessageContaining("empty segments");
    }

    @Test
    void multipleEntriesAppliedInOrder() {
        ObjectNode target = obj("{\"a\":1,\"b\":2}");
        Map<String, JsonNode> sets = new LinkedHashMap<>();
        sets.put("a", IntNode.valueOf(10));
        sets.put("b", IntNode.valueOf(20));
        JsonPatcher.apply(target, sets);
        assertThat(target.get("a").asInt()).isEqualTo(10);
        assertThat(target.get("b").asInt()).isEqualTo(20);
    }
}
