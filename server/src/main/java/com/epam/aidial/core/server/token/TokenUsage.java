package com.epam.aidial.core.server.token;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.math.BigDecimal;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class TokenUsage {
    @JsonAlias({"completion_tokens", "completionTokens"})
    private long completionTokens;
    @JsonAlias({"prompt_tokens", "promptTokens"})
    private long promptTokens;
    @JsonAlias({"total_tokens", "totalTokens"})
    private long totalTokens;
    @JsonAlias({"prompt_token_details", "promptTokenDetail"})
    private PromptTokenDetails promptTokenDetail;

    private BigDecimal cost;
    private BigDecimal aggCost;

    public void increase(TokenUsage other) {
        if (other == null) {
            return;
        }
        completionTokens += other.completionTokens;
        promptTokens += other.promptTokens;
        totalTokens += other.totalTokens;
        if (promptTokenDetail == null) {
            promptTokenDetail = other.promptTokenDetail;
        } else{
            promptTokenDetail.increase(other.promptTokenDetail);
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
                + (promptTokenDetail != null ? ", cached_prompt=" + promptTokenDetail.getCachedTokens() : "")
                + ", total=" + totalTokens;
    }
}