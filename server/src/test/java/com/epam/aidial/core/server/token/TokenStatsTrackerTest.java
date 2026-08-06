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

        ProxyContext model = mock(ProxyContext.class);
        when(model.getSpanId()).thenReturn("model");
        when(model.getTraceId()).thenReturn(traceId);
        when(model.getParentSpanId()).thenReturn("app");

        // core starts span for the model's own hop
        tracker.startSpan(model);

        TokenUsage modelTokenUsage = new TokenUsage();
        modelTokenUsage.setTotalTokens(100);
        modelTokenUsage.setCompletionTokens(80);
        modelTokenUsage.setPromptTokens(20);
        modelTokenUsage.setCost(new BigDecimal("10.0"));
        modelTokenUsage.setAggCost(new BigDecimal("10.0"));

        // core receives response from model
        TokenStatsTracker.UsageStats modelStats = tracker.updateDeploymentStats(traceId, "model", "gpt-4", modelTokenUsage).result();
        assertNotNull(modelStats);
        assertEquals(100, modelStats.total().getTotalTokens());
        // the model never lists itself in its own breakdown - that's already its own tokenUsage
        assertEquals(0, modelStats.usagePerModel().size());

        // the model's direct ancestor (app) is the one that picks up the entry
        TokenStatsTracker.UsageStats appStatsAfterModel = tracker.getUsageStats(app).result();
        assertEquals(1, appStatsAfterModel.usagePerModel().size());
        assertEquals(0, appStatsAfterModel.usagePerModel().get(0).getIndex());
        assertEquals("gpt-4", appStatsAfterModel.usagePerModel().get(0).getModel());
        assertEquals(100, appStatsAfterModel.usagePerModel().get(0).getTotalTokens());

        // the app also self-reports its own usage in its response body. Its own counters are
        // *assigned* (5, not 100 + 5) but aggCost must keep the model's earlier contribution -
        // a plain assign there would silently erase it.
        TokenUsage appOwnUsage = new TokenUsage();
        appOwnUsage.setTotalTokens(5);
        appOwnUsage.setPromptTokens(5);
        TokenStatsTracker.UsageStats appStats = tracker.updateDeploymentStats(traceId, "app", "my-app", appOwnUsage).result();
        assertEquals(5, appStats.total().getTotalTokens());
        assertEquals(5, appStats.total().getPromptTokens());
        assertEquals(0, appStats.total().getCompletionTokens());
        assertEquals(new BigDecimal("10.0"), appStats.total().getAggCost());
        // the app's own view of its breakdown still only shows the model - never itself
        assertEquals(1, appStats.usagePerModel().size());
        assertEquals("gpt-4", appStats.usagePerModel().get(0).getModel());

        // core ends span for request to model
        tracker.endSpan(model);

        // core receives response from chat: chat never self-reports, so its own counters stay
        // at zero - only aggCost and usagePerModel roll up from descendants.
        Future<TokenUsage> appStatsFuture = tracker.getTokenStats(chatBackend);
        assertNotNull(appStatsFuture);
        TokenUsage tokenUsage = appStatsFuture.result();
        assertEquals(0, tokenUsage.getTotalTokens());
        assertEquals(0, tokenUsage.getCompletionTokens());
        assertEquals(0, tokenUsage.getPromptTokens());
        assertEquals(new BigDecimal("10.0"), tokenUsage.getAggCost());
        assertNull(tokenUsage.getCost());

        // the breakdown at the chat span shows both contributors, additively (no suppression) -
        // chat is an ancestor of both the model and the app, so it picks up both entries
        TokenStatsTracker.UsageStats chatStats = tracker.getUsageStats(chatBackend).result();
        assertEquals(2, chatStats.usagePerModel().size());
        assertEquals("gpt-4", chatStats.usagePerModel().get(0).getModel());
        assertEquals(100, chatStats.usagePerModel().get(0).getTotalTokens());
        assertEquals("my-app", chatStats.usagePerModel().get(1).getModel());
        assertEquals(5, chatStats.usagePerModel().get(1).getTotalTokens());

        // core ends span for request to app
        tracker.endSpan(chatBackend);
        assertNull(tracker.getTokenStats(chatBackend).result());
    }

    @Test
    public void testSameDeploymentCalledTwiceAppendsTwoSeparateEntries() {
        when(taskExecutor.submit(any(Callable.class))).thenAnswer(invocation -> {
            Callable<?> callable = invocation.getArgument(0);
            return Future.succeededFuture(callable.call());
        });

        final String traceId = "trace-id-repeat";
        ProxyContext root = mock(ProxyContext.class);
        when(root.getSpanId()).thenReturn("root");
        when(root.getTraceId()).thenReturn(traceId);
        tracker.startSpan(root);

        // two different branches under the same root both call gpt-4
        ProxyContext branchA = mock(ProxyContext.class);
        when(branchA.getSpanId()).thenReturn("branch-a");
        when(branchA.getTraceId()).thenReturn(traceId);
        when(branchA.getParentSpanId()).thenReturn("root");
        tracker.startSpan(branchA);

        ProxyContext branchB = mock(ProxyContext.class);
        when(branchB.getSpanId()).thenReturn("branch-b");
        when(branchB.getTraceId()).thenReturn(traceId);
        when(branchB.getParentSpanId()).thenReturn("root");
        tracker.startSpan(branchB);

        TokenUsage firstCall = new TokenUsage();
        firstCall.setTotalTokens(10);
        firstCall.setPromptTokens(10);
        firstCall.setAggCost(new BigDecimal("1.0"));
        tracker.updateDeploymentStats(traceId, "branch-a", "gpt-4", firstCall);

        TokenUsage secondCall = new TokenUsage();
        secondCall.setTotalTokens(20);
        secondCall.setCompletionTokens(20);
        secondCall.setAggCost(new BigDecimal("2.0"));
        tracker.updateDeploymentStats(traceId, "branch-b", "gpt-4", secondCall);

        TokenStatsTracker.UsageStats stats = tracker.getUsageStats(root).result();

        // no merge-by-name: each report from "gpt-4" is its own entry, not summed together
        assertEquals(2, stats.usagePerModel().size());
        UsagePerModel first = stats.usagePerModel().get(0);
        assertEquals("gpt-4", first.getModel());
        assertEquals(0, first.getIndex());
        assertEquals(10, first.getTotalTokens());
        assertEquals(10, first.getPromptTokens());
        assertEquals(0, first.getCompletionTokens());

        UsagePerModel second = stats.usagePerModel().get(1);
        assertEquals("gpt-4", second.getModel());
        assertEquals(1, second.getIndex());
        assertEquals(20, second.getTotalTokens());
        assertEquals(0, second.getPromptTokens());
        assertEquals(20, second.getCompletionTokens());

        // root is a pure ancestor of both branches - it never self-reports, so its own
        // counters stay at zero; only aggCost accumulates from both branches.
        assertEquals(0, stats.total().getTotalTokens());
        assertEquals(new BigDecimal("3.0"), stats.total().getAggCost());
    }

    @Test
    public void testSoloModelCallHasEmptyUsagePerModel() {
        when(taskExecutor.submit(any(Callable.class))).thenAnswer(invocation -> {
            Callable<?> callable = invocation.getArgument(0);
            return Future.succeededFuture(callable.call());
        });

        final String traceId = "trace-id-solo";
        ProxyContext root = mock(ProxyContext.class);
        when(root.getSpanId()).thenReturn("root");
        when(root.getTraceId()).thenReturn(traceId);
        tracker.startSpan(root);

        TokenUsage usage = new TokenUsage();
        usage.setTotalTokens(42);
        usage.setPromptTokens(10);
        usage.setCompletionTokens(32);
        usage.setCost(new BigDecimal("1.0"));
        usage.setAggCost(new BigDecimal("1.0"));

        // a client calling a Model directly (no App in between) has no ancestors, so nothing
        // ever merges it into a usagePerModel breakdown - only the top-level tokenUsage carries it
        TokenStatsTracker.UsageStats stats = tracker.updateDeploymentStats(traceId, "root", "gpt-4", usage).result();

        assertEquals(42, stats.total().getTotalTokens());
        assertEquals(List.of(), stats.usagePerModel());
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
