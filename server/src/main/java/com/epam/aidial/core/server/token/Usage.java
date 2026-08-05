package com.epam.aidial.core.server.token;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Outbound-only token usage breakdown for a single {@link UsagePerModel} entry. Deliberately
 * distinct from {@link TokenUsage}: this serializes snake_case field names and never carries
 * {@code cost}/{@code aggCost}, which must not leak into a client-facing response body.
 */
@Data
@NoArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class Usage {
    @JsonProperty("prompt_tokens")
    private long promptTokens;
    @JsonProperty("completion_tokens")
    private long completionTokens;
    @JsonProperty("total_tokens")
    private long totalTokens;
    @JsonProperty("prompt_tokens_details")
    private PromptTokensDetails promptTokensDetails;
    @JsonProperty("completion_tokens_details")
    private CompletionTokensDetails completionTokensDetails;

    public Usage(TokenUsage tokenUsage) {
        this.promptTokens = tokenUsage.getPromptTokens();
        this.completionTokens = tokenUsage.getCompletionTokens();
        this.totalTokens = tokenUsage.getTotalTokens();
        this.promptTokensDetails = tokenUsage.getPromptTokensDetails();
        this.completionTokensDetails = tokenUsage.getCompletionTokensDetails();
    }
}
