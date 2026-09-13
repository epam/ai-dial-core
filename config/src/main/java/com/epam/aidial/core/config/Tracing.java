package com.epam.aidial.core.config;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;

import java.util.List;
import java.util.regex.Pattern;

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
    private List<String> genAiAttributeBlacklist = List.of();

    @JsonIgnore
    private List<Pattern> attributeBlacklistPatterns = List.of();

    public void setConversationIdHeaders(List<String> conversationIdHeaders) {
        this.conversationIdHeaders = List.copyOf(conversationIdHeaders);
    }

    public void setGenAiAttributeBlacklist(List<String> genAiAttributeBlacklist) {
        this.genAiAttributeBlacklist = List.copyOf(genAiAttributeBlacklist);
        this.attributeBlacklistPatterns = this.genAiAttributeBlacklist.stream()
                .map(Pattern::compile)
                .toList();
    }

    public boolean isAttributeBlacklisted(String attribute) {
        return attributeBlacklistPatterns.stream()
                .anyMatch(pattern -> pattern.matcher(attribute).matches());
    }
}
