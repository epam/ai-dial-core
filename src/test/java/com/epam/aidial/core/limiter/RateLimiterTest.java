package com.epam.aidial.core.limiter;

import com.epam.aidial.core.ProxyContext;
import com.epam.aidial.core.config.ApiKeyData;
import com.epam.aidial.core.config.Application;
import com.epam.aidial.core.config.Config;
import com.epam.aidial.core.config.Key;
import com.epam.aidial.core.config.Limit;
import com.epam.aidial.core.config.Model;
import com.epam.aidial.core.config.Role;
import com.epam.aidial.core.security.ExtractedClaims;
import com.epam.aidial.core.service.LockService;
import com.epam.aidial.core.service.ResourceService;
import com.epam.aidial.core.storage.BlobStorage;
import com.epam.aidial.core.token.TokenUsage;
import com.epam.aidial.core.util.HttpStatus;
import com.epam.aidial.core.util.ProxyUtil;
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
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.Callable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
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
        redisServer = RedisServer.builder()
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
    public static void afterAll() {
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
        LockService lockService = new LockService(redissonClient);
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
    public void testLimit_EntityNotFound() {
        ApiKeyData apiKeyData = new ApiKeyData();
        apiKeyData.setOriginalKey(new Key());
        ProxyContext proxyContext = new ProxyContext(new Config(), request, apiKeyData, null, "unknown-trace-id", "span-id");
        proxyContext.setDeployment(new Application());

        Future<RateLimitResult> result = rateLimiter.limit(proxyContext);

        assertNotNull(result);
        assertNotNull(result.result());
        assertEquals(HttpStatus.FORBIDDEN, result.result().status());
    }

    @Test
    public void testLimit_SuccessUser() {
        ProxyContext proxyContext = new ProxyContext(new Config(), request, new ApiKeyData(), new ExtractedClaims("sub", Collections.emptyList(), "hash"), "trace-id", "span-id");

        Future<RateLimitResult> result = rateLimiter.limit(proxyContext);

        assertNotNull(result);
        assertNotNull(result.result());
        assertEquals(HttpStatus.OK, result.result().status());
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

        when(vertx.executeBlocking(any(Callable.class))).thenAnswer(invocation -> {
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

        when(vertx.executeBlocking(any(Callable.class))).thenAnswer(invocation -> {
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

}
