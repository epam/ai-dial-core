package com.epam.aidial.core.limiter;

import com.epam.aidial.core.ProxyContext;
import com.epam.aidial.core.config.ApiKeyData;
import com.epam.aidial.core.config.Config;
import com.epam.aidial.core.config.Key;
import com.epam.aidial.core.config.Limit;
import com.epam.aidial.core.config.Model;
import com.epam.aidial.core.config.Role;
import com.epam.aidial.core.data.LimitStats;
import com.epam.aidial.core.security.ExtractedClaims;
import com.epam.aidial.core.service.LockService;
import com.epam.aidial.core.service.ResourceService;
import com.epam.aidial.core.storage.BlobStorage;
import com.epam.aidial.core.token.TokenUsage;
import com.epam.aidial.core.util.HttpStatus;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.Redisson;
import org.redisson.api.RKeys;
import org.redisson.api.RedissonClient;
import org.redisson.config.ConfigSupport;
import redis.embedded.RedisServer;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
public class RateLimiterTest {

    private static RedisServer redisServer;

    private static RedissonClient redissonClient;

    @Mock
    private Vertx vertx;

    @Mock
    private BlobStorage blobStorage;

    @Mock
    private HttpServerRequest request;

    private RateLimiter rateLimiter;

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
        String resourceConfig = """
                  {
                    "maxSize" : 1048576,
                    "syncPeriod": 60000,
                    "syncDelay": 120000,
                    "syncBatch": 4096,
                    "cacheExpiration": 300000,
                    "compressionMinSize": 256
                  }
                """;
        ResourceService resourceService = new ResourceService(vertx, redissonClient, blobStorage, lockService, new JsonObject(resourceConfig), null);
        rateLimiter = new RateLimiter(vertx, resourceService);
    }

    @Test
    public void testLimit_ApiKeyLimitNotFound() {
        Key key = new Key();
        key.setRole("role");
        ApiKeyData apiKeyData = new ApiKeyData();
        apiKeyData.setOriginalKey(key);
        ProxyContext proxyContext = new ProxyContext(new Config(), request, apiKeyData, null, "trace-id", "span-id");
        proxyContext.setDeployment(new Model());

        Future<RateLimitResult> result = rateLimiter.limit(proxyContext);

        assertNotNull(result);
        assertNotNull(result.result());
        assertEquals(HttpStatus.FORBIDDEN, result.result().status());
    }


    @Test
    public void testLimit_ApiKeyLimitNotFoundWithNullRole() {
        Key key = new Key();
        key.setKey("key");
        key.setProject("project");
        ApiKeyData apiKeyData = new ApiKeyData();
        apiKeyData.setOriginalKey(key);
        ProxyContext proxyContext = new ProxyContext(new Config(), request, apiKeyData, null, "trace-id", "span-id");
        proxyContext.setDeployment(new Model());

        Future<RateLimitResult> result = rateLimiter.limit(proxyContext);

        assertNotNull(result);
        assertNotNull(result.result());
        assertEquals(HttpStatus.FORBIDDEN, result.result().status());

    }

    @Test
    public void testLimit_ApiKeyLimitNegative() {
        Key key = new Key();
        key.setRole("role");
        key.setKey("key");
        Config config = new Config();
        Role role = new Role();
        Limit limit = new Limit();
        limit.setDay(-1);
        role.setLimits(Map.of("model", limit));
        config.setRoles(Map.of("role", role));
        ApiKeyData apiKeyData = new ApiKeyData();
        apiKeyData.setOriginalKey(key);
        ProxyContext proxyContext = new ProxyContext(config, request, apiKeyData, null, "trace-id", "span-id");
        Model model = new Model();
        model.setName("model");
        proxyContext.setDeployment(model);

        Future<RateLimitResult> result = rateLimiter.limit(proxyContext);

        assertNotNull(result);
        assertNotNull(result.result());
        assertEquals(HttpStatus.FORBIDDEN, result.result().status());

    }

    @Test
    public void testLimit_ApiKeySuccess_KeyNotFound() {
        Key key = new Key();
        key.setRole("role");
        key.setKey("key");
        key.setProject("api-key");
        Config config = new Config();
        Role role = new Role();
        Limit limit = new Limit();
        role.setLimits(Map.of("model", limit));
        config.setRoles(Map.of("role", role));
        ApiKeyData apiKeyData = new ApiKeyData();
        apiKeyData.setOriginalKey(key);
        ProxyContext proxyContext = new ProxyContext(config, request, apiKeyData, null, "trace-id", "span-id");
        Model model = new Model();
        model.setName("model");
        proxyContext.setDeployment(model);

        when(vertx.executeBlocking(any(Callable.class), eq(false))).thenAnswer(invocation -> {
            Callable<?> callable = invocation.getArgument(0);
            return Future.succeededFuture(callable.call());
        });

        Future<RateLimitResult> result = rateLimiter.limit(proxyContext);

        assertNotNull(result);
        assertNotNull(result.result());
        assertEquals(HttpStatus.OK, result.result().status());
    }

    @Test
    public void testLimit_ApiKeySuccess_KeyExist() {
        Key key = new Key();
        key.setRole("role");
        key.setKey("key");
        key.setProject("api-key");
        Config config = new Config();
        Role role = new Role();
        Limit limit = new Limit();
        limit.setDay(10000);
        limit.setMinute(100);
        role.setLimits(Map.of("model", limit));
        config.setRoles(Map.of("role", role));
        ApiKeyData apiKeyData = new ApiKeyData();
        apiKeyData.setOriginalKey(key);
        ProxyContext proxyContext = new ProxyContext(config, request, apiKeyData, null, "trace-id", "span-id");
        Model model = new Model();
        model.setName("model");
        proxyContext.setDeployment(model);

        when(vertx.executeBlocking(any(Callable.class), eq(false))).thenAnswer(invocation -> {
            Callable<?> callable = invocation.getArgument(0);
            return Future.succeededFuture(callable.call());
        });

        TokenUsage tokenUsage = new TokenUsage();
        tokenUsage.setTotalTokens(90);
        proxyContext.setTokenUsage(tokenUsage);

        Future<Void> increaseLimitFuture = rateLimiter.increase(proxyContext);
        assertNotNull(increaseLimitFuture);
        assertNull(increaseLimitFuture.cause());

        Future<RateLimitResult> checkLimitFuture = rateLimiter.limit(proxyContext);

        assertNotNull(checkLimitFuture);
        assertNotNull(checkLimitFuture.result());
        assertEquals(HttpStatus.OK, checkLimitFuture.result().status());

        increaseLimitFuture = rateLimiter.increase(proxyContext);
        assertNotNull(increaseLimitFuture);
        assertNull(increaseLimitFuture.cause());

        checkLimitFuture = rateLimiter.limit(proxyContext);

        assertNotNull(checkLimitFuture);
        assertNotNull(checkLimitFuture.result());
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, checkLimitFuture.result().status());

    }

    @Test
    public void testGetLimitStats_ApiKey() {
        Key key = new Key();
        key.setRole("role");
        key.setKey("key");
        key.setProject("api-key");
        Config config = new Config();
        Role role = new Role();
        Limit limit = new Limit();
        limit.setDay(10000);
        limit.setMinute(100);
        limit.setRequestDay(10);
        limit.setRequestHour(2);
        role.setLimits(Map.of("model", limit));
        config.setRoles(Map.of("role", role));
        ApiKeyData apiKeyData = new ApiKeyData();
        apiKeyData.setOriginalKey(key);
        ProxyContext proxyContext = new ProxyContext(config, request, apiKeyData, null, "trace-id", "span-id");
        Model model = new Model();
        model.setName("model");
        proxyContext.setDeployment(model);

        when(vertx.executeBlocking(any(Callable.class), eq(false))).thenAnswer(invocation -> {
            Callable<?> callable = invocation.getArgument(0);
            return Future.succeededFuture(callable.call());
        });

        TokenUsage tokenUsage = new TokenUsage();
        tokenUsage.setTotalTokens(90);
        proxyContext.setTokenUsage(tokenUsage);

        Future<RateLimitResult> resultFuture = rateLimiter.limit(proxyContext);
        assertNotNull(resultFuture);
        assertNotNull(resultFuture.result());
        assertEquals(HttpStatus.OK, resultFuture.result().status());

        Future<Void> increaseLimitFuture = rateLimiter.increase(proxyContext);
        assertNotNull(increaseLimitFuture);
        assertNull(increaseLimitFuture.cause());

        Future<LimitStats> limitStatsFuture = rateLimiter.getLimitStats(model.getName(), proxyContext);

        assertNotNull(limitStatsFuture);
        assertNotNull(limitStatsFuture.result());
        LimitStats limitStats = limitStatsFuture.result();
        assertEquals(10000, limitStats.getDayTokenStats().getTotal());
        assertEquals(90, limitStats.getDayTokenStats().getUsed());
        assertEquals(100, limitStats.getMinuteTokenStats().getTotal());
        assertEquals(90, limitStats.getMinuteTokenStats().getUsed());
        assertEquals(10, limitStats.getDayRequestStats().getTotal());
        assertEquals(1, limitStats.getDayRequestStats().getUsed());
        assertEquals(2, limitStats.getHourRequestStats().getTotal());
        assertEquals(1, limitStats.getHourRequestStats().getUsed());

        increaseLimitFuture = rateLimiter.increase(proxyContext);
        assertNotNull(increaseLimitFuture);
        assertNull(increaseLimitFuture.cause());

        limitStatsFuture = rateLimiter.getLimitStats(model.getName(), proxyContext);

        assertNotNull(limitStatsFuture);
        assertNotNull(limitStatsFuture.result());
        limitStats = limitStatsFuture.result();
        assertEquals(10000, limitStats.getDayTokenStats().getTotal());
        assertEquals(180, limitStats.getDayTokenStats().getUsed());
        assertEquals(100, limitStats.getMinuteTokenStats().getTotal());
        assertEquals(180, limitStats.getMinuteTokenStats().getUsed());

    }

    @Test
    public void testLimit_User_LimitFound() {
        Config config = new Config();

        Role role1 = new Role();
        Limit limit = new Limit();
        limit.setDay(10000);
        limit.setMinute(100);
        role1.setLimits(Map.of("model", limit));

        Role role2 = new Role();
        limit = new Limit();
        limit.setDay(20000);
        limit.setMinute(200);
        role2.setLimits(Map.of("model", limit));

        config.getRoles().put("role1", role1);
        config.getRoles().put("role2", role2);

        ApiKeyData apiKeyData = new ApiKeyData();
        ProxyContext proxyContext = new ProxyContext(config, request, apiKeyData,
                new ExtractedClaims("sub", List.of("role1", "role2"), "user-hash", Map.of()), "trace-id", "span-id");
        Model model = new Model();
        model.setName("model");
        proxyContext.setDeployment(model);

        when(vertx.executeBlocking(any(Callable.class), eq(false))).thenAnswer(invocation -> {
            Callable<?> callable = invocation.getArgument(0);
            return Future.succeededFuture(callable.call());
        });

        TokenUsage tokenUsage = new TokenUsage();
        tokenUsage.setTotalTokens(150);
        proxyContext.setTokenUsage(tokenUsage);

        Future<Void> increaseLimitFuture = rateLimiter.increase(proxyContext);
        assertNotNull(increaseLimitFuture);
        assertNull(increaseLimitFuture.cause());

        Future<RateLimitResult> checkLimitFuture = rateLimiter.limit(proxyContext);

        assertNotNull(checkLimitFuture);
        assertNotNull(checkLimitFuture.result());
        assertEquals(HttpStatus.OK, checkLimitFuture.result().status());

        increaseLimitFuture = rateLimiter.increase(proxyContext);
        assertNotNull(increaseLimitFuture);
        assertNull(increaseLimitFuture.cause());

        checkLimitFuture = rateLimiter.limit(proxyContext);

        assertNotNull(checkLimitFuture);
        assertNotNull(checkLimitFuture.result());
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, checkLimitFuture.result().status());

    }

    @Test
    public void testLimit_User_DefaultLimit() {
        Config config = new Config();

        ApiKeyData apiKeyData = new ApiKeyData();
        ProxyContext proxyContext = new ProxyContext(config, request, apiKeyData,
                new ExtractedClaims("sub", List.of("role1"), "user-hash", Map.of()), "trace-id", "span-id");
        Model model = new Model();
        model.setName("model");
        proxyContext.setDeployment(model);

        when(vertx.executeBlocking(any(Callable.class), eq(false))).thenAnswer(invocation -> {
            Callable<?> callable = invocation.getArgument(0);
            return Future.succeededFuture(callable.call());
        });

        TokenUsage tokenUsage = new TokenUsage();
        tokenUsage.setTotalTokens(90);
        proxyContext.setTokenUsage(tokenUsage);

        Future<Void> increaseLimitFuture = rateLimiter.increase(proxyContext);
        assertNotNull(increaseLimitFuture);
        assertNull(increaseLimitFuture.cause());

        Future<RateLimitResult> checkLimitFuture = rateLimiter.limit(proxyContext);

        assertNotNull(checkLimitFuture);
        assertNotNull(checkLimitFuture.result());
        assertEquals(HttpStatus.OK, checkLimitFuture.result().status());

        increaseLimitFuture = rateLimiter.increase(proxyContext);
        assertNotNull(increaseLimitFuture);
        assertNull(increaseLimitFuture.cause());

        checkLimitFuture = rateLimiter.limit(proxyContext);

        assertNotNull(checkLimitFuture);
        assertNotNull(checkLimitFuture.result());
        assertEquals(HttpStatus.OK, checkLimitFuture.result().status());
    }

    @Test
    public void testLimit_User_RequestLimit() {
        Config config = new Config();

        Role role1 = new Role();
        Limit limit = new Limit();
        limit.setRequestDay(10);
        limit.setRequestHour(1);
        role1.setLimits(Map.of("model", limit));

        Role role2 = new Role();
        limit = new Limit();
        limit.setRequestDay(20);
        limit.setRequestHour(1);
        role2.setLimits(Map.of("model", limit));

        config.getRoles().put("role1", role1);
        config.getRoles().put("role2", role2);

        ApiKeyData apiKeyData = new ApiKeyData();
        ProxyContext proxyContext = new ProxyContext(config, request, apiKeyData,
                new ExtractedClaims("sub", List.of("role1", "role2"), "user-hash", Map.of()), "trace-id", "span-id");
        Model model = new Model();
        model.setName("model");
        proxyContext.setDeployment(model);

        when(vertx.executeBlocking(any(Callable.class), eq(false))).thenAnswer(invocation -> {
            Callable<?> callable = invocation.getArgument(0);
            return Future.succeededFuture(callable.call());
        });

        TokenUsage tokenUsage = new TokenUsage();
        tokenUsage.setTotalTokens(150);
        proxyContext.setTokenUsage(tokenUsage);

        Future<Void> increaseLimitFuture = rateLimiter.increase(proxyContext);
        assertNotNull(increaseLimitFuture);
        assertNull(increaseLimitFuture.cause());

        Future<RateLimitResult> checkLimitFuture = rateLimiter.limit(proxyContext);

        assertNotNull(checkLimitFuture);
        assertNotNull(checkLimitFuture.result());
        assertEquals(HttpStatus.OK, checkLimitFuture.result().status());

        increaseLimitFuture = rateLimiter.increase(proxyContext);
        assertNotNull(increaseLimitFuture);
        assertNull(increaseLimitFuture.cause());

        checkLimitFuture = rateLimiter.limit(proxyContext);

        assertNotNull(checkLimitFuture);
        assertNotNull(checkLimitFuture.result());
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, checkLimitFuture.result().status());

    }

}
