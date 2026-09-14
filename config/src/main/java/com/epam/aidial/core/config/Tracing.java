package com.epam.aidial.core.config;

import lombok.Data;

import java.util.List;

@Data
public class Tracing {
    private boolean genAiSpanAttributes;
    private boolean responseTraceHeaders;
    private List<String> conversationIdHeaders = List.of(
            "x-claude-code-session-id",
            "thread-id",
            "x-session-id",
            "x-dial-client-channel-id",
            "X-CONVERSATION-ID");

    public void setConversationIdHeaders(List<String> conversationIdHeaders) {
        this.conversationIdHeaders = List.copyOf(conversationIdHeaders);
    }
}
