package com.epam.aidial.core.translator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResponsesToMessagesTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * Parsed, so numbers compare by value whatever node type built them.
     */
    private static JsonNode json(String json) throws Exception {
        return MAPPER.readTree(json);
    }

    @Test
    void testCacheCountsComeOutOfTheInputTotal() throws Exception {
        JsonNode usage = ResponsesToMessages.convertUsage(json("""
                {"input_tokens":100,"input_tokens_details":{"cached_tokens":60,"cache_write_tokens":30},
                 "output_tokens":20,"output_tokens_details":{"reasoning_tokens":5}}"""));

        assertEquals(json("""
                {"input_tokens":10,"output_tokens":20,"cache_read_input_tokens":60,"cache_creation_input_tokens":30,
                 "output_tokens_details":{"thinking_tokens":5}}"""), json(usage.toString()));
    }

    @Test
    void testBreakdownOverTheTotalIsClampedToIt() throws Exception {
        JsonNode usage = ResponsesToMessages.convertUsage(json("""
                {"input_tokens":100,"input_tokens_details":{"cached_tokens":80,"cacheWriteTokens":50},
                 "output_tokens":3,"output_tokens_details":{"reasoning_tokens":9}}"""));

        assertEquals(0, usage.path("input_tokens").asLong());
        assertEquals(80, usage.path("cache_read_input_tokens").asLong());
        assertEquals(20, usage.path("cache_creation_input_tokens").asLong());
        assertEquals(3, usage.path("output_tokens_details").path("thinking_tokens").asLong());
    }

    @Test
    void testNoThinkingDetailsWithoutReasoning() throws Exception {
        assertEquals(json("""
                {"input_tokens":0,"output_tokens":0,"cache_read_input_tokens":0,"cache_creation_input_tokens":0}"""),
                json(ResponsesToMessages.convertUsage(json("{}")).toString()));
    }

    @Test
    void testStopReasonPrecedence() throws Exception {
        JsonNode truncated = json("{\"status\":\"incomplete\",\"incomplete_details\":{\"reason\":\"max_output_tokens\"}}");
        JsonNode filtered = json("{\"status\":\"incomplete\",\"incomplete_details\":{\"reason\":\"content_filter\"}}");
        JsonNode completed = json("{\"status\":\"completed\"}");

        assertEquals("max_tokens", ResponsesToMessages.stopReason(truncated, true, false));
        assertEquals("refusal", ResponsesToMessages.stopReason(filtered, true, false));
        assertEquals("tool_use", ResponsesToMessages.stopReason(completed, true, true));
        assertEquals("refusal", ResponsesToMessages.stopReason(completed, false, true));
        assertEquals("end_turn", ResponsesToMessages.stopReason(completed, false, false));
    }

    @Test
    void testFailureTable() throws Exception {
        // Failure the request caused
        assertFailure(400, "invalid_request_error", "This model does not support image inputs", ResponsesToMessages.failure(json("""
                {"type":"invalid_request_error","code":"invalid_request_error","message":"This model does not support image inputs"}""")));
        // Request-fault code
        assertFailure(400, "invalid_request_error", "bad prompt", ResponsesToMessages.failure(json("{\"code\":\"invalid_prompt\",\"message\":\"bad prompt\"}")));
        assertFailure(400, "invalid_request_error", "x", ResponsesToMessages.failure(json("{\"code\":\"image_content_policy_violation\",\"message\":\"x\"}")));
        // Rate limit inside a 200
        assertFailure(429, "rate_limit_error", "slow down", ResponsesToMessages.failure(json("{\"code\":\"rate_limit_exceeded\",\"message\":\"slow down\"}")));
        // Vector store timeout
        assertFailure(504, "timeout_error", "late", ResponsesToMessages.failure(json("{\"code\":\"vector_store_timeout\",\"message\":\"late\"}")));
        // an unknown name, and a name that is not a string, are no evidence the request was at fault
        assertFailure(502, "api_error", "upstream exploded", ResponsesToMessages.failure(json("{\"code\":\"server_error\",\"message\":\"upstream exploded\"}")));
        assertFailure(502, "api_error", "The upstream request failed", ResponsesToMessages.failure(json("{\"code\":429,\"message\":5}")));
        // type is read before code
        assertFailure(400, "invalid_request_error", "both", ResponsesToMessages.failure(json("""
                {"type":"invalid_request_error","code":"rate_limit_exceeded","message":"both"}""")));
        assertFailure(429, "rate_limit_error", "The upstream request failed", ResponsesToMessages.failure(json("""
                {"type":"server_error","code":"rate_limit_exceeded"}""")));
        assertFailure(502, "api_error", "The upstream request failed", ResponsesToMessages.failure(json("\"boom\"")));
    }

    @Test
    void testTextAndFunctionCall() throws Exception {
        JsonNode message = translate("""
                {"status":"completed","output":[
                  {"type":"message","content":[{"type":"output_text","text":"let me check","annotations":[]}]},
                  {"type":"function_call","call_id":"call_abc","name":"get_weather","arguments":"{\\"city\\": \\"Kyiv\\"}"}]}""");

        assertEquals(json("""
                [{"type":"text","text":"let me check","citations":null},
                 {"type":"tool_use","id":"call_abc","name":"get_weather","input":{"city":"Kyiv"},"caller":null}]"""), message.path("content"));
        assertEquals("tool_use", message.path("stop_reason").asText());
    }

    @Test
    void testAliasedToolNameIsRestored() throws Exception {
        String original = "mcp__dial-knowledge-base__search_documents_by_semantic_similarity_v2";
        ToolNames toolNames = new ToolNames();
        String alias = toolNames.alias(original, Set.of());
        JsonNode message = ResponsesToMessages.translateResponse(json("""
                {"status":"completed","output":[{"type":"function_call","call_id":"c","name":"%s","arguments":"{}"},
                  {"type":"function_call","call_id":"d","name":"Read","arguments":"{}"}]}""".formatted(alias)),
                "requested", "msg_dial", toolNames, false);

        assertEquals(original, message.path("content").path(0).path("name").asText());
        assertEquals("Read", message.path("content").path(1).path("name").asText());
    }

    @Test
    void testArgumentsThatAreNotAnObjectBecomeEmpty() throws Exception {
        JsonNode message = translate("""
                {"status":"completed","output":[
                  {"type":"function_call","call_id":"a","name":"f","arguments":"{\\"city\\": "},
                  {"type":"function_call","call_id":"b","name":"f","arguments":"[1, 2]"},
                  {"type":"function_call","call_id":"c","name":"f","arguments":""},
                  {"type":"function_call","call_id":"d","name":"f","arguments":"{\\"a\\": 1} x"}]}""");

        for (JsonNode block : message.path("content")) {
            assertEquals(json("{}"), block.path("input"), block.toString());
        }
    }

    @Test
    void testRefusalBecomesText() throws Exception {
        JsonNode message = translate("""
                {"status":"completed","output":[{"type":"message","content":[{"type":"refusal","refusal":"I can't help with that"}]}]}""");

        assertEquals(json("[{\"type\":\"text\",\"text\":\"I can't help with that\",\"citations\":null}]"), message.path("content"));
        assertEquals("refusal", message.path("stop_reason").asText());
    }

    @Test
    void testEmptyOutputGetsAnEmptyTextBlock() throws Exception {
        JsonNode empty = json("[{\"type\":\"text\",\"text\":\"\",\"citations\":null}]");

        assertEquals(empty, translate("{\"status\":\"completed\",\"output\":[]}").path("content"));
        assertEquals(empty, translate("{\"status\":\"completed\",\"output\":[{\"type\":\"reasoning\",\"id\":\"rs_1\",\"summary\":[]}]}").path("content"));
        // an item of a type the translation does not know gives nothing
        assertEquals(empty, translate("{\"status\":\"completed\",\"output\":[{\"type\":\"file_search_call\",\"id\":\"fs_1\"}]}").path("content"));
    }

    @Test
    void testEncryptedReasoningWithSummary() throws Exception {
        JsonNode message = translate("""
                {"status":"completed","output":[
                  {"type":"reasoning","id":"rs_1","encrypted_content":"cipher","summary":[{"type":"summary_text","text":"a"},{"type":"summary_text","text":"b"}]},
                  {"type":"message","content":[{"type":"output_text","text":"42"}]}]}""");

        assertEquals(json("""
                {"type":"thinking","thinking":"a\\n\\nb",
                 "signature":"{\\"dial_translator_reasoning_wrapper\\":{\\"version\\":2,\\"id\\":\\"rs_1\\",\\"encrypted_content\\":\\"cipher\\"}}"}"""),
                message.path("content").path(0));
        assertEquals("text", message.path("content").path(1).path("type").asText());
    }

    @Test
    void testEncryptedReasoningWithoutText() throws Exception {
        JsonNode message = translate("""
                {"status":"completed","output":[{"type":"reasoning","id":"rs_1","encrypted_content":"cipher","summary":[]}]}""");

        assertEquals(json("""
                [{"type":"redacted_thinking",
                  "data":"{\\"dial_translator_reasoning_wrapper\\":{\\"version\\":2,\\"id\\":\\"rs_1\\",\\"encrypted_content\\":\\"cipher\\"}}"}]"""),
                message.path("content"));
    }

    @Test
    void testUnencryptedReasoningGivesUnsignedBlocks() throws Exception {
        JsonNode message = translate("""
                {"status":"completed","output":[{"type":"reasoning","id":"rs_1",
                  "summary":[{"type":"summary_text","text":"s"},{"type":"summary_text","text":""}],
                  "content":[{"type":"reasoning_text","text":"c"}]}]}""");

        assertEquals(json("""
                [{"type":"thinking","thinking":"s","signature":""},{"type":"thinking","thinking":"c","signature":""}]"""),
                message.path("content"));
    }

    @Test
    void testOmittedThinking() throws Exception {
        JsonNode message = ResponsesToMessages.translateResponse(json("""
                {"status":"completed","output":[
                  {"type":"reasoning","id":"rs_1","encrypted_content":"cipher","summary":[{"type":"summary_text","text":"plan"}]},
                  {"type":"reasoning","id":"rs_2","summary":[{"type":"summary_text","text":"secret"}]},
                  {"type":"message","content":[{"type":"output_text","text":"answer"}]}]}"""),
                "requested", "msg_dial", new ToolNames(), true);

        JsonNode content = message.path("content");
        assertEquals(2, content.size(), content.toString());
        assertEquals("thinking", content.path(0).path("type").asText());
        assertEquals("", content.path(0).path("thinking").asText());
        assertTrue(content.path(0).path("signature").asText().contains("cipher"));
        assertEquals("answer", content.path(1).path("text").asText());
    }

    @Test
    void testModelFallsBackToTheRequestedModel() throws Exception {
        assertEquals("upstream", translate("{\"status\":\"completed\",\"model\":\"upstream\",\"output\":[]}").path("model").asText());
        assertEquals("requested", translate("{\"status\":\"completed\",\"output\":[]}").path("model").asText());
        assertEquals("requested", translate("{\"status\":\"completed\",\"model\":\"\",\"output\":[]}").path("model").asText());
    }

    @Test
    void testNullValuedFields() throws Exception {
        JsonNode message = translate("""
                {"id":"resp_075fcfcf","status":"completed","model":"upstream","output":[{"type":"message","content":[{"type":"output_text","text":"hi"}]}],
                 "usage":{"input_tokens":12,"input_tokens_details":{"cached_tokens":4},"output_tokens":7}}""");

        assertEquals(json("""
                {"id":"msg_dial","type":"message","role":"assistant","model":"upstream",
                 "content":[{"type":"text","text":"hi","citations":null}],
                 "stop_reason":"end_turn","stop_sequence":null,"container":null,"stop_details":null,
                 "usage":{"input_tokens":8,"output_tokens":7,"cache_read_input_tokens":4,"cache_creation_input_tokens":0,
                          "cache_creation":null,"inference_geo":null,"server_tool_use":null,"service_tier":null}}"""), json(message.toString()));
    }

    @Test
    void testMissingStatusIsReadAsCompleted() throws Exception {
        JsonNode message = translate("{\"status\":null,\"output\":[{\"type\":\"message\",\"content\":[{\"type\":\"output_text\",\"text\":\"hi\"}]}]}");

        assertEquals("hi", message.path("content").path(0).path("text").asText());
        assertEquals("end_turn", message.path("stop_reason").asText());
        assertEquals("end_turn", translate("{\"output\":[]}").path("stop_reason").asText());
    }

    @Test
    void testCutOffFunctionCallIsMaxTokens() throws Exception {
        JsonNode message = translate("""
                {"status":"incomplete","incomplete_details":{"reason":"max_output_tokens"},
                 "output":[{"type":"function_call","call_id":"c","name":"f","arguments":"{\\"a\\""}]}""");

        assertEquals("max_tokens", message.path("stop_reason").asText());
    }

    @Test
    void testTranslatedUsage() throws Exception {
        JsonNode message = translate("""
                {"status":"completed","output":[],"usage":{"input_tokens":12,"input_tokens_details":{"cached_tokens":4},
                 "output_tokens":20,"output_tokens_details":{"reasoning_tokens":15}}}""");

        assertEquals(json("""
                {"input_tokens":8,"output_tokens":20,"cache_read_input_tokens":4,"cache_creation_input_tokens":0,
                 "output_tokens_details":{"thinking_tokens":15},
                 "cache_creation":null,"inference_geo":null,"server_tool_use":null,"service_tier":null}"""), json(message.path("usage").toString()));
    }

    @Test
    void testFailedWithoutAnErrorObject() {
        assertFailure(502, "api_error", "The upstream request failed",
                assertThrows(UpstreamFailureException.class, () -> translate("{\"status\":\"failed\",\"output\":[]}")));
        assertFailure(502, "api_error", "upstream exploded", assertThrows(UpstreamFailureException.class, () -> translate("""
                {"status":"failed","error":{"code":"server_error","message":"upstream exploded"},"output":[]}""")));
    }

    @Test
    void testUnfinishedStatusesAreErrors() {
        for (String status : List.of("queued", "in_progress", "cancelled")) {
            UpstreamFailureException failure = assertThrows(UpstreamFailureException.class, () -> translate("""
                    {"status":"%s","output":[{"type":"message","content":[{"type":"output_text","text":"partial"}]}]}""".formatted(status)));
            assertFailure(502, "api_error", "The upstream response did not complete (status '" + status + "')", failure);
        }
    }

    private static JsonNode translate(String response) throws Exception {
        return ResponsesToMessages.translateResponse(json(response), "requested", "msg_dial", new ToolNames(), false);
    }

    private static void assertFailure(int status, String type, String message, UpstreamFailureException failure) {
        assertEquals(status, failure.getStatus());
        assertEquals(type, failure.getType());
        assertEquals(message, failure.getMessage());
    }
}
