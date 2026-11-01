package com.epam.aidial.core.translator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MessagesToResponsesTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static JsonNode translate(String json) throws Exception {
        // the upstream model comes from the caller, never from the body's own model
        return MessagesToResponses.translateRequest(MAPPER.readTree(json), "upstream");
    }

    @Test
    void testTextConversation() throws Exception {
        JsonNode result = translate("""
                {"model":"m","max_tokens":100,"stream":true,"temperature":0.5,"top_p":0.9,
                 "system":[{"type":"text","text":"one"},{"type":"text","text":""},{"type":"text","text":"two"}],
                 "messages":[
                   {"role":"user","content":"hi"},
                   {"role":"assistant","content":[{"type":"text","text":"a"},{"type":"text","text":"b"}]},
                   {"role":"user","content":[{"type":"text","text":"more"}]}]}""");

        // stream is the caller's to set: Core is the transport
        assertEquals(MAPPER.readTree("""
                {"model":"upstream",
                 "input":[
                   {"type":"message","role":"user","content":[{"type":"input_text","text":"hi"}]},
                   {"type":"message","role":"assistant","content":"a\\nb"},
                   {"type":"message","role":"user","content":[{"type":"input_text","text":"more"}]}],
                 "instructions":"one\\n\\ntwo",
                 "store":false,"include":["reasoning.encrypted_content"],"max_output_tokens":100,"temperature":0.5,"top_p":0.9}"""), result);
    }

    @Test
    void testSystemTurnExpiresAfterLaterUserTurn() throws Exception {
        JsonNode result = translate("""
                {"model":"m","messages":[
                   {"role":"user","content":"1"},
                   {"role":"system","content":"expired","clear_at":"next_user_message"},
                   {"role":"system","content":"kept"},
                   {"role":"user","content":"2"},
                   {"role":"system","content":"current","clear_at":"next_user_message"}]}""");

        JsonNode input = result.path("input");
        assertEquals(4, input.size());
        assertEquals("kept", input.path(1).path("content").path(0).path("text").asText());
        assertEquals("system", input.path(3).path("role").asText());
        assertEquals("current", input.path(3).path("content").path(0).path("text").asText());
    }

    @Test
    void testOutputBudgetIsRaisedToTheMinimumButZeroIsKept() throws Exception {
        assertEquals(16, translate("{\"model\":\"m\",\"max_tokens\":1,\"messages\":[]}").path("max_output_tokens").asInt());
        assertEquals(0, translate("{\"model\":\"m\",\"max_tokens\":0,\"messages\":[]}").path("max_output_tokens").asInt());
        assertFalse(translate("{\"model\":\"m\",\"messages\":[]}").has("max_output_tokens"));
    }

    @Test
    void testUserIdIsSentOnlyWithinTheLimit() throws Exception {
        assertEquals("u1", translate("{\"model\":\"m\",\"messages\":[],\"metadata\":{\"user_id\":\"u1\"}}").path("user").asText());
        String longId = "x".repeat(65);
        assertFalse(translate("{\"model\":\"m\",\"messages\":[],\"metadata\":{\"user_id\":\"" + longId + "\"}}").has("user"));
    }

    @Test
    void testServiceTierIsMappedThroughClosedTable() throws Exception {
        assertEquals("auto", translate("{\"model\":\"m\",\"messages\":[],\"service_tier\":\"auto\"}").path("service_tier").asText());
        assertEquals("default", translate("{\"model\":\"m\",\"messages\":[],\"service_tier\":\"standard_only\"}").path("service_tier").asText());
        assertFalse(translate("{\"model\":\"m\",\"messages\":[],\"service_tier\":\"priority\"}").has("service_tier"));
    }

    @Test
    void testFieldsWithNoCounterpartAreDropped() throws Exception {
        JsonNode result = translate("""
                {"model":"m","messages":[],"top_k":5,"stop_sequences":["x"],"context_management":{},
                 "thinking":{"type":"adaptive"},"ignore_eos":true}""");

        assertFalse(result.has("top_k"));
        assertFalse(result.has("stop_sequences"));
        assertFalse(result.has("context_management"));
        assertFalse(result.has("thinking"));
        assertFalse(result.has("ignore_eos"));
    }

    @Test
    void testNonTextContentIsRefused() {
        UntranslatableRequestException error = assertThrows(UntranslatableRequestException.class, () -> translate("""
                {"model":"m","messages":[{"role":"assistant","content":[{"type":"tool_use","id":"t","name":"n","input":{}}]}]}"""));

        assertTrue(error.getMessage().contains("'tool_use' in the assistant turn"), error.getMessage());
    }

    @Test
    void testNonTextBlockInSystemTurnIsDropped() throws Exception {
        JsonNode result = translate("""
                {"model":"m","messages":[
                   {"role":"system","content":[{"type":"text","text":"a"},
                     {"type":"image","source":{"type":"base64","media_type":"image/png","data":"AA=="}}]},
                   {"role":"user","content":"hi"}]}""");

        assertEquals(MAPPER.readTree("""
                [{"type":"message","role":"system","content":[{"type":"input_text","text":"a"}]},
                 {"type":"message","role":"user","content":[{"type":"input_text","text":"hi"}]}]"""), result.path("input"));
    }

    @Test
    void testMidConversationBlockInUserTurnPrecedesIt() throws Exception {
        JsonNode result = translate("""
                {"model":"m","messages":[
                   {"role":"user","content":[{"type":"text","text":"carry on"},{"type":"mid_conv_system","content":"injected"}]}]}""");

        assertEquals(MAPPER.readTree("""
                [{"type":"message","role":"system","content":[{"type":"input_text","text":"injected"}]},
                 {"type":"message","role":"user","content":[{"type":"input_text","text":"carry on"}]}]"""), result.path("input"));
    }

    @Test
    void testMidConversationBlockInAssistantTurnPrecedesIt() throws Exception {
        JsonNode result = translate("""
                {"model":"m","messages":[
                   {"role":"user","content":"hi"},
                   {"role":"assistant","content":[{"type":"text","text":"hello"},
                     {"type":"mid_conv_system","content":[{"type":"text","text":"be brief"}]}]}]}""");

        assertEquals(MAPPER.readTree("""
                [{"type":"message","role":"user","content":[{"type":"input_text","text":"hi"}]},
                 {"type":"message","role":"system","content":[{"type":"input_text","text":"be brief"}]},
                 {"type":"message","role":"assistant","content":"hello"}]"""), result.path("input"));
    }

    @Test
    void testCompactionWithoutSummarySendsNothing() throws Exception {
        // a null content is a failed compaction, which Anthropic treats as a no-op
        JsonNode result = translate("""
                {"model":"m","messages":[
                   {"role":"assistant","content":[{"type":"compaction","content":null}]},
                   {"role":"user","content":[{"type":"compaction","content":"Earlier: we fixed X."}]}]}""");

        assertEquals(MAPPER.readTree("""
                [{"type":"message","role":"user","content":[{"type":"input_text","text":"Earlier: we fixed X."}]}]"""),
                result.path("input"));
    }

    @Test
    void testToolsAreRefused() {
        assertThrows(UntranslatableRequestException.class, () -> translate("""
                {"model":"m","messages":[],"tools":[{"name":"t","input_schema":{}}]}"""));
    }

    @Test
    void testMessagesMustBeAnArray() {
        assertThrows(UntranslatableRequestException.class, () -> translate("""
                {"model":"m","messages":{"role":"user","content":"x"}}"""));
    }

    @Test
    void testUnknownRoleIsRefused() {
        assertThrows(UntranslatableRequestException.class, () -> translate("""
                {"model":"m","messages":[{"role":"tool","content":"x"}]}"""));
    }
}
