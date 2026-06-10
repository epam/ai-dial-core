package com.epam.aidial.cli.service.json;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JsonMergePatchTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static JsonNode parse(String json) throws Exception {
        return MAPPER.readTree(json);
    }

    private static JsonNode patch(String target, String patch) throws Exception {
        return JsonMergePatch.apply(parse(target), parse(patch));
    }

    @Test
    void scalarReplaceField() throws Exception {
        assertEquals(parse("{\"a\":2}"), patch("{\"a\":1}", "{\"a\":2}"));
    }

    @Test
    void nullDeletesField() throws Exception {
        assertEquals(parse("{}"), patch("{\"a\":1}", "{\"a\":null}"));
    }

    @Test
    void nullDeleteAbsentFieldIsNoOp() throws Exception {
        assertEquals(parse("{\"a\":1}"), patch("{\"a\":1}", "{\"b\":null}"));
    }

    @Test
    void deepMergeNestedObject() throws Exception {
        assertEquals(
                parse("{\"a\":{\"x\":1,\"y\":2}}"),
                patch("{\"a\":{\"x\":1}}", "{\"a\":{\"y\":2}}"));
    }

    @Test
    void deepNullDeletesNestedField() throws Exception {
        assertEquals(
                parse("{\"a\":{\"y\":2}}"),
                patch("{\"a\":{\"x\":1,\"y\":2}}", "{\"a\":{\"x\":null}}"));
    }

    @Test
    void arrayReplaces() throws Exception {
        // RFC 7396: arrays are scalars at the merge level — full replacement.
        assertEquals(
                parse("{\"a\":[3,4]}"),
                patch("{\"a\":[1,2]}", "{\"a\":[3,4]}"));
    }

    @Test
    void nonObjectPatchReplacesTarget() throws Exception {
        assertEquals(parse("42"), patch("{\"a\":1}", "42"));
        assertEquals(parse("[1,2]"), patch("{\"a\":1}", "[1,2]"));
    }

    @Test
    void objectPatchOverNonObjectTargetTreatedAsEmpty() throws Exception {
        // RFC 7396 §1: if Target is not an Object, Target = {}; then walk Patch.
        assertEquals(
                parse("{\"a\":1}"),
                patch("\"hello\"", "{\"a\":1}"));
    }

    @Test
    void addsNewField() throws Exception {
        assertEquals(parse("{\"a\":1,\"b\":2}"), patch("{\"a\":1}", "{\"b\":2}"));
    }

    @Test
    void targetIsNotMutated() throws Exception {
        JsonNode target = parse("{\"a\":{\"x\":1}}");
        JsonNode patch = parse("{\"a\":{\"x\":2}}");
        JsonMergePatch.apply(target, patch);
        assertTrue(target.path("a").path("x").asInt() == 1, "target should be untouched");
    }
}
