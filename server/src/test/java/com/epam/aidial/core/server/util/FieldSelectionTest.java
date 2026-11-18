package com.epam.aidial.core.server.util;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.SneakyThrows;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class FieldSelectionTest {

    private static final String ROWS = """
            [
              {
                "id": "gpt-4", "object": "model", "display_name": "GPT 4", "display_version": "1.0",
                "features": {"rate": true, "tokenize": false, "configuration": true, "reasoning_efforts": ["low", "high"]},
                "interface_configs": {
                  "openaiChatCompletions": {"features": {"reasoning_efforts": ["low"], "rate": true}, "defaults": {"temperature": 1}},
                  "anthropicMessages": {"features": {"reasoning_efforts": ["max"], "rate": false}}
                },
                "routes": {"r1": {"paths": ["/a", "/b"], "methods": ["GET"]}}
              },
              {"id": "app", "object": "application", "display_name": "App", "application_properties": {"p": "v"}}
            ]
            """;

    @Test
    void emptyOrBlankValueGivesNoSelection() {
        assertNull(FieldSelection.parse(null));
        assertNull(FieldSelection.parse(""));
        assertNull(FieldSelection.parse("  "));
    }

    @Test
    void topLevelNamesAreKeptInResponseOrderWithIdAndObject() {
        JsonNode projected = project("display_version,display_name");

        assertEquals(2, projected.size());
        assertEquals(List.of("id", "object", "display_name", "display_version"), names(projected.get(0)));
        assertEquals(List.of("id", "object", "display_name"), names(projected.get(1)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"features", "id", "object", "id,object"})
    void idAndObjectAreAlwaysKept(String fields) {
        for (JsonNode row : project(fields)) {
            assertTrue(row.has("id"));
            assertTrue(row.has("object"));
        }
    }

    @Test
    void namingOnlyIdOrObjectReturnsExactlyBoth() {
        assertEquals(List.of("id", "object"), names(project("id").get(0)));
        assertEquals(List.of("id", "object"), names(project("object").get(0)));
    }

    @Test
    void nestedSelectionKeepsNamedMembersOfObjectsAndArrayElements() {
        JsonNode first = project("display_name,features(rate,tokenize),routes(*(paths))").get(0);

        assertEquals(List.of("id", "object", "display_name", "features", "routes"), names(first));
        assertEquals(List.of("rate", "tokenize"), names(first.get("features")));
        assertEquals(List.of("paths"), names(first.get("routes").get("r1")));

        JsonNode rows = read("[{\"a\": [{\"b\": 1, \"c\": 2}, \"s\", 3]}]");
        assertEquals(read("[{\"a\": [{\"b\": 1}, \"s\", 3]}]"), FieldSelection.parse("a(b)").project(rows));
    }

    @Test
    void selectionOnValueWithoutMembersLeavesItUnchanged() {
        JsonNode first = project("display_version(major),features(reasoning_efforts(x))").get(0);

        assertEquals("1.0", first.get("display_version").asText());
        assertEquals(read("{\"reasoning_efforts\": [\"low\", \"high\"]}"), first.get("features"));
    }

    @Test
    void wildcardSelectsEveryKeyOfMap() {
        JsonNode configs = project("interface_configs(*(features(reasoning_efforts)))").get(0).get("interface_configs");

        assertEquals(read("""
                {
                  "openaiChatCompletions": {"features": {"reasoning_efforts": ["low"]}},
                  "anthropicMessages": {"features": {"reasoning_efforts": ["max"]}}
                }
                """), configs);
    }

    @Test
    void repeatedNamesAreMerged() {
        assertEquals(List.of("rate", "tokenize"), names(project("features(rate),features(tokenize)").get(0).get("features")));
        assertEquals(4, project("features,features(rate)").get(0).get("features").size());
        assertEquals(4, project("*,features(rate)").get(0).get("features").size());
        assertEquals(List.of("rate", "tokenize"), names(project("features(rate),*(tokenize)").get(0).get("features")));
    }

    @Test
    void unknownNamesAreIgnored() {
        JsonNode projected = project("display_name,descriptio,application_properties");

        assertEquals(List.of("id", "object", "display_name"), names(projected.get(0)));
        assertEquals(List.of("id", "object", "display_name", "application_properties"), names(projected.get(1)));
    }

    @Test
    void topLevelWildcardKeepsEverything() {
        assertEquals(read(ROWS), project("*"));
    }

    @Test
    void whitespaceAroundNamesIsIgnored() {
        JsonNode first = project(" display_name , features ( rate ) ").get(0);

        assertEquals(List.of("id", "object", "display_name", "features"), names(first));
        assertEquals(List.of("rate"), names(first.get("features")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"features(rate", "features)", "features(rate))", "id,,display_name", "a(,b)", "()", "(a)", "a,", ",a", "a(b)c", "a(b)(c)"})
    void malformedSelectionIsRejected(String fields) {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> FieldSelection.parse(fields));
        assertTrue(error.getMessage().contains("position"), error.getMessage());
    }

    @Test
    void selectionOverTheLengthLimitIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> FieldSelection.parse("a".repeat(FieldSelection.MAX_LENGTH + 1)));
        FieldSelection.parse("a".repeat(FieldSelection.MAX_LENGTH));
    }

    @Test
    void selectionOverTheDepthLimitIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> FieldSelection.parse("a(b(c(d(e(f)))))"));
        FieldSelection.parse("a(b(c(d(e))))");
    }

    private static JsonNode project(String fields) {
        return FieldSelection.parse(fields).project(read(ROWS));
    }

    @SneakyThrows
    private static JsonNode read(String json) {
        return ProxyUtil.MAPPER.readTree(json);
    }

    private static List<String> names(JsonNode node) {
        return node.properties().stream().map(Map.Entry::getKey).toList();
    }
}
