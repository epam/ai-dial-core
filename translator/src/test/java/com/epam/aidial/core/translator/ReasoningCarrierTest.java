package com.epam.aidial.core.translator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReasoningCarrierTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * Copied from the recorded stream fixture 034f9395f501f56e: a quote, a backslash and a CJK character.
     */
    private static final String RECORDED = """
            {"dial_translator_reasoning_wrapper":{"version":2,"id":"dialenc_original@@\\"\\\\雪","encrypted_content":"dialenc:dXBzdHJlYW0=;gAAAA_+/opaque=="}}""";

    private static JsonNode json(String json) throws Exception {
        return MAPPER.readTree(json);
    }

    private static ObjectNode thinking(String signature) {
        ObjectNode block = MAPPER.createObjectNode().put("type", "thinking").put("thinking", "plan");
        return block.put("signature", signature);
    }

    private static ObjectNode redacted(String data) {
        return MAPPER.createObjectNode().put("type", "redacted_thinking").put("data", data);
    }

    private static String carrier(String payload) {
        return "{\"dial_translator_reasoning_wrapper\":" + payload + "}";
    }

    @Test
    void testEncodeMatchesPythonSpelling() throws Exception {
        JsonNode item = json("""
                {"type":"reasoning","id":"dialenc_original@@\\"\\\\雪","summary":[],"encrypted_content":"dialenc:dXBzdHJlYW0=;gAAAA_+/opaque=="}""");

        assertEquals(RECORDED, ReasoningCarrier.encode(item));
    }

    @Test
    void testEncodeEscapesControlCharactersAsPythonDoes() throws Exception {
        // json.dumps(ensure_ascii=False) writes \n short and any other control character as a lowercase \\u escape
        JsonNode item = json("{\"id\":\"rs_1\",\"encrypted_content\":\"a\\nb\\u001bc\"}");

        assertEquals(carrier("{\"version\":2,\"id\":\"rs_1\",\"encrypted_content\":\"a\\nb\\u001bc\"}"), ReasoningCarrier.encode(item));
    }

    @Test
    void testEncodeLeavesOutAnEmptyOrMissingId() throws Exception {
        String expected = carrier("{\"version\":2,\"encrypted_content\":\"cipher\"}");

        assertEquals(expected, ReasoningCarrier.encode(json("{\"encrypted_content\":\"cipher\"}")));
        assertEquals(expected, ReasoningCarrier.encode(json("{\"id\":\"\",\"encrypted_content\":\"cipher\"}")));
        assertEquals(expected, ReasoningCarrier.encode(json("{\"id\":7,\"encrypted_content\":\"cipher\"}")));
    }

    @Test
    void testDecodeOfThinkingBlock() throws Exception {
        assertEquals(json("""
                {"type":"reasoning","summary":[{"type":"summary_text","text":"plan"}],
                 "encrypted_content":"dialenc:dXBzdHJlYW0=;gAAAA_+/opaque==","id":"dialenc_original@@\\"\\\\雪"}"""),
                ReasoningCarrier.decode(thinking(RECORDED)));
    }

    @Test
    void testDecodeOfRedactedBlockWithoutId() throws Exception {
        String value = "  \n" + carrier("{\"version\":2,\"encrypted_content\":\"cipher\"}") + " \n";

        assertEquals(json("{\"type\":\"reasoning\",\"summary\":[],\"encrypted_content\":\"cipher\"}"), ReasoningCarrier.decode(redacted(value)));
    }

    @Test
    void testDecodeOfThinkingBlockWithoutText() throws Exception {
        ObjectNode block = thinking(RECORDED);
        block.put("thinking", "");
        ObjectNode noText = thinking(RECORDED);
        noText.remove("thinking");

        assertEquals(json("[]"), ReasoningCarrier.decode(block).path("summary"));
        assertEquals(json("[]"), ReasoningCarrier.decode(noText).path("summary"));
    }

    @Test
    void testDecodeRejectsWhatPythonRejects() {
        String[] values = {
            "EqQBCkgIARABGAIiQDN",
            "[" + RECORDED + "]",
            "{\"dial_translator_reasoning_wrapper\":",
            RECORDED + " x",
            RECORDED + RECORDED,
            "{\"dial_translator_reasoning_wrapper\":{\"version\":2,\"encrypted_content\":\"c\"},\"other\":1}",
            carrier("{\"version\":2,\"encrypted_content\":\"c\",\"extra\":1}"),
            carrier("{\"version\":2,\"encrypted_content\":\"c\",\"encrypted_content\":\"d\"}"),
            carrier("{\"version\":1,\"encrypted_content\":\"c\"}"),
            carrier("{\"version\":2.0,\"encrypted_content\":\"c\"}"),
            carrier("{\"version\":\"2\",\"encrypted_content\":\"c\"}"),
            carrier("{\"version\":true,\"encrypted_content\":\"c\"}"),
            carrier("{\"encrypted_content\":\"c\"}"),
            carrier("{\"version\":2}"),
            carrier("{\"version\":2,\"encrypted_content\":\"\"}"),
            carrier("{\"version\":2,\"encrypted_content\":5}"),
            carrier("{\"version\":2,\"id\":\"\",\"encrypted_content\":\"c\"}"),
            carrier("{\"version\":2,\"id\":null,\"encrypted_content\":\"c\"}"),
            carrier("[]"),
            "",
        };
        for (String value : values) {
            assertNull(ReasoningCarrier.decode(thinking(value)), value);
            assertNull(ReasoningCarrier.decode(redacted(value)), value);
        }
    }

    @Test
    void testDecodeRejectsValueThatIsNotString() {
        ObjectNode numberSignature = MAPPER.createObjectNode().put("type", "thinking").put("signature", 123);
        ObjectNode objectData = MAPPER.createObjectNode().put("type", "redacted_thinking");
        objectData.putObject("data").put("dial_translator_reasoning_wrapper", 2);

        assertNull(ReasoningCarrier.decode(numberSignature));
        assertNull(ReasoningCarrier.decode(objectData));
        assertNull(ReasoningCarrier.decode(MAPPER.createObjectNode().put("type", "thinking")));
    }

    @Test
    void testDecodeReadsTheFieldOfTheBlockType() {
        // a thinking block's carrier is its signature, a redacted block's its data
        ObjectNode thinkingWithData = MAPPER.createObjectNode().put("type", "thinking").put("data", RECORDED);
        ObjectNode redactedWithSignature = MAPPER.createObjectNode().put("type", "redacted_thinking").put("signature", RECORDED);

        assertNull(ReasoningCarrier.decode(thinkingWithData));
        assertNull(ReasoningCarrier.decode(redactedWithSignature));
    }

    @Test
    void testWholeBlockJoinsSummaryAndContentTexts() throws Exception {
        JsonNode item = json("""
                {"type":"reasoning","id":"rs_1","encrypted_content":"cipher",
                 "summary":[{"type":"summary_text","text":"one"},{"type":"summary_text","text":""}],
                 "content":[{"type":"reasoning_text","text":"two"}]}""");
        String expectedCarrier = ReasoningCarrier.encode(item);

        assertEquals(MAPPER.createObjectNode().put("type", "thinking").put("thinking", "one\n\ntwo").put("signature", expectedCarrier),
                ReasoningCarrier.wholeBlock(item, false));
        assertEquals(MAPPER.createObjectNode().put("type", "thinking").put("thinking", "").put("signature", expectedCarrier),
                ReasoningCarrier.wholeBlock(item, true));
    }

    @Test
    void testWholeBlockWithoutTextIsRedacted() throws Exception {
        JsonNode item = json("{\"type\":\"reasoning\",\"id\":\"rs_1\",\"encrypted_content\":\"cipher\",\"summary\":[],\"content\":null}");
        ObjectNode expected = MAPPER.createObjectNode().put("type", "redacted_thinking").put("data", ReasoningCarrier.encode(item));

        assertEquals(expected, ReasoningCarrier.wholeBlock(item, false));
        assertEquals(expected, ReasoningCarrier.wholeBlock(item, true));
    }

    @Test
    void testHasEncryptedContent() throws Exception {
        assertTrue(ReasoningCarrier.hasEncryptedContent(json("{\"encrypted_content\":\"c\"}")));
        assertFalse(ReasoningCarrier.hasEncryptedContent(json("{\"encrypted_content\":\"\"}")));
        assertFalse(ReasoningCarrier.hasEncryptedContent(json("{\"encrypted_content\":null}")));
        assertFalse(ReasoningCarrier.hasEncryptedContent(json("{}")));
    }
}
