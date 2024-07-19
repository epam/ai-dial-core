package com.epam.aidial.core.token;

import com.epam.aidial.core.Proxy;
import com.epam.aidial.core.ProxyContext;
import com.epam.aidial.core.config.ApiKeyData;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
public class DeploymentStatsTrackerTest {

    private Vertx vertx;

    @Mock
    private Proxy proxy;

    @InjectMocks
    private DeploymentCostStatsTracker tracker;

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


        ProxyContext app = mock(ProxyContext.class);
        when(app.getSpanId()).thenReturn("app");
        when(app.getTraceId()).thenReturn(traceId);
        when(app.getParentSpanId()).thenReturn("chat");
        ApiKeyData apiKeyData = new ApiKeyData();
        apiKeyData.setPerRequestKey("key");
        when(app.getApiKeyData()).thenReturn(apiKeyData);

        // app calls model -> core starts span
        tracker.startSpan(app);


        DeploymentCostStats deploymentCostStats = new DeploymentCostStats();
        TokenUsage modelTokenUsage = new TokenUsage();
        modelTokenUsage.setTotalTokens(100);
        modelTokenUsage.setCompletionTokens(80);
        modelTokenUsage.setPromptTokens(20);
        deploymentCostStats.setCost(new BigDecimal("10.0"));
        deploymentCostStats.setAggCost(new BigDecimal("10.0"));

        // core receives response from model
        when(app.getDeploymentCostStats()).thenReturn(deploymentCostStats);

        // core ends span for request to model
        tracker.endSpan(app);

        // core receives response from app
        Future<DeploymentCostStats> future = tracker.getDeploymentStats(chatBackend);
        assertNotNull(future);
        DeploymentCostStats result = future.result();
        assertEquals(100, result.getTokenUsage().getTotalTokens());
        assertEquals(80, result.getTokenUsage().getCompletionTokens());
        assertEquals(20, result.getTokenUsage().getPromptTokens());
        assertEquals(new BigDecimal("10.0"), result.getAggCost());
        assertNull(result.getCost());

        // core ends span for request to app
        tracker.endSpan(chatBackend);
    }
}
