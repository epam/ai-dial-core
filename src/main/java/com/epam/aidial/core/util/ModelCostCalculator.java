package com.epam.aidial.core.util;

import com.epam.aidial.core.ProxyContext;
import com.epam.aidial.core.config.Deployment;
import com.epam.aidial.core.config.Model;
import com.epam.aidial.core.config.Pricing;
import com.epam.aidial.core.token.TokenUsage;
import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;

import java.math.BigDecimal;

@Slf4j
@UtilityClass
public class ModelCostCalculator {

    public static BigDecimal calculate(ProxyContext context) {
        Deployment deployment = context.getDeployment();
        if (!(deployment instanceof Model model)) {
            return null;
        }

        Pricing pricing = model.getPricing();
        if (pricing == null) {
            return null;
        }

        return calculate(context.getTokenUsage(), pricing.getPrompt(), pricing.getCompletion());
    }

    private static BigDecimal calculate(TokenUsage tokenUsage, String promptRate, String completionRate) {
        if (tokenUsage == null) {
            return null;
        }
        BigDecimal cost = null;
        if (promptRate != null) {
            cost = new BigDecimal(tokenUsage.getPromptTokens()).multiply(new BigDecimal(promptRate));
        }
        if (completionRate != null) {
            BigDecimal completionCost = new BigDecimal(tokenUsage.getCompletionTokens()).multiply(new BigDecimal(completionRate));
            if (cost != null) {
                cost = cost.add(completionCost);
            } else {
                cost = completionCost;
            }
        }
        return cost;
    }

}
