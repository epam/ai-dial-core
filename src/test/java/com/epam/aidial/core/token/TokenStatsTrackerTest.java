package com.epam.aidial.core.token;

import com.epam.aidial.core.ProxyContext;
import com.epam.aidial.core.config.ApiKeyData;
import io.vertx.core.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
public class TokenStatsTrackerTest {

    private TokenStatsTracker tracker;

    @BeforeEach
    public void beforeEach() {
        tracker = new TokenStatsTracker();
    }

    /**
     * Tests the flow: chat back-end -> core -> app -> core -> model
     */
    @Test
    public void test1() {
        final String traceId = "trace-id";
        ProxyContext chatBackend = mock(ProxyContext.class);
        when(chatBackend.getSpanId()).thenReturn("chat");
        when(chatBackend.getTraceId()).thenReturn(traceId);
        when(chatBackend.getApiKeyData()).thenReturn(new ApiKeyData());

        // chat calls app -> core starts span
        tracker.startSpan(chatBackend);

        assertEquals(1, tracker.getTraces().size());
        assertTrue(tracker.getTraces().containsKey(traceId));

        ProxyContext app = mock(ProxyContext.class);
        when(app.getSpanId()).thenReturn("app");
        when(app.getTraceId()).thenReturn(traceId);
        when(app.getParentSpanId()).thenReturn("chat");
        ApiKeyData apiKeyData = new ApiKeyData();
        apiKeyData.setPerRequestKey("key");
        when(app.getApiKeyData()).thenReturn(apiKeyData);

        // app calls model -> core starts span
        tracker.startSpan(app);

        assertEquals(1, tracker.getTraces().size());

        TokenUsage modelTokenUsage = new TokenUsage();
        modelTokenUsage.setTotalTokens(100);
        modelTokenUsage.setCompletionTokens(80);
        modelTokenUsage.setPromptTokens(20);
        modelTokenUsage.setCost(new BigDecimal("10.0"));
        modelTokenUsage.setAggCost(new BigDecimal("10.0"));

        // core receives response from model
        when(app.getTokenUsage()).thenReturn(modelTokenUsage);

        // core ends span for request to model
        tracker.endSpan(app);

        assertEquals(1, tracker.getTraces().size());

        // core receives response from app
        Future<TokenUsage> future = tracker.getTokenStats(chatBackend);
        assertNotNull(future);
        TokenUsage result = future.result();
        assertEquals(100, result.getTotalTokens());
        assertEquals(80, result.getCompletionTokens());
        assertEquals(20, result.getPromptTokens());
        assertEquals(new BigDecimal("10.0"), result.getAggCost());
        assertNull(result.getCost());

        // core ends span for request to app
        tracker.endSpan(chatBackend);
        assertEquals(0, tracker.getTraces().size());
    }
}
