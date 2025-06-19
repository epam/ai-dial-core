package com.epam.aidial.core.server.limiter;

import com.epam.aidial.core.config.Config;
import com.epam.aidial.core.config.CostLimit;
import com.epam.aidial.core.config.Limit;
import com.epam.aidial.core.config.Model;
import com.epam.aidial.core.config.ModelType;
import com.epam.aidial.core.config.Pricing;
import com.epam.aidial.core.config.Role;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.data.ApiKeyData;
import com.epam.aidial.core.server.data.LimitStats;
import com.epam.aidial.core.server.security.ExtractedClaims;
import com.epam.aidial.core.server.token.TokenUsage;
import com.epam.aidial.core.server.util.ModelCostCalculator;
import com.epam.aidial.core.storage.blobstore.BlobStorage;
import com.epam.aidial.core.storage.blobstore.Storage;
import com.epam.aidial.core.storage.http.HttpStatus;
import com.epam.aidial.core.storage.service.LockService;
import com.epam.aidial.core.storage.service.ResourceService;
import com.epam.aidial.core.storage.service.TimerService;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
public class CostRateLimitTest {

    private static RedisServer redisServer;
    private static RedissonClient redissonClient;

    @Mock
    private Vertx vertx;

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
                {
                  "provider" : "filesystem",
                  "bucket": "dial",
                  "createBucket": true,
                  "overrides": {
                    "jclouds.filesystem.basedir": "data"
                  }
                }
                """, Storage.class));
        LockService lockService = new LockService(redissonClient, null);
        ResourceService.Settings settings = new ResourceService.Settings(64 * 1048576, 1048576, 60000, 120000, 4096, 300000, 256);
        ResourceService resourceService = new ResourceService(mock(TimerService.class), redissonClient, blobStorage, lockService, settings, null);
        rateLimiter = new RateLimiter(vertx, resourceService);
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
        role1.setCostLimits(Map.of("default", costLimit1));

        // Role 2 with cost limits
        Role role2 = new Role();
        limit = new Limit();
        limit.setDay(20000);
        limit.setMinute(200);
        role2.setLimits(Map.of("model", limit));

        CostLimit costLimit2 = new CostLimit();
        costLimit2.setMinute(new BigDecimal("0.20")); // 20 cents per minute
        costLimit2.setDay(new BigDecimal("20.00")); // $20 per day
        role2.setCostLimits(Map.of("default", costLimit2));

        config.getRoles().put("role1", role1);
        config.getRoles().put("role2", role2);

        // Create ProxyContext with user and roles
        ApiKeyData apiKeyData = new ApiKeyData();
        apiKeyData.setPerRequestKey("per-request-key");
        apiKeyData.setExtractedClaims(new ExtractedClaims("sub", List.of("role1", "role2"), "user-hash", Map.of(), null, null));
        ProxyContext proxyContext = new ProxyContext(null, config, request, apiKeyData, null, "trace-id", "span-id");

        // Set up model with pricing
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
        when(vertx.executeBlocking(any(Callable.class), eq(false))).thenAnswer(invocation -> {
            Callable<?> callable = invocation.getArgument(0);
            return Future.succeededFuture(callable.call());
        });

        // Set up token usage
        TokenUsage tokenUsage = new TokenUsage();
        tokenUsage.setPromptTokens(50);
        tokenUsage.setCompletionTokens(25);
        tokenUsage.setTotalTokens(75);
        proxyContext.setTokenUsage(tokenUsage);

        // Mock ModelCostCalculator to return a cost
        try (MockedStatic<ModelCostCalculator> mockedCalculator = Mockito.mockStatic(ModelCostCalculator.class)) {
            // First call returns $0.05 (below the limit)
            mockedCalculator.when(() -> ModelCostCalculator.calculate(proxyContext))
                    .thenReturn(new BigDecimal("0.05"));

            // First increase and limit check should succeed
            Future<Void> increaseLimitFuture = rateLimiter.increase(proxyContext, model);
            assertNotNull(increaseLimitFuture);
            assertNull(increaseLimitFuture.cause());

            Future<RateLimitResult> checkLimitFuture = rateLimiter.limit(proxyContext, model);
            assertNotNull(checkLimitFuture);
            assertNotNull(checkLimitFuture.result());
            assertEquals(HttpStatus.OK, checkLimitFuture.result().status());

            // Second call returns $0.15 (above the limit)
            mockedCalculator.when(() -> ModelCostCalculator.calculate(proxyContext))
                    .thenReturn(new BigDecimal("0.15"));

            // Second increase and limit check should fail due to cost limit
            increaseLimitFuture = rateLimiter.increase(proxyContext, model);
            assertNotNull(increaseLimitFuture);
            assertNull(increaseLimitFuture.cause());

            checkLimitFuture = rateLimiter.limit(proxyContext, model);
            assertNotNull(checkLimitFuture);
            assertNotNull(checkLimitFuture.result());
            assertEquals(HttpStatus.TOO_MANY_REQUESTS, checkLimitFuture.result().status());

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
        role.setCostLimits(Map.of("default", costLimit));

        config.getRoles().put("role", role);

        // Create ProxyContext with user and role
        ApiKeyData apiKeyData = new ApiKeyData();
        apiKeyData.setPerRequestKey("per-request-key");
        apiKeyData.setExtractedClaims(new ExtractedClaims("sub", List.of("role"), "user-hash", Map.of(), null, null));
        ProxyContext proxyContext = new ProxyContext(null, config, request, apiKeyData, null, "trace-id", "span-id");

        // Set up model with pricing
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
        when(vertx.executeBlocking(any(Callable.class), eq(false))).thenAnswer(invocation -> {
            Callable<?> callable = invocation.getArgument(0);
            return Future.succeededFuture(callable.call());
        });

        // Set up token usage
        TokenUsage tokenUsage = new TokenUsage();
        tokenUsage.setPromptTokens(50);
        tokenUsage.setCompletionTokens(25);
        tokenUsage.setTotalTokens(75);
        proxyContext.setTokenUsage(tokenUsage);

        // Mock ModelCostCalculator to return a cost
        try (MockedStatic<ModelCostCalculator> mockedCalculator = Mockito.mockStatic(ModelCostCalculator.class)) {
            mockedCalculator.when(() -> ModelCostCalculator.calculate(proxyContext))
                    .thenReturn(new BigDecimal("0.05"));

            // Increase limit to record usage
            Future<Void> increaseLimitFuture = rateLimiter.increase(proxyContext, model);
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
}
