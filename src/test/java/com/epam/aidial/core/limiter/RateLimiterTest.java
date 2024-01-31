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
import com.epam.aidial.core.util.HttpStatus;
import com.epam.aidial.core.util.ProxyUtil;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServerRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;

import java.util.Collections;
import java.util.Map;
import java.util.concurrent.Callable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
public class RateLimiterTest {

    @Mock
    private Vertx vertx;

    @Mock
    private RedissonClient redis;

    @Mock
    private HttpServerRequest request;

    @InjectMocks
    private RateLimiter rateLimiter;

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

        RBucket<Object> bucket = mock(RBucket.class);
        when(redis.getBucket(eq("rate_limit:token.api.key.key.model"), eq(StringCodec.INSTANCE))).thenReturn(bucket);

        Future<RateLimitResult> result = rateLimiter.limit(proxyContext);

        assertNotNull(result);
        assertNotNull(result.result());
        assertEquals(HttpStatus.OK, result.result().status());
    }

    @Test
    public void testLimit_ApiKeySuccess_KeyExist() throws Exception {
        Key key = new Key();
        key.setRole("role");
        key.setKey("key");
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

        RBucket<Object> bucket = mock(RBucket.class);
        when(redis.getBucket(eq("rate_limit:token.api.key.key.model"), eq(StringCodec.INSTANCE))).thenReturn(bucket);
        RateLimit rateLimit = new RateLimit();
        rateLimit.add(System.currentTimeMillis(), 10);
        String json = ProxyUtil.MAPPER.writeValueAsString(rateLimit);
        when(bucket.get()).thenReturn(json);
        when(bucket.compareAndSet(any(String.class), any(String.class))).thenReturn(true);

        Future<RateLimitResult> result = rateLimiter.limit(proxyContext);

        assertNotNull(result);
        assertNotNull(result.result());
        assertEquals(HttpStatus.OK, result.result().status());

    }

}
