package com.epam.aidial.core.server.token;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class TokenUsageTest {

    @Test
    public void testIncrease_Model() {
        TokenUsage tokenUsage = new TokenUsage();
        tokenUsage.setPromptTokens(10);
        tokenUsage.setCompletionTokens(50);
        tokenUsage.setTotalTokens(60);
        tokenUsage.setPromptTokensDetails(promptDetails(3, 4));
        tokenUsage.setCompletionTokensDetails(completionDetails(5));

        TokenUsage modelUsage = new TokenUsage();
        modelUsage.setPromptTokens(10);
        modelUsage.setCompletionTokens(50);
        modelUsage.setTotalTokens(60);
        modelUsage.setCost(new BigDecimal("10.0"));
        modelUsage.setAggCost(new BigDecimal("10.0"));
        modelUsage.setPromptTokensDetails(promptDetails(3, 4));
        modelUsage.setCompletionTokensDetails(completionDetails(5));

        tokenUsage.increase(modelUsage);

        assertEquals(20, tokenUsage.getPromptTokens());
        assertEquals(100, tokenUsage.getCompletionTokens());
        assertEquals(120, tokenUsage.getTotalTokens());
        assertEquals(new BigDecimal("10.0"), tokenUsage.getAggCost());
        assertEquals(6, tokenUsage.getPromptTokensDetails().getCachedTokens());
        assertEquals(8, tokenUsage.getPromptTokensDetails().getCacheWriteTokens());
        assertEquals(10, tokenUsage.getCompletionTokensDetails().getReasoningTokens());
    }

    @Test
    public void testIncrease_Details_FromNull() {
        TokenUsage tokenUsage = new TokenUsage();

        TokenUsage other = new TokenUsage();
        other.setPromptTokensDetails(promptDetails(3, 4));
        other.setCompletionTokensDetails(completionDetails(5));

        tokenUsage.increase(other);

        assertEquals(3, tokenUsage.getPromptTokensDetails().getCachedTokens());
        assertEquals(4, tokenUsage.getPromptTokensDetails().getCacheWriteTokens());
        assertEquals(5, tokenUsage.getCompletionTokensDetails().getReasoningTokens());
    }

    private static PromptTokensDetails promptDetails(long cachedTokens, long cacheWriteTokens) {
        PromptTokensDetails details = new PromptTokensDetails();
        details.setCachedTokens(cachedTokens);
        details.setCacheWriteTokens(cacheWriteTokens);
        return details;
    }

    private static CompletionTokensDetails completionDetails(long reasoningTokens) {
        CompletionTokensDetails details = new CompletionTokensDetails();
        details.setReasoningTokens(reasoningTokens);
        return details;
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
        appUsage.setAggCost(new BigDecimal("10.0"));

        tokenUsage.increase(appUsage);

        assertEquals(20, tokenUsage.getPromptTokens());
        assertEquals(100, tokenUsage.getCompletionTokens());
        assertEquals(120, tokenUsage.getTotalTokens());
        assertEquals(new BigDecimal("10.0"), tokenUsage.getAggCost());
    }
}
