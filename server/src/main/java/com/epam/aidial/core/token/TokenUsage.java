package com.epam.aidial.core.token;

import lombok.Data;

import java.math.BigDecimal;

@Data
public class TokenUsage {
    private long completionTokens;
    private long promptTokens;
    private long totalTokens;
    private BigDecimal cost;
    private BigDecimal aggCost;

    public void increase(TokenUsage other) {
        if (other == null) {
            return;
        }
        completionTokens += other.completionTokens;
        promptTokens += other.promptTokens;
        totalTokens += other.totalTokens;
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
                + ", total=" + totalTokens;
    }
}