package com.epam.aidial.core.server.token;

import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.vertx.AsyncTaskExecutor;
import com.epam.aidial.core.storage.blobstore.BlobStorage;
import com.epam.aidial.core.storage.service.LockService;
import com.epam.aidial.core.storage.service.ResourceService;
import com.epam.aidial.core.storage.service.TimerService;
import io.vertx.core.Future;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.Redisson;
import org.redisson.api.RKeys;
import org.redisson.api.RedissonClient;
import org.redisson.config.ConfigSupport;
import redis.embedded.RedisServer;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.Callable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
public class TokenStatsTrackerTest {

    private static RedisServer redisServer;

    private static RedissonClient redissonClient;

    @Mock
    private AsyncTaskExecutor taskExecutor;

    @Mock
    private BlobStorage blobStorage;

    @InjectMocks
    private TokenStatsTracker tracker;

    @BeforeAll
    public static void beforeAll() throws IOException {
        redisServer = RedisServer.newRedisServer()
                .port(16370)
                .setting("bind 127.0.0.1")
                .setting("maxmemory 16M")
                .setting("maxmemory-policy volatile-lfu")
                .build();
        redisServer.start();
        ConfigSupport configSupport = new ConfigSupport();
        org.redisson.config.Config redisClientConfig = configSupport.fromJSON("""
                {
                  "singleServerConfig": {
                     "address": "redis://localhost:16370"
                  }
                }
                """, org.redisson.config.Config.class);

        redissonClient = Redisson.create(redisClientConfig);
    }

    @AfterAll
    public static void afterAll() throws IOException {
        if (redissonClient != null) {
            redissonClient.shutdown();
        }
        if (redisServer != null) {
            redisServer.stop();
        }
    }

    @BeforeEach
    public void beforeEach() {
        RKeys keys = redissonClient.getKeys();
        for (String key : keys.getKeys()) {
            keys.delete(key);
        }
        LockService lockService = new LockService(redissonClient, null);
        ResourceService.Settings settings = new ResourceService.Settings(64 * 1048576, 1048576, 60000, 120000, 4096, 300000, 256);
        ResourceService resourceService = new ResourceService(mock(TimerService.class), redissonClient, blobStorage,
                lockService, settings, null);
        tracker = new TokenStatsTracker(taskExecutor, resourceService);
    }

