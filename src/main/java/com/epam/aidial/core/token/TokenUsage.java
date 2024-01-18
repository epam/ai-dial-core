package com.epam.aidial.core.token;

import lombok.Data;

@Data
public class TokenUsage {
    private long completionTokens;
    private long promptTokens;
    private long totalTokens;
    private Double cost;
    private Double aggCost;

    public void increase(TokenUsage other) {
        if (other == null) {
            return;
        }
        completionTokens += other.completionTokens;
        promptTokens += other.promptTokens;
        totalTokens += other.totalTokens;
        aggCost(other.cost);
        aggCost(other.aggCost);
    }

    private void aggCost(Double val) {
        if (val == null) {
            return;
        }
        if (aggCost == null) {
            aggCost = val;
        } else {
            aggCost += val;
        }
    }

    @Override
    public String toString() {
        return "completion=" + completionTokens
                + ", prompt=" + promptTokens
                + ", total=" + totalTokens;
    }
}