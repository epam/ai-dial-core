package com.epam.aidial.core.config;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.regex.PatternSyntaxException;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TracingTest {

    @Test
    void defaultsAreOptInAndIncludeKnownConversationHeaders() {
        Tracing tracing = new Tracing();

        assertFalse(tracing.isGenAiSpanAttributes());
        assertFalse(tracing.isResponseTraceHeaders());
        assertTrue(tracing.getConversationIdHeaders().containsAll(List.of(
                "x-claude-code-session-id",
                "thread-id",
                "x-session-id",
                "x-dial-client-channel-id",
                "X-CONVERSATION-ID")));
        assertTrue(tracing.getGenAiAttributeBlacklist().isEmpty());
    }

    @Test
    void attributeBlacklistMatchesWholeAttributeName() {
        Tracing tracing = new Tracing();
        tracing.setGenAiAttributeBlacklist(List.of("gen_ai\\.request\\..*", "dial\\.usage\\.total_tokens"));

        assertTrue(tracing.isAttributeBlacklisted("gen_ai.request.model"));
        assertTrue(tracing.isAttributeBlacklisted("dial.usage.total_tokens"));
        assertFalse(tracing.isAttributeBlacklisted("gen_ai.response.id"));
        assertFalse(tracing.isAttributeBlacklisted("xgen_ai.request.model"));
    }

    @Test
    void invalidAttributeBlacklistRegexIsRejectedWhenConfigured() {
        Tracing tracing = new Tracing();

        assertThrows(PatternSyntaxException.class,
                () -> tracing.setGenAiAttributeBlacklist(List.of("[")));
    }

    @Test
    void invalidAttributeBlacklistRegexIsRejectedWhenConfigIsDeserialized() {
        ObjectMapper mapper = new ObjectMapper();
        String config = """
                {"tracing":{"genAiAttributeBlacklist":["["]}}
                """;

        JsonProcessingException error = assertThrows(JsonProcessingException.class,
                () -> mapper.readValue(config, Config.class));
        assertTrue(error.getCause() instanceof PatternSyntaxException);
    }
}
