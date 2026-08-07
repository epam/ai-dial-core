package com.epam.aidial.core.server.token;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class TokenUsageTest {

    @Test
    public void testIsEmpty_FreshInstance() {
        assertTrue(new TokenUsage().isEmpty());
    }

    @Test
    public void testIsEmpty_FalseWhenAnyCounterNonZero() {
        TokenUsage promptOnly = new TokenUsage();
        promptOnly.setPromptTokens(1);
        assertFalse(promptOnly.isEmpty());

        TokenUsage completionOnly = new TokenUsage();
        completionOnly.setCompletionTokens(1);
        assertFalse(completionOnly.isEmpty());

        TokenUsage totalOnly = new TokenUsage();
        totalOnly.setTotalTokens(1);
        assertFalse(totalOnly.isEmpty());
    }

    @Test
    public void testIsEmpty_FalseWhenDetailsPresentEvenIfCountersAreZero() {
        TokenUsage tokenUsage = new TokenUsage();
        tokenUsage.setPromptTokensDetails(promptDetails(0, 0));
        assertFalse(tokenUsage.isEmpty());
    }

    @Test
    public void testIsEmpty_TrueWhenCostSetButNoUsage() {
        // cost/aggCost are billing metadata, not usage - a bare cost stamp shouldn't count as "usage"
        TokenUsage tokenUsage = new TokenUsage();
        tokenUsage.setCost(new BigDecimal("1.0"));
        tokenUsage.setAggCost(new BigDecimal("1.0"));
        assertTrue(tokenUsage.isEmpty());
    }

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

    @Test
    public void testAssign_OverwritesCountersAndCostRatherThanSumming() {
        TokenUsage tokenUsage = new TokenUsage();
        tokenUsage.setPromptTokens(20);
        tokenUsage.setCompletionTokens(80);
        tokenUsage.setTotalTokens(100);
        tokenUsage.setCost(new BigDecimal("10.0"));

        TokenUsage ownReport = new TokenUsage();
        ownReport.setPromptTokens(5);
        ownReport.setTotalTokens(5);

        tokenUsage.assign(ownReport);

        assertEquals(5, tokenUsage.getPromptTokens());
        assertEquals(0, tokenUsage.getCompletionTokens());
        assertEquals(5, tokenUsage.getTotalTokens());
        assertNull(tokenUsage.getCost());
    }

    @Test
    public void testAssign_AccumulatesAggCostInsteadOfOverwriting() {
        // simulates a descendant's cost already having been rolled up via increaseAggCost
        // before this span's own deployment self-reports - assign must not erase it.
        TokenUsage tokenUsage = new TokenUsage();
        tokenUsage.increaseAggCost(new BigDecimal("10.0"));

        TokenUsage ownReport = new TokenUsage();
        ownReport.setTotalTokens(5);
        ownReport.setPromptTokens(5);

        tokenUsage.assign(ownReport);

        assertEquals(5, tokenUsage.getTotalTokens());
        assertEquals(new BigDecimal("10.0"), tokenUsage.getAggCost());
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
