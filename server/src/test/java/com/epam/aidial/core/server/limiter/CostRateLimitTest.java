package com.epam.aidial.core.server.limiter;

import com.epam.aidial.core.config.Config;
import com.epam.aidial.core.config.CostLimit;
import com.epam.aidial.core.config.InterfaceType;
import com.epam.aidial.core.config.Limit;
import com.epam.aidial.core.config.Model;
import com.epam.aidial.core.config.ModelType;
import com.epam.aidial.core.config.Pricing;
import com.epam.aidial.core.config.Role;
import com.epam.aidial.core.server.Proxy;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.config.ConfigStore;
import com.epam.aidial.core.server.data.ApiKeyData;
import com.epam.aidial.core.server.data.LimitStats;
import com.epam.aidial.core.server.security.ExtractedClaims;
import com.epam.aidial.core.server.token.TokenUsage;
import com.epam.aidial.core.server.util.BucketBuilder;
import com.epam.aidial.core.server.util.ModelCostCalculator;
import com.epam.aidial.core.server.util.ProxyUtil;
import com.epam.aidial.core.server.vertx.AsyncTaskExecutor;
import com.epam.aidial.core.storage.blobstore.BlobStorage;
import com.epam.aidial.core.storage.blobstore.Storage;
import com.epam.aidial.core.storage.http.HttpStatus;
import com.epam.aidial.core.storage.service.LockService;
import com.epam.aidial.core.storage.service.ResourceService;
import com.epam.aidial.core.storage.service.TimerService;
import io.vertx.core.Future;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.json.Json;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.Redisson;
import org.redisson.api.RKeys;
import org.redisson.api.RedissonClient;
import org.redisson.config.ConfigSupport;
import redis.embedded.RedisServer;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
public class CostRateLimitTest {

    private static RedisServer redisServer;
    private static RedissonClient redissonClient;

    @Mock
    private AsyncTaskExecutor taskExecutor;

    @Mock
    private HttpServerRequest request;

    private RateLimiter rateLimiter;

