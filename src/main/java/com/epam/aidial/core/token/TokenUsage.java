package com.epam.aidial.core.token;

import com.epam.aidial.core.config.Pricing;
import lombok.Data;

@Data
public class TokenUsage {
    private long completionTokens;
    private long promptTokens;
    private long totalTokens;
    private Double cost;
    private Double aggCost;

    public void calculateCost(Pricing pricing) {
        if (pricing == null) {
            return;
        }
        String unit = pricing.getUnit();
        if (!"token".equals(unit)) {
            return;
        }
        double cost = 0.0;
        if (pricing.getPrompt() != null) {
            cost += promptTokens * Double.parseDouble(pricing.getPrompt());
        }
        if (pricing.getCompletion() != null) {
            cost += completionTokens * Double.parseDouble(pricing.getCompletion());
        }
        if (pricing.getPrompt() != null || pricing.getCompletion() != null) {
            this.cost = cost;
        }
    }

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