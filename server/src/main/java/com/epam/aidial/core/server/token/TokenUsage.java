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

    private BigDecimal cost;
    private BigDecimal aggCost;

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
                + (promptTokensDetails != null ? ", cached_prompt=" + promptTokensDetails.getCachedTokens() : "")
                + ", total=" + totalTokens;
    }
}