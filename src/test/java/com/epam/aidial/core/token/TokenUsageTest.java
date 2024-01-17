package com.epam.aidial.core.token;

import com.epam.aidial.core.config.Pricing;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

public class TokenUsageTest {

    @Test
    public void testCalculateCost_NullPricing() {
        TokenUsage tokenUsage = new TokenUsage();
        tokenUsage.calculateCost(null);
        assertNull(tokenUsage.getCost());
    }

    @Test
    public void testCalculateCost_DifferentUnit() {
        TokenUsage tokenUsage = new TokenUsage();
        Pricing pricing = new Pricing();
        pricing.setUnit("other");

        tokenUsage.calculateCost(pricing);

        assertNull(tokenUsage.getCost());
    }

    @Test
    public void testCalculateCost_PromptCompletionNulls() {
        TokenUsage tokenUsage = new TokenUsage();
        Pricing pricing = new Pricing();
        pricing.setUnit("token");

        tokenUsage.calculateCost(pricing);

        assertNull(tokenUsage.getCost());
    }

    @Test
    public void testCalculateCost_Normal() {
        TokenUsage tokenUsage = new TokenUsage();
        tokenUsage.setPromptTokens(10);
        tokenUsage.setCompletionTokens(50);

        Pricing pricing = new Pricing();
        pricing.setUnit("token");
        pricing.setPrompt("0.5");
        pricing.setCompletion("0.8");

        tokenUsage.calculateCost(pricing);

        assertEquals(45, tokenUsage.getCost());
    }

    @Test
    public void testCalculateCost_PromptNull() {
        TokenUsage tokenUsage = new TokenUsage();
        tokenUsage.setPromptTokens(10);
        tokenUsage.setCompletionTokens(50);

        Pricing pricing = new Pricing();
        pricing.setUnit("token");
        pricing.setCompletion("0.8");

        tokenUsage.calculateCost(pricing);

        assertEquals(40, tokenUsage.getCost());
    }

    @Test
    public void testCalculateCost_CompletionNull() {
        TokenUsage tokenUsage = new TokenUsage();
        tokenUsage.setPromptTokens(10);
        tokenUsage.setCompletionTokens(50);

        Pricing pricing = new Pricing();
        pricing.setUnit("token");
        pricing.setPrompt("0.5");

        tokenUsage.calculateCost(pricing);

        assertEquals(5, tokenUsage.getCost());
    }

    @Test
    public void testIncrease_Model() {
        TokenUsage tokenUsage = new TokenUsage();
        tokenUsage.setPromptTokens(10);
        tokenUsage.setCompletionTokens(50);
        tokenUsage.setTotalTokens(60);

        TokenUsage modelUsage = new TokenUsage();
        modelUsage.setPromptTokens(10);
        modelUsage.setCompletionTokens(50);
        modelUsage.setTotalTokens(60);
        modelUsage.setCost(10.0);

        tokenUsage.increase(modelUsage);

        assertEquals(20, tokenUsage.getPromptTokens());
        assertEquals(100, tokenUsage.getCompletionTokens());
        assertEquals(120, tokenUsage.getTotalTokens());
        assertEquals(10.0, tokenUsage.getAggCost());
    }

    @Test
    public void testIncrease_App() {
        TokenUsage tokenUsage = new TokenUsage();
        tokenUsage.setPromptTokens(10);
        tokenUsage.setCompletionTokens(50);
        tokenUsage.setTotalTokens(60);

        TokenUsage appUsage = new TokenUsage();
        appUsage.setPromptTokens(10);
        appUsage.setCompletionTokens(50);
        appUsage.setTotalTokens(60);
        appUsage.setAggCost(10.0);

        tokenUsage.increase(appUsage);

        assertEquals(20, tokenUsage.getPromptTokens());
        assertEquals(100, tokenUsage.getCompletionTokens());
        assertEquals(120, tokenUsage.getTotalTokens());
        assertEquals(10.0, tokenUsage.getAggCost());
    }
}
