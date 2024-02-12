package com.epam.aidial.core.util;

import com.epam.aidial.core.ProxyContext;
import com.epam.aidial.core.config.Model;
import com.epam.aidial.core.config.Pricing;
import com.epam.aidial.core.token.TokenUsage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.when;

@SuppressWarnings("checkstyle:LineLength")
@ExtendWith(MockitoExtension.class)
public class ModelCostCalculatorTest {

    @Mock
    private ProxyContext context;

    @Test
    public void testCalculate_DeploymentIsNotModel() {
        assertNull(ModelCostCalculator.calculate(context));
    }

    @Test
    public void testCalculate_PricingIsNull() {
        when(context.getDeployment()).thenReturn(new Model());
        assertNull(ModelCostCalculator.calculate(context));
    }

    @Test
    public void testCalculate_UnknownCostUnit() {
        Model model = new Model();
        Pricing pricing = new Pricing();
        pricing.setUnit("unknown");
        model.setPricing(pricing);
        when(context.getDeployment()).thenReturn(model);
        assertNull(ModelCostCalculator.calculate(context));
    }

    @Test
    public void testCalculate_TokenCost() {
        Model model = new Model();
        Pricing pricing = new Pricing();
        pricing.setPrompt("0.1");
        pricing.setCompletion("0.5");
        pricing.setUnit("token");
        model.setPricing(pricing);
        when(context.getDeployment()).thenReturn(model);

        TokenUsage tokenUsage = new TokenUsage();
        tokenUsage.setCompletionTokens(10);
        tokenUsage.setPromptTokens(10);
        when(context.getTokenUsage()).thenReturn(tokenUsage);

        assertEquals(new BigDecimal("6.0"), ModelCostCalculator.calculate(context));
    }

}
