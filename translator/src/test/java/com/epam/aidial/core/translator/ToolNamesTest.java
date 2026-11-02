package com.epam.aidial.core.translator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.IntNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The expected aliases are what the Python translator's {@code tool_names.py} gives for the same names.
 */
class ToolNamesTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String LONG_NAME = "mcp__dial-knowledge-base__search_documents_by_semantic_similarity_v2";
    private static final String LONG_ALIAS = "mcp__dial-knowledge-base__search_documents_by_semantic__7bc2a2fa";

    private final ToolNames toolNames = new ToolNames();

    @Test
    void testLongNameIsAliased() {
        assertEquals(LONG_ALIAS, toolNames.alias(LONG_NAME, Set.of()));
        assertEquals(TextNode.valueOf(LONG_NAME), toolNames.restore(TextNode.valueOf(LONG_ALIAS)));
    }

    @Test
    void testTakenAliasGetsCounterWithinTheLengthLimit() {
        String alias = toolNames.alias(LONG_NAME, Set.of(LONG_ALIAS));

        assertEquals("mcp__dial-knowledge-base__search_documents_by_semanti_7bc2a2fa_1", alias);
        assertEquals(64, alias.length());
    }

    @Test
    void testHeadIsSanitised() {
        assertEquals("t_1abc_57cb980e", toolNames.alias("1abc", Set.of()));
        assertEquals("wetter-caf__0a467e11", toolNames.alias("wetter-café", Set.of()));
        assertEquals("___7ec26292", toolNames.alias("名前", Set.of()));
    }

    @Test
    void testSameNameGetsOneAliasAtEverySite() throws Exception {
        ObjectNode body = (ObjectNode) MAPPER.readTree("""
                {"tools":[{"type":"function","name":"Read"},{"type":"function","name":"%1$s"},{"type":"bash_20250124","name":"%1$s"}],
                 "tool_choice":{"type":"function","name":"%1$s"},
                 "input":[{"type":"function_call","call_id":"c","name":"%1$s","arguments":"{}"},
                          {"type":"message","role":"user","name":"%1$s"}]}""".formatted(LONG_NAME));

        toolNames.applyResponses(body);

        assertEquals(MAPPER.readTree("""
                {"tools":[{"type":"function","name":"Read"},{"type":"function","name":"%1$s"},{"type":"bash_20250124","name":"%2$s"}],
                 "tool_choice":{"type":"function","name":"%1$s"},
                 "input":[{"type":"function_call","call_id":"c","name":"%1$s","arguments":"{}"},
                          {"type":"message","role":"user","name":"%2$s"}]}""".formatted(LONG_ALIAS, LONG_NAME)), body);
    }

    @Test
    void testAliasNeverLandsOnRealName() throws Exception {
        ObjectNode body = (ObjectNode) MAPPER.readTree("""
                {"tools":[{"type":"function","name":"a"},{"type":"function","name":"a_ca978112"}]}""");

        toolNames.applyResponses(body);

        assertEquals("a_ca978112_1", body.path("tools").path(0).path("name").asText());
        assertEquals("a_ca978112", body.path("tools").path(1).path("name").asText());
        assertEquals(TextNode.valueOf("a"), toolNames.restore(TextNode.valueOf("a_ca978112_1")));
        // a real name is not an alias this request issued
        assertEquals(TextNode.valueOf("a_ca978112"), toolNames.restore(TextNode.valueOf("a_ca978112")));
    }

    @Test
    void testNamelessFunctionToolIsLeftAlone() throws Exception {
        ObjectNode body = (ObjectNode) MAPPER.readTree("""
                {"tools":[{"type":"function","name":null},{"type":"function"}]}""");
        JsonNode before = body.deepCopy();

        toolNames.applyResponses(body);

        assertEquals(before, body);
    }

    @Test
    void testRestorePassesOtherValuesThrough() {
        assertEquals(TextNode.valueOf("unknown"), toolNames.restore(TextNode.valueOf("unknown")));
        assertEquals(IntNode.valueOf(1), toolNames.restore(IntNode.valueOf(1)));
        assertEquals(null, toolNames.restore(null));
    }
}
