package com.epam.aidial.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JsonDiffTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static JsonNode tree(String json) throws Exception {
        return MAPPER.readTree(json);
    }

    @Test
    void emptyDiffWhenIdentical() throws Exception {
        JsonNode same = tree("{\"a\":1,\"b\":[1,2]}");

        assertEquals(List.of(), JsonDiff.diff(same, same));
    }

    @Test
    void detectsAddedKey() throws Exception {
        JsonNode source = tree("{\"a\":1}");
        JsonNode target = tree("{\"a\":1,\"b\":2}");

        List<JsonDiff.Change> changes = JsonDiff.diff(source, target);

        assertEquals(1, changes.size());
        assertEquals("b", changes.get(0).path());
        assertEquals(JsonDiff.Op.ADDED, changes.get(0).op());
    }

    @Test
    void detectsRemovedKey() throws Exception {
        JsonNode source = tree("{\"a\":1,\"b\":2}");
        JsonNode target = tree("{\"a\":1}");

        List<JsonDiff.Change> changes = JsonDiff.diff(source, target);

        assertEquals(1, changes.size());
        assertEquals("b", changes.get(0).path());
        assertEquals(JsonDiff.Op.REMOVED, changes.get(0).op());
    }

    @Test
    void detectsScalarChange() throws Exception {
        JsonNode source = tree("{\"a\":1}");
        JsonNode target = tree("{\"a\":2}");

        List<JsonDiff.Change> changes = JsonDiff.diff(source, target);

        assertEquals(1, changes.size());
        assertEquals("a", changes.get(0).path());
        assertEquals(JsonDiff.Op.CHANGED, changes.get(0).op());
    }

    @Test
    void recursesIntoNestedObjects() throws Exception {
        JsonNode source = tree("{\"models\":{\"gpt-4\":{\"endpoint\":\"a\"}}}");
        JsonNode target = tree("{\"models\":{\"gpt-4\":{\"endpoint\":\"b\"}}}");

        List<JsonDiff.Change> changes = JsonDiff.diff(source, target);

        assertEquals(1, changes.size());
        assertEquals("models.gpt-4.endpoint", changes.get(0).path());
        assertEquals(JsonDiff.Op.CHANGED, changes.get(0).op());
    }

    @Test
    void treatsArraysAsOpaque() throws Exception {
        JsonNode source = tree("{\"items\":[1,2,3]}");
        JsonNode target = tree("{\"items\":[1,2,4]}");

        List<JsonDiff.Change> changes = JsonDiff.diff(source, target);

        assertEquals(1, changes.size());
        assertEquals("items", changes.get(0).path());
        assertEquals(JsonDiff.Op.CHANGED, changes.get(0).op());
    }

    @Test
    void mixedAddedRemovedChanged() throws Exception {
        JsonNode source = tree("{\"a\":1,\"b\":2,\"c\":3}");
        JsonNode target = tree("{\"a\":1,\"b\":99,\"d\":4}");

        List<String> paths = JsonDiff.diff(source, target).stream()
                .map(JsonDiff.Change::toString)
                .collect(Collectors.toList());

        assertEquals(List.of("~ b: 2 → 99", "- c: 3", "+ d: 4"), paths);
    }

    @Test
    void changeToStringPrefixes() throws Exception {
        assertEquals("+ x.y: \"val\"", new JsonDiff.Change("x.y", JsonDiff.Op.ADDED, null, tree("\"val\"")).toString());
        assertEquals("- x.y: \"val\"", new JsonDiff.Change("x.y", JsonDiff.Op.REMOVED, tree("\"val\""), null).toString());
        assertEquals("~ x.y: \"old\" → \"new\"", new JsonDiff.Change("x.y", JsonDiff.Op.CHANGED, tree("\"old\""), tree("\"new\"")).toString());
    }

    @Test
    void detectsTypeMismatchAsChanged() throws Exception {
        JsonNode source = tree("{\"a\":{\"x\":1}}");
        JsonNode target = tree("{\"a\":[1,2,3]}");

        List<JsonDiff.Change> changes = JsonDiff.diff(source, target);

        assertEquals(1, changes.size());
        assertEquals("a", changes.get(0).path());
        assertEquals(JsonDiff.Op.CHANGED, changes.get(0).op());
    }

    @Test
    void deeplyNestedPath() throws Exception {
        JsonNode source = tree("{\"a\":{\"b\":{\"c\":{\"d\":1}}}}");
        JsonNode target = tree("{\"a\":{\"b\":{\"c\":{\"d\":2}}}}");

        List<JsonDiff.Change> changes = JsonDiff.diff(source, target);

        assertEquals(1, changes.size());
        assertEquals("a.b.c.d", changes.get(0).path());
        assertTrue(changes.get(0).toString().contains("a.b.c.d"));
    }
}
