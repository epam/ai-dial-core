package com.epam.aidial.core.token;

import lombok.Data;

import java.math.BigDecimal;

@Data
public class DeploymentCostStats {
    private TokenUsage tokenUsage;
    private BigDecimal cost;
    private BigDecimal aggCost;
    private int requestContentLength;
    private int responseContentLength;

    public void increase(DeploymentCostStats other) {
        if (other == null) {
            return;
        }
        if (other.tokenUsage != null) {
            if (tokenUsage == null) {
                tokenUsage = other.tokenUsage;
            } else {
                tokenUsage.increase(other.tokenUsage);
            }
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
}