    /**
     * Tests the flow: chat back-end -> core -> app -> core -> model
     */
    @Test
    public void testWorkflow() {
        when(taskExecutor.submit(any(Callable.class))).thenAnswer(invocation -> {
            Callable<?> callable = invocation.getArgument(0);
            return Future.succeededFuture(callable.call());
        });

        final String traceId = "trace-id";
        ProxyContext chatBackend = mock(ProxyContext.class);
        when(chatBackend.getSpanId()).thenReturn("chat");
        when(chatBackend.getTraceId()).thenReturn(traceId);
        when(chatBackend.isOriginalRequest()).thenReturn(true);

        // chat calls app -> core starts span
        tracker.startSpan(chatBackend);


        ProxyContext app = mock(ProxyContext.class);
        when(app.getSpanId()).thenReturn("app");
        when(app.getTraceId()).thenReturn(traceId);
        when(app.getParentSpanId()).thenReturn("chat");

        // app calls model -> core starts span
        tracker.startSpan(app);

        TokenUsage modelTokenUsage = new TokenUsage();
        modelTokenUsage.setTotalTokens(100);
        modelTokenUsage.setCompletionTokens(80);
        modelTokenUsage.setPromptTokens(20);
        modelTokenUsage.setCost(new BigDecimal("10.0"));
        modelTokenUsage.setAggCost(new BigDecimal("10.0"));

        // core receives response from model
        TokenStatsTracker.UsageStats modelStats = tracker.updateDeploymentStats(traceId, "app", "gpt-4", modelTokenUsage).result();
        assertNotNull(modelStats);
        assertEquals(100, modelStats.total().getTotalTokens());
        assertEquals(1, modelStats.usagePerModel().size());
        assertEquals(0, modelStats.usagePerModel().get(0).getIndex());
        assertEquals("gpt-4", modelStats.usagePerModel().get(0).getModel());

        // the app also self-reports its own usage in its response body. The old assign-based
        // updateStats would have wiped the model's contribution to the app's aggregate here
        // instead of adding to it - this is the regression case for that bug.
        TokenUsage appOwnUsage = new TokenUsage();
        appOwnUsage.setTotalTokens(5);
        appOwnUsage.setPromptTokens(5);
        tracker.updateDeploymentStats(traceId, "app", "my-app", appOwnUsage);

        // core ends span for request to model
        tracker.endSpan(app);

        // core receives response from app: aggregate must be model + app's own (105), not just 5
        Future<TokenUsage> appStatsFuture = tracker.getTokenStats(chatBackend);
        assertNotNull(appStatsFuture);
        TokenUsage tokenUsage = appStatsFuture.result();
        assertEquals(105, tokenUsage.getTotalTokens());
        assertEquals(80, tokenUsage.getCompletionTokens());
        assertEquals(25, tokenUsage.getPromptTokens());
        assertEquals(new BigDecimal("10.0"), tokenUsage.getAggCost());
        assertNull(tokenUsage.getCost());

        // the breakdown at the chat span shows both contributors, additively (no suppression)
        TokenStatsTracker.UsageStats chatStats = tracker.getUsageStats(chatBackend).result();
        assertEquals(2, chatStats.usagePerModel().size());
        assertEquals("gpt-4", chatStats.usagePerModel().get(0).getModel());
        assertEquals(100, chatStats.usagePerModel().get(0).getUsage().getTotalTokens());
        assertEquals("my-app", chatStats.usagePerModel().get(1).getModel());
        assertEquals(5, chatStats.usagePerModel().get(1).getUsage().getTotalTokens());

        // core ends span for request to app
        tracker.endSpan(chatBackend);
        assertNull(tracker.getTokenStats(chatBackend).result());
    }

    @Test
    public void testSameDeploymentCalledTwiceMergesIntoOneEntry() {
        when(taskExecutor.submit(any(Callable.class))).thenAnswer(invocation -> {
            Callable<?> callable = invocation.getArgument(0);
            return Future.succeededFuture(callable.call());
        });

        final String traceId = "trace-id-repeat";
        ProxyContext root = mock(ProxyContext.class);
        when(root.getSpanId()).thenReturn("root");
        when(root.getTraceId()).thenReturn(traceId);
        tracker.startSpan(root);

        TokenUsage firstCall = new TokenUsage();
        firstCall.setTotalTokens(10);
        firstCall.setPromptTokens(10);
        tracker.updateDeploymentStats(traceId, "root", "gpt-4", firstCall);

        TokenUsage secondCall = new TokenUsage();
        secondCall.setTotalTokens(20);
        secondCall.setCompletionTokens(20);
        TokenStatsTracker.UsageStats stats = tracker.updateDeploymentStats(traceId, "root", "gpt-4", secondCall).result();

        assertEquals(1, stats.usagePerModel().size());
        UsagePerModel entry = stats.usagePerModel().get(0);
        assertEquals("gpt-4", entry.getModel());
        assertEquals(0, entry.getIndex());
        assertEquals(30, entry.getUsage().getTotalTokens());
        assertEquals(10, entry.getUsage().getPromptTokens());
        assertEquals(20, entry.getUsage().getCompletionTokens());
        assertEquals(30, stats.total().getTotalTokens());
    }

    @Test
    public void testMissingTraceReturnsEmptyUsageStats() {
        when(taskExecutor.submit(any(Callable.class))).thenAnswer(invocation -> {
            Callable<?> callable = invocation.getArgument(0);
            return Future.succeededFuture(callable.call());
        });

        ProxyContext ctx = mock(ProxyContext.class);
        when(ctx.getTraceId()).thenReturn("nonexistent-trace");

        TokenStatsTracker.UsageStats stats = tracker.getUsageStats(ctx).result();
        assertNotNull(stats);
        assertNull(stats.total());
        assertEquals(List.of(), stats.usagePerModel());
    }

}
