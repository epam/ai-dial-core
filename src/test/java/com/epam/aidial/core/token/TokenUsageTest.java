package com.epam.aidial.core.token;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class TokenUsageTest {

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

        tokenUsage.increase(modelUsage);

        assertEquals(20, tokenUsage.getPromptTokens());
        assertEquals(100, tokenUsage.getCompletionTokens());
        assertEquals(120, tokenUsage.getTotalTokens());
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

        tokenUsage.increase(appUsage);

        assertEquals(20, tokenUsage.getPromptTokens());
        assertEquals(100, tokenUsage.getCompletionTokens());
        assertEquals(120, tokenUsage.getTotalTokens());
    }
}
