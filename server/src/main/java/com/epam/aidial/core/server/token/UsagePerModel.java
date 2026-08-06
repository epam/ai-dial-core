package com.epam.aidial.core.server.token;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * A single entry of {@code statistics.usage_per_model}: the token usage aggregated for one
 * reporting deployment across the call tree. See {@link TokenStatsTracker} for how entries are
 * accumulated. Fields mirror {@link TokenUsage} but serialize snake_case and never carry
 * {@code cost}/{@code aggCost}, which must not leak into a client-facing response body.
 */
@Data
@NoArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class UsagePerModel {
    private Integer index;
    private String model;
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

    public UsagePerModel(Integer index, String model, TokenUsage tokenUsage) {
        this.index = index;
        this.model = model;
        this.promptTokens = tokenUsage.getPromptTokens();
        this.completionTokens = tokenUsage.getCompletionTokens();
        this.totalTokens = tokenUsage.getTotalTokens();
        this.promptTokensDetails = tokenUsage.getPromptTokensDetails();
        this.completionTokensDetails = tokenUsage.getCompletionTokensDetails();
    }

    public UsagePerModel(String model, TokenUsage tokenUsage) {
        this(null, model, tokenUsage);
    }
}
