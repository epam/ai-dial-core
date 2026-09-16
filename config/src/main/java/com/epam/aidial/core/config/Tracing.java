package com.epam.aidial.core.config;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Locale;
import java.util.Set;

@Slf4j
@Data
public class Tracing {
    /**
     * Header names whose value must never be published as a conversation id - naming one here
     * would put a credential on the span and on every log record of the request.
     */
    private static final Set<String> CREDENTIAL_HEADERS = Set.of(
            "authorization",
            "proxy-authorization",
            "cookie",
            "set-cookie",
            "api-key",
            "api_key",
            "x-api-key",
            "x-auth-token",
            "x-amz-security-token");

    private boolean genAiSpanAttributes;
    private boolean responseTraceHeaders;
    private List<String> conversationIdHeaders = List.of(
            "x-claude-code-session-id",
            "thread-id",
            "x-session-id",
            "x-dial-client-channel-id",
            "X-CONVERSATION-ID");

    /**
     * One bad entry must not fail the whole config reload, so null, blank and credential header
     * names are dropped rather than thrown on. An explicit {@code null} list means no correlation.
     */
    public void setConversationIdHeaders(List<String> conversationIdHeaders) {
        if (conversationIdHeaders == null) {
            this.conversationIdHeaders = List.of();
            return;
        }
        this.conversationIdHeaders = conversationIdHeaders.stream()
                .filter(header -> header != null && !header.isBlank())
                .map(String::trim)
                .filter(Tracing::isPublishable)
                .toList();
    }

    private static boolean isPublishable(String header) {
        if (CREDENTIAL_HEADERS.contains(header.toLowerCase(Locale.ROOT))) {
            log.warn("Ignoring conversation id header {}: credential headers are never published", header);
            return false;
        }
        return true;
    }
}
