package com.epam.aidial.core.translator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
    void testErrorTypeReadsTypeBeforeCode() throws Exception {
        assertEquals("invalid_request_error", ResponsesToMessages.errorType(
                json("{\"type\":\"invalid_request_error\",\"code\":\"rate_limit_exceeded\"}")));
        assertEquals("rate_limit_error", ResponsesToMessages.errorType(json("{\"code\":\"rate_limit_exceeded\"}")));
        assertEquals("timeout_error", ResponsesToMessages.errorType(json("{\"code\":\"vector_store_timeout\"}")));
        assertEquals("api_error", ResponsesToMessages.errorType(json("{\"code\":\"server_error\"}")));
    }
}
