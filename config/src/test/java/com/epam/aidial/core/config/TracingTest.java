package com.epam.aidial.core.config;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
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
    }
}