    @BeforeAll
    public static void beforeAll() throws IOException {
        redisServer = RedisServer.newRedisServer()
                .port(16379)
                .bind("127.0.0.1")
                .setting("maxmemory 16M")
                .setting("maxmemory-policy volatile-lfu")
                .build();
        redisServer.start();

        ConfigSupport configSupport = new ConfigSupport();
        org.redisson.config.Config redisClientConfig = configSupport.fromJSON("""
                {
                  "singleServerConfig": {
                     "address": "redis://localhost:16379"
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

        BlobStorage blobStorage = new BlobStorage(Json.decodeValue("""
                {"provider" : "filesystem", "bucket": "dial", "createBucket": true, "overrides": {"jclouds.filesystem.basedir": "data"}}
                """, Storage.class));
        LockService lockService = new LockService(redissonClient, null);
        ResourceService.Settings settings = new ResourceService.Settings(64 * 1048576, 1048576, 60000, 120000, 4096, 300000, 256);
        ResourceService resourceService = new ResourceService(mock(TimerService.class), redissonClient, blobStorage, lockService, settings, null);
        rateLimiter = new RateLimiter(taskExecutor, resourceService);
    }

    private static Proxy mockProxy(Config config) {
        ConfigStore configStore = mock(ConfigStore.class);
        when(configStore.get()).thenReturn(config);
        Proxy proxy = mock(Proxy.class);
        when(proxy.getConfigStore()).thenReturn(configStore);
        return proxy;
    }

    @Test
    public void testCostLimit_User_LimitFound() {
        // Set up configuration with cost limits
        Config config = new Config();

        // Role 1 with cost limits
        Role role1 = new Role();
        Limit limit = new Limit();
        limit.setDay(10000);
        limit.setMinute(100);
        role1.setLimits(Map.of("model", limit));

        CostLimit costLimit1 = new CostLimit();
        costLimit1.setMinute(new BigDecimal("0.10")); // 10 cents per minute
        costLimit1.setDay(new BigDecimal("10.00")); // $10 per day
        role1.setCostLimit(costLimit1);

        // Role 2 with cost limits
        Role role2 = new Role();
        limit = new Limit();
        limit.setDay(20000);
        limit.setMinute(200);
        role2.setLimits(Map.of("model", limit));

        CostLimit costLimit2 = new CostLimit();
        costLimit2.setMinute(new BigDecimal("0.20")); // 20 cents per minute
        costLimit2.setDay(new BigDecimal("20.00")); // $20 per day
        role2.setCostLimit(costLimit2);

        config.getRoles().put("role1", role1);
        config.getRoles().put("role2", role2);

        // Create ProxyContext with user and roles
        ApiKeyData apiKeyData = new ApiKeyData();
        apiKeyData.setPerRequestKey("per-request-key");
        apiKeyData.setExtractedClaims(new ExtractedClaims("sub", List.of("role1", "role2"), "user-hash",
                ProxyUtil.MAPPER.createObjectNode(), null, null));
        ProxyContext proxyContext = new ProxyContext(mockProxy(config), request, apiKeyData, null, "trace-id", "span-id", "01");

        // Set up a model with pricing
        Model model = new Model();
        model.setName("model");
        model.setType(ModelType.CHAT);

        Pricing pricing = new Pricing();
        pricing.setUnit("token");
        pricing.setPrompt("0.001"); // $0.001 per prompt token
        pricing.setCompletion("0.002"); // $0.002 per completion token
        model.setPricing(pricing);

        proxyContext.setDeployment(model);

        // Mock vertx.executeBlocking
        when(taskExecutor.submit(any(Callable.class))).thenAnswer(invocation -> {
            Callable<?> callable = invocation.getArgument(0);
            return Future.succeededFuture(callable.call());
        });

        // Set up token usage
        TokenUsage tokenUsage = new TokenUsage();
        tokenUsage.setPromptTokens(50);
        tokenUsage.setCompletionTokens(25);
        tokenUsage.setTotalTokens(75);
        proxyContext.setTokenUsage(tokenUsage);

        String bucketLocation = BucketBuilder.buildInitiatorBucket(proxyContext);

        // Mock ModelCostCalculator to return a cost
        try (MockedStatic<ModelCostCalculator> mockedCalculator = Mockito.mockStatic(ModelCostCalculator.class)) {
            // The first call returns $0.05 (below the limit)
            mockedCalculator.when(() -> ModelCostCalculator.calculate(any(), any(), any(), any(), any(), any()))
                    .thenReturn(new BigDecimal("0.05"));

            // First increase and limit check should succeed
            Future<Void> increaseLimitFuture = rateLimiter.increase(model, bucketLocation, proxyContext.getTokenUsage(), null, null, InterfaceType.OPENAI_CHAT_COMPLETIONS, null);
            assertNotNull(increaseLimitFuture);
            assertNull(increaseLimitFuture.cause());

            Future<RateLimitResult> checkLimitFuture = rateLimiter.limit(proxyContext, model);
            assertNotNull(checkLimitFuture);
            assertNotNull(checkLimitFuture.result());
            assertEquals(HttpStatus.OK, checkLimitFuture.result().status());

            // The second call returns $0.15 (above the limit)
            mockedCalculator.when(() -> ModelCostCalculator.calculate(any(), any(), any(), any(), any(), any()))
                    .thenReturn(new BigDecimal("0.15"));

            // Second increase and limit check should fail due to cost limit
            increaseLimitFuture = rateLimiter.increase(model, bucketLocation, proxyContext.getTokenUsage(), null, null, InterfaceType.OPENAI_CHAT_COMPLETIONS, null);
            assertNotNull(increaseLimitFuture);
            assertNull(increaseLimitFuture.cause());

            checkLimitFuture = rateLimiter.limit(proxyContext, model);
            assertNotNull(checkLimitFuture);
            assertNotNull(checkLimitFuture.result());
            assertEquals(HttpStatus.TOO_MANY_REQUESTS, checkLimitFuture.result().status());
            assertEquals("Hit cost rate limit. Minute limit: $0.20 / $0.20. Day limit: $0.20 / $20.00."
                    + " Week limit: $0.20 / $9,223,372,036,854,775,807.00."
                    + " Month limit: $0.20 / $9,223,372,036,854,775,807.00.",  checkLimitFuture.result().errorMessage());

            // Check that the error message mentions cost limit
            String errorMessage = checkLimitFuture.result().displayErrorMessage();
            assertNotNull(errorMessage);
            assertTrue(errorMessage.contains("cost limit"));
        }
    }

    @Test
    public void testGetLimitStats_WithCostLimits() {
        // Set up configuration with cost limits
        Config config = new Config();

        // Role with cost limits
        Role role = new Role();
        Limit limit = new Limit();
        limit.setDay(10000);
        limit.setMinute(100);
        role.setLimits(Map.of("model", limit));

        CostLimit costLimit = new CostLimit();
        costLimit.setMinute(new BigDecimal("0.10")); // 10 cents per minute
        costLimit.setDay(new BigDecimal("10.00")); // $10 per day
        costLimit.setWeek(new BigDecimal("50.00")); // $50 per week
        costLimit.setMonth(new BigDecimal("200.00")); // $200 per month
        role.setCostLimit(costLimit);

        config.getRoles().put("role", role);

        // Create ProxyContext with user and role
        ApiKeyData apiKeyData = new ApiKeyData();
        apiKeyData.setPerRequestKey("per-request-key");
        apiKeyData.setExtractedClaims(new ExtractedClaims("sub", List.of("role"), "user-hash",
                ProxyUtil.MAPPER.createObjectNode(), null, null));
        ProxyContext proxyContext = new ProxyContext(mockProxy(config), request, apiKeyData, null, "trace-id", "span-id", "01");

        // Set up a model with pricing
        Model model = new Model();
        model.setName("model");
        model.setType(ModelType.CHAT);

        Pricing pricing = new Pricing();
        pricing.setUnit("token");
        pricing.setPrompt("0.001"); // $0.001 per prompt token
        pricing.setCompletion("0.002"); // $0.002 per completion token
        model.setPricing(pricing);

        proxyContext.setDeployment(model);

        // Mock vertx.executeBlocking
        when(taskExecutor.submit(any(Callable.class))).thenAnswer(invocation -> {
            Callable<?> callable = invocation.getArgument(0);
            return Future.succeededFuture(callable.call());
        });

        // Set up token usage
        TokenUsage tokenUsage = new TokenUsage();
        tokenUsage.setPromptTokens(50);
        tokenUsage.setCompletionTokens(25);
        tokenUsage.setTotalTokens(75);
        proxyContext.setTokenUsage(tokenUsage);

        String bucketLocation = BucketBuilder.buildInitiatorBucket(proxyContext);

        // Mock ModelCostCalculator to return a cost
        try (MockedStatic<ModelCostCalculator> mockedCalculator = Mockito.mockStatic(ModelCostCalculator.class)) {
            mockedCalculator.when(() -> ModelCostCalculator.calculate(any(), any(), any(), any(), any(), any()))
                    .thenReturn(new BigDecimal("0.05"));

            // Increase limit to record usage
            Future<Void> increaseLimitFuture = rateLimiter.increase(model, bucketLocation, proxyContext.getTokenUsage(), null, null, InterfaceType.OPENAI_CHAT_COMPLETIONS, null);
            assertNotNull(increaseLimitFuture);
            assertNull(increaseLimitFuture.cause());

            // Get limit stats
            Future<LimitStats> limitStatsFuture = rateLimiter.getLimitStats(model, proxyContext);
            assertNotNull(limitStatsFuture);
            LimitStats limitStats = limitStatsFuture.result();
            assertNotNull(limitStats);

            // Check token limit stats
            assertEquals(10000, limitStats.getDayTokenStats().getTotal());
            assertEquals(75, limitStats.getDayTokenStats().getUsed());
            assertEquals(100, limitStats.getMinuteTokenStats().getTotal());
            assertEquals(75, limitStats.getMinuteTokenStats().getUsed());

            // Check cost limit stats
            assertEquals(new BigDecimal("0.10"), limitStats.getMinuteCostStats().getTotal());
            assertEquals(new BigDecimal("0.05"), limitStats.getMinuteCostStats().getUsed());
            assertEquals(new BigDecimal("10.00"), limitStats.getDayCostStats().getTotal());
            assertEquals(new BigDecimal("0.05"), limitStats.getDayCostStats().getUsed());
            assertEquals(new BigDecimal("50.00"), limitStats.getWeekCostStats().getTotal());
            assertEquals(new BigDecimal("0.05"), limitStats.getWeekCostStats().getUsed());
            assertEquals(new BigDecimal("200.00"), limitStats.getMonthCostStats().getTotal());
            assertEquals(new BigDecimal("0.05"), limitStats.getMonthCostStats().getUsed());
        }
    }

    @Test
    public void testPerUserCostLimits() {
        // Set up configuration with cost limits
        Config config = new Config();

        // Role with cost limits
        Role role = new Role();
        Limit limit = new Limit();
        limit.setDay(10000);
        limit.setMinute(1000); // Set a high token limit to ensure we hit the cost limit first
        role.setLimits(Map.of("model", limit));

        CostLimit costLimit = new CostLimit();
        costLimit.setMinute(new BigDecimal("0.10")); // 10 cents per minute
        costLimit.setDay(new BigDecimal("10.00")); // $10 per day
        role.setCostLimit(costLimit);

        config.getRoles().put("role", role);

        // Create model with pricing
        Model model = new Model();
        model.setName("model");
        model.setType(ModelType.CHAT);

        Pricing pricing = new Pricing();
        pricing.setUnit("token");
        pricing.setPrompt("0.001"); // $0.001 per prompt token
        pricing.setCompletion("0.002"); // $0.002 per completion token
        model.setPricing(pricing);

        // Mock vertx.executeBlocking
        when(taskExecutor.submit(any(Callable.class))).thenAnswer(invocation -> {
            Callable<?> callable = invocation.getArgument(0);
            return Future.succeededFuture(callable.call());
        });

        // Create first user context
        ApiKeyData apiKeyData1 = new ApiKeyData();
        apiKeyData1.setPerRequestKey("per-request-key-1");
        apiKeyData1.setExtractedClaims(new ExtractedClaims("user1", List.of("role"), "user-hash-1",
                ProxyUtil.MAPPER.createObjectNode(), null, null));
        ProxyContext proxyContext1 = new ProxyContext(mockProxy(config), request, apiKeyData1, null, "trace-id-1", "span-id-1", "01");
        proxyContext1.setDeployment(model);

        // Create second user context
        ApiKeyData apiKeyData2 = new ApiKeyData();
        apiKeyData2.setPerRequestKey("per-request-key-2");
        apiKeyData2.setExtractedClaims(new ExtractedClaims("user2", List.of("role"), "user-hash-2",
                ProxyUtil.MAPPER.createObjectNode(), null, null));
        ProxyContext proxyContext2 = new ProxyContext(mockProxy(config), request, apiKeyData2, null, "trace-id-2", "span-id-2", "01");
        proxyContext2.setDeployment(model);

        // Set up token usage for both users
        TokenUsage tokenUsage1 = new TokenUsage();
        tokenUsage1.setPromptTokens(50);
        tokenUsage1.setCompletionTokens(25);
        tokenUsage1.setTotalTokens(75);
        proxyContext1.setTokenUsage(tokenUsage1);

        TokenUsage tokenUsage2 = new TokenUsage();
        tokenUsage2.setPromptTokens(50);
        tokenUsage2.setCompletionTokens(25);
        tokenUsage2.setTotalTokens(75);
        proxyContext2.setTokenUsage(tokenUsage2);

        String bucketLocation1 = BucketBuilder.buildInitiatorBucket(proxyContext1);
        String bucketLocation2 = BucketBuilder.buildInitiatorBucket(proxyContext2);

        // Mock ModelCostCalculator to return costs
        try (MockedStatic<ModelCostCalculator> mockedCalculator = Mockito.mockStatic(ModelCostCalculator.class)) {
            // The first user gets $0.05 cost
            mockedCalculator.when(() -> ModelCostCalculator.calculate(any(), same(tokenUsage1), any(), any(), any(), any()))
                    .thenReturn(new BigDecimal("0.05"));

            // The second user gets $0.08 cost
            mockedCalculator.when(() -> ModelCostCalculator.calculate(any(), same(tokenUsage2), any(), any(), any(), any()))
                    .thenReturn(new BigDecimal("0.08"));

            // First user increases limit
            Future<Void> increaseLimitFuture1 = rateLimiter.increase(model, bucketLocation1, tokenUsage1, null, null, InterfaceType.OPENAI_CHAT_COMPLETIONS, null);
            assertNotNull(increaseLimitFuture1);
            assertNull(increaseLimitFuture1.cause());

            // Second user increases limit
            Future<Void> increaseLimitFuture2 = rateLimiter.increase(model, bucketLocation2, tokenUsage2, null, null, InterfaceType.OPENAI_CHAT_COMPLETIONS, null);
            assertNotNull(increaseLimitFuture2);
            assertNull(increaseLimitFuture2.cause());

            // Check limits for first user - should be OK
            Future<RateLimitResult> checkLimitFuture1 = rateLimiter.limit(proxyContext1, model);
            assertNotNull(checkLimitFuture1);
            assertNotNull(checkLimitFuture1.result());
            assertEquals(HttpStatus.OK, checkLimitFuture1.result().status());

            // Check limits for second user - should be OK
            Future<RateLimitResult> checkLimitFuture2 = rateLimiter.limit(proxyContext2, model);
            assertNotNull(checkLimitFuture2);
            assertNotNull(checkLimitFuture2.result());
            assertEquals(HttpStatus.OK, checkLimitFuture2.result().status());

            // Get limit stats for the first user
            Future<LimitStats> limitStatsFuture1 = rateLimiter.getLimitStats(model, proxyContext1);
            assertNotNull(limitStatsFuture1);
            LimitStats limitStats1 = limitStatsFuture1.result();
            assertNotNull(limitStats1);

            // Get limit stats for the second user
            Future<LimitStats> limitStatsFuture2 = rateLimiter.getLimitStats(model, proxyContext2);
            assertNotNull(limitStatsFuture2);
            LimitStats limitStats2 = limitStatsFuture2.result();
            assertNotNull(limitStats2);

            // Check that each user has their own cost usage
            assertEquals(new BigDecimal("0.05"), limitStats1.getMinuteCostStats().getUsed());
            assertEquals(new BigDecimal("0.08"), limitStats2.getMinuteCostStats().getUsed());

            // Now make first user exceed their limit
            mockedCalculator.when(() -> ModelCostCalculator.calculate(any(), eq(tokenUsage1), any(), any(), any(), any()))
                    .thenReturn(new BigDecimal("0.06"));

            // First user increases limit again
            increaseLimitFuture1 = rateLimiter.increase(model, bucketLocation1, tokenUsage1, null, null, InterfaceType.OPENAI_CHAT_COMPLETIONS, null);
            assertNotNull(increaseLimitFuture1);
            assertNull(increaseLimitFuture1.cause());

            // Get updated limit stats for first user after second increase
            limitStatsFuture1 = rateLimiter.getLimitStats(model, proxyContext1);
            assertNotNull(limitStatsFuture1);
            limitStats1 = limitStatsFuture1.result();
            assertNotNull(limitStats1);

            // Print out the updated cost usage
            System.out.println("[DEBUG_LOG] First user's minute cost usage after second increase: " + limitStats1.getMinuteCostStats().getUsed());

            // Check limits for the first user - should now exceed
            checkLimitFuture1 = rateLimiter.limit(proxyContext1, model);
            assertNotNull(checkLimitFuture1);
            assertNotNull(checkLimitFuture1.result());

            // Print out the actual error message
            System.out.println("[DEBUG_LOG] First user's error message: " + checkLimitFuture1.result().displayErrorMessage());

            assertEquals(HttpStatus.TOO_MANY_REQUESTS, checkLimitFuture1.result().status());
            assertTrue(checkLimitFuture1.result().displayErrorMessage().contains("cost limit"));

            // The second user should still be OK
            checkLimitFuture2 = rateLimiter.limit(proxyContext2, model);
            assertNotNull(checkLimitFuture2);
            assertNotNull(checkLimitFuture2.result());
            assertEquals(HttpStatus.OK, checkLimitFuture2.result().status());
        }
    }
}
