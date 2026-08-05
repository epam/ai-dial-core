package com.epam.aidial.core.server.token;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.math.BigDecimal;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class TokenUsage {
    @JsonAlias({"output_tokens", "completion_tokens", "completionTokens"})
    private long completionTokens;
    @JsonAlias({"input_tokens", "prompt_tokens", "promptTokens"})
    private long promptTokens;
    @JsonAlias({"total_tokens", "totalTokens"})
    private long totalTokens;
    @JsonAlias({"input_tokens_details", "prompt_tokens_details", "promptsTokenDetails"})
    private PromptTokensDetails promptTokensDetails;
    @JsonAlias({"output_tokens_details", "completion_tokens_details", "completionTokensDetails"})
    private CompletionTokensDetails completionTokensDetails;

    private BigDecimal cost;
    private BigDecimal aggCost;

    public boolean isEmpty() {
        return completionTokens == 0 && promptTokens == 0 && totalTokens == 0
                && promptTokensDetails == null && completionTokensDetails == null;
    }

    public void increase(TokenUsage other) {
        if (other == null) {
            return;
        }
        completionTokens += other.completionTokens;
        promptTokens += other.promptTokens;
        totalTokens += other.totalTokens;
        if (promptTokensDetails == null) {
            promptTokensDetails = other.promptTokensDetails;
        } else {
            promptTokensDetails.increase(other.promptTokensDetails);
        }
        if (completionTokensDetails == null) {
            completionTokensDetails = other.completionTokensDetails;
        } else {
            completionTokensDetails.increase(other.completionTokensDetails);
        }
        aggCost(other.aggCost);
    }

    private void aggCost(BigDecimal val) {
        if (val == null) {
            return;
        }
        if (aggCost == null) {
            aggCost = val;
        } else {
            aggCost = aggCost.add(val);
        }
    }

    @Override
    public String toString() {
        return "completion=" + completionTokens
                + ", prompt=" + promptTokens
                + (promptTokensDetails != null ? ", cached_prompt=" + promptTokensDetails.getCachedTokens()
                        + ", cache_write=" + promptTokensDetails.getCacheWriteTokens() : "")
                + (completionTokensDetails != null ? ", reasoning=" + completionTokensDetails.getReasoningTokens() : "")
                + ", total=" + totalTokens;
    }
}