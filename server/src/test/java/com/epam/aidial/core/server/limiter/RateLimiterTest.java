package com.epam.aidial.core.server.limiter;

import com.epam.aidial.core.config.Config;
import com.epam.aidial.core.config.CostLimit;
import com.epam.aidial.core.config.Key;
import com.epam.aidial.core.config.Limit;
import com.epam.aidial.core.config.Model;
import com.epam.aidial.core.config.Role;
import com.epam.aidial.core.server.Proxy;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.config.ConfigStore;
import com.epam.aidial.core.server.data.ApiKeyData;
import com.epam.aidial.core.server.data.DeploymentLimitStats;
import com.epam.aidial.core.server.data.LimitStats;
import com.epam.aidial.core.server.data.UserLimitStats;
import com.epam.aidial.core.server.security.ExtractedClaims;
import com.epam.aidial.core.server.token.TokenUsage;
import com.epam.aidial.core.server.util.BucketBuilder;
import com.epam.aidial.core.server.util.ProxyUtil;
import com.epam.aidial.core.server.vertx.AsyncTaskExecutor;
import com.epam.aidial.core.storage.blobstore.BlobStorage;
import com.epam.aidial.core.storage.http.HttpStatus;
import com.epam.aidial.core.storage.service.LockService;
import com.epam.aidial.core.storage.service.ResourceService;
import com.epam.aidial.core.storage.service.TimerService;
import io.vertx.core.Future;
import io.vertx.core.http.HttpServerRequest;
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
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
public class RateLimiterTest {

    private static RedisServer redisServer;

    private static RedissonClient redissonClient;

    @Mock
    private AsyncTaskExecutor taskExecutor;

    @Mock
    private BlobStorage blobStorage;

    @Mock
    private HttpServerRequest request;

    private RateLimiter rateLimiter;

    @BeforeAll
    public static void beforeAll() throws IOException {
        redisServer = RedisServer.newRedisServer()
                .port(16370)
                .bind("127.0.0.1")
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
        ProxyContext proxyContext = new ProxyContext(mockProxy(config), request, apiKeyData, null, "trace-id", "span-id", "01");
        Model model = new Model();
        model.setName("model");
        proxyContext.setDeployment(model);

        Future<RateLimitResult> result = rateLimiter.limit(proxyContext, model);

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
        ProxyContext proxyContext = new ProxyContext(mockProxy(config), request, apiKeyData, null, "trace-id", "span-id", "01");
        Model model = new Model();
        model.setName("model");
        proxyContext.setDeployment(model);

        when(taskExecutor.submit(any(Callable.class))).thenAnswer(invocation -> {
            Callable<?> callable = invocation.getArgument(0);
            return Future.succeededFuture(callable.call());
        });

        Future<RateLimitResult> result = rateLimiter.limit(proxyContext, model);

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
        apiKeyData.setPerRequestKey("per-request-key");
        ProxyContext proxyContext = new ProxyContext(mockProxy(config), request, apiKeyData, null, "trace-id", "span-id", "01");
        Model model = new Model();
        model.setName("model");
        proxyContext.setDeployment(model);

        when(taskExecutor.submit(any(Callable.class))).thenAnswer(invocation -> {
            Callable<?> callable = invocation.getArgument(0);
            return Future.succeededFuture(callable.call());
        });

        TokenUsage tokenUsage = new TokenUsage();
        tokenUsage.setTotalTokens(90);
        proxyContext.setTokenUsage(tokenUsage);

        Future<Void> increaseLimitFuture = rateLimiter.increase(model, BucketBuilder.buildInitiatorBucket(proxyContext), proxyContext.getTokenUsage(), null, null);
        assertNotNull(increaseLimitFuture);
        assertNull(increaseLimitFuture.cause());

        Future<RateLimitResult> checkLimitFuture = rateLimiter.limit(proxyContext, model);

        assertNotNull(checkLimitFuture);
        assertNotNull(checkLimitFuture.result());
        assertEquals(HttpStatus.OK, checkLimitFuture.result().status());

        increaseLimitFuture = rateLimiter.increase(model, BucketBuilder.buildInitiatorBucket(proxyContext), proxyContext.getTokenUsage(), null, null);
        assertNotNull(increaseLimitFuture);
        assertNull(increaseLimitFuture.cause());

        checkLimitFuture = rateLimiter.limit(proxyContext, model);

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
        limit.setWeek(1000000);
        limit.setMonth(10000000);
        role.setLimits(Map.of("model", limit));
        config.setRoles(Map.of("role", role));
        ApiKeyData apiKeyData = new ApiKeyData();
        apiKeyData.setOriginalKey(key);
        ProxyContext proxyContext = new ProxyContext(mockProxy(config), request, apiKeyData, null, "trace-id", "span-id", "01");
        Model model = new Model();
        model.setName("model");
        proxyContext.setDeployment(model);

        when(taskExecutor.submit(any(Callable.class))).thenAnswer(invocation -> {
            Callable<?> callable = invocation.getArgument(0);
            return Future.succeededFuture(callable.call());
        });

        TokenUsage tokenUsage = new TokenUsage();
        tokenUsage.setTotalTokens(90);
        proxyContext.setTokenUsage(tokenUsage);

        Future<RateLimitResult> resultFuture = rateLimiter.limit(proxyContext, model);
        assertNotNull(resultFuture);
        assertNotNull(resultFuture.result());
        assertEquals(HttpStatus.OK, resultFuture.result().status());

        Future<Void> increaseLimitFuture = rateLimiter.increase(model, BucketBuilder.buildInitiatorBucket(proxyContext), proxyContext.getTokenUsage(), null, null);
        assertNotNull(increaseLimitFuture);
        assertNull(increaseLimitFuture.cause());

        Future<LimitStats> limitStatsFuture = rateLimiter.getLimitStats(model, proxyContext);

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
        assertEquals(1000000, limitStats.getWeekTokenStats().getTotal());
        assertEquals(90, limitStats.getWeekTokenStats().getUsed());
        assertEquals(10000000, limitStats.getMonthTokenStats().getTotal());
        assertEquals(90, limitStats.getMonthTokenStats().getUsed());

        increaseLimitFuture = rateLimiter.increase(model, BucketBuilder.buildInitiatorBucket(proxyContext), proxyContext.getTokenUsage(), null, null);
        assertNotNull(increaseLimitFuture);
        assertNull(increaseLimitFuture.cause());

        limitStatsFuture = rateLimiter.getLimitStats(model, proxyContext);

        assertNotNull(limitStatsFuture);
        assertNotNull(limitStatsFuture.result());
        limitStats = limitStatsFuture.result();
        assertEquals(10000, limitStats.getDayTokenStats().getTotal());
        assertEquals(180, limitStats.getDayTokenStats().getUsed());
        assertEquals(100, limitStats.getMinuteTokenStats().getTotal());
        assertEquals(180, limitStats.getMinuteTokenStats().getUsed());
        assertEquals(1000000, limitStats.getWeekTokenStats().getTotal());
        assertEquals(180, limitStats.getWeekTokenStats().getUsed());
        assertEquals(10000000, limitStats.getMonthTokenStats().getTotal());
        assertEquals(180, limitStats.getMonthTokenStats().getUsed());

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
        apiKeyData.setPerRequestKey("per-request-key");
        apiKeyData.setExtractedClaims(new ExtractedClaims("sub", List.of("role1", "role2"), "user-hash",
                ProxyUtil.MAPPER.createObjectNode(), null, null));
        ProxyContext proxyContext = new ProxyContext(mockProxy(config), request, apiKeyData,
                null, "trace-id", "span-id", "01");
        Model model = new Model();
        model.setName("model");
        proxyContext.setDeployment(model);

        when(taskExecutor.submit(any(Callable.class))).thenAnswer(invocation -> {
            Callable<?> callable = invocation.getArgument(0);
            return Future.succeededFuture(callable.call());
        });

        TokenUsage tokenUsage = new TokenUsage();
        tokenUsage.setTotalTokens(150);
        proxyContext.setTokenUsage(tokenUsage);

        Future<Void> increaseLimitFuture = rateLimiter.increase(model, BucketBuilder.buildInitiatorBucket(proxyContext), proxyContext.getTokenUsage(), null, null);
        assertNotNull(increaseLimitFuture);
        assertNull(increaseLimitFuture.cause());

        Future<RateLimitResult> checkLimitFuture = rateLimiter.limit(proxyContext, model);

        assertNotNull(checkLimitFuture);
        assertNotNull(checkLimitFuture.result());
        assertEquals(HttpStatus.OK, checkLimitFuture.result().status());

        increaseLimitFuture = rateLimiter.increase(model, BucketBuilder.buildInitiatorBucket(proxyContext), proxyContext.getTokenUsage(), null, null);
        assertNotNull(increaseLimitFuture);
        assertNull(increaseLimitFuture.cause());

        checkLimitFuture = rateLimiter.limit(proxyContext, model);

        assertNotNull(checkLimitFuture);
        assertNotNull(checkLimitFuture.result());
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, checkLimitFuture.result().status());

    }

    @Test
    public void testLimit_User_DefaultLimit() {
        Config config = new Config();

        ApiKeyData apiKeyData = new ApiKeyData();
        ProxyContext proxyContext = new ProxyContext(mockProxy(config), request, apiKeyData,
                new ExtractedClaims("sub", List.of("role1"), "user-hash",
                        ProxyUtil.MAPPER.createObjectNode(), null, null),
                "trace-id", "span-id", "01");
        Model model = new Model();
        model.setName("model");
        proxyContext.setDeployment(model);

        when(taskExecutor.submit(any(Callable.class))).thenAnswer(invocation -> {
            Callable<?> callable = invocation.getArgument(0);
            return Future.succeededFuture(callable.call());
        });

        TokenUsage tokenUsage = new TokenUsage();
        tokenUsage.setTotalTokens(90);
        proxyContext.setTokenUsage(tokenUsage);

        Future<Void> increaseLimitFuture = rateLimiter.increase(model, BucketBuilder.buildInitiatorBucket(proxyContext), proxyContext.getTokenUsage(), null, null);
        assertNotNull(increaseLimitFuture);
        assertNull(increaseLimitFuture.cause());

        Future<RateLimitResult> checkLimitFuture = rateLimiter.limit(proxyContext, model);

        assertNotNull(checkLimitFuture);
        assertNotNull(checkLimitFuture.result());
        assertEquals(HttpStatus.OK, checkLimitFuture.result().status());

        increaseLimitFuture = rateLimiter.increase(model, BucketBuilder.buildInitiatorBucket(proxyContext), proxyContext.getTokenUsage(), null, null);
        assertNotNull(increaseLimitFuture);
        assertNull(increaseLimitFuture.cause());

        checkLimitFuture = rateLimiter.limit(proxyContext, model);

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
        ProxyContext proxyContext = new ProxyContext(mockProxy(config), request, apiKeyData,
                new ExtractedClaims("sub", List.of("role1", "role2"), "user-hash",
                        ProxyUtil.MAPPER.createObjectNode(), null, null),
                "trace-id", "span-id", "01");
        Model model = new Model();
        model.setName("model");
        proxyContext.setDeployment(model);

        when(taskExecutor.submit(any(Callable.class))).thenAnswer(invocation -> {
            Callable<?> callable = invocation.getArgument(0);
            return Future.succeededFuture(callable.call());
        });

        TokenUsage tokenUsage = new TokenUsage();
        tokenUsage.setTotalTokens(150);
        proxyContext.setTokenUsage(tokenUsage);

        Future<Void> increaseLimitFuture = rateLimiter.increase(model, BucketBuilder.buildInitiatorBucket(proxyContext), proxyContext.getTokenUsage(), null, null);
        assertNotNull(increaseLimitFuture);
        assertNull(increaseLimitFuture.cause());

        Future<RateLimitResult> checkLimitFuture = rateLimiter.limit(proxyContext, model);

        assertNotNull(checkLimitFuture);
        assertNotNull(checkLimitFuture.result());
        assertEquals(HttpStatus.OK, checkLimitFuture.result().status());

        increaseLimitFuture = rateLimiter.increase(model, BucketBuilder.buildInitiatorBucket(proxyContext), proxyContext.getTokenUsage(), null, null);
        assertNotNull(increaseLimitFuture);
        assertNull(increaseLimitFuture.cause());

        checkLimitFuture = rateLimiter.limit(proxyContext, model);

        assertNotNull(checkLimitFuture);
        assertNotNull(checkLimitFuture.result());
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, checkLimitFuture.result().status());

    }

    @Test
    public void testGetUserLimitStats_MultipleDeploymentsAndEmptyUsage() {
        Config config = new Config();
        Role role = new Role();
        Limit usedLimit = new Limit();
        usedLimit.setMinute(100);
        usedLimit.setDay(10000);
        usedLimit.setWeek(1000000);
        usedLimit.setMonth(10000000);
        usedLimit.setRequestHour(20);
        usedLimit.setRequestDay(300);
        Limit unusedLimit = new Limit();
        unusedLimit.setDay(500);
        role.setLimits(Map.of("used-model", usedLimit, "unused-model", unusedLimit));
        config.setRoles(Map.of("role", role));

        ProxyContext proxyContext = userContext(config, List.of("role"));
        Model usedModel = model("used-model");
        Model unusedModel = model("unused-model");
        stubInlineExecutor();

        TokenUsage tokenUsage = new TokenUsage();
        tokenUsage.setTotalTokens(90);
        assertNull(rateLimiter.increase(usedModel, BucketBuilder.buildInitiatorBucket(proxyContext), tokenUsage, null, null).cause());

        // deliberately passed out of order - the response must come back sorted by id
        UserLimitStats stats = rateLimiter.getUserLimitStats(proxyContext, List.of(usedModel, unusedModel)).result();

        assertNotNull(stats);
        assertEquals(List.of("unused-model", "used-model"), stats.getDeployments().stream().map(DeploymentLimitStats::getId).toList());

        DeploymentLimitStats used = stats.getDeployments().get(1);
        assertEquals(100, used.getMinuteTokenStats().getTotal());
        assertEquals(90, used.getMinuteTokenStats().getUsed());
        assertEquals(10000, used.getDayTokenStats().getTotal());
        assertEquals(90, used.getDayTokenStats().getUsed());
        assertEquals(1000000, used.getWeekTokenStats().getTotal());
        assertEquals(90, used.getWeekTokenStats().getUsed());
        assertEquals(10000000, used.getMonthTokenStats().getTotal());
        assertEquals(90, used.getMonthTokenStats().getUsed());
        assertEquals(20, used.getHourRequestStats().getTotal());
        assertEquals(300, used.getDayRequestStats().getTotal());

        // a deployment with a finite limit but no traffic is still reported, with used = 0
        DeploymentLimitStats unused = stats.getDeployments().get(0);
        assertEquals(500, unused.getDayTokenStats().getTotal());
        assertEquals(0, unused.getDayTokenStats().getUsed());
        assertEquals(0, unused.getMonthTokenStats().getUsed());
        assertEquals(0, unused.getHourRequestStats().getUsed());
    }

    @Test
    public void testGetUserLimitStats_DefaultRoleFallback() {
        Config config = new Config();
        Role defaultRole = new Role();
        Limit limit = new Limit();
        limit.setDay(777);
        defaultRole.setLimits(Map.of("model", limit));
        config.setRoles(Map.of("default", defaultRole));

        ProxyContext proxyContext = userContext(config, List.of("unrelated-role"));
        stubInlineExecutor();

        UserLimitStats stats = rateLimiter.getUserLimitStats(proxyContext, List.of(model("model"))).result();

        assertNotNull(stats);
        assertEquals(1, stats.getDeployments().size());
        assertEquals(777, stats.getDeployments().get(0).getDayTokenStats().getTotal());
    }

    @Test
    public void testGetUserLimitStats_CostIsReportedOnceAndMergedByMax() {
        Config config = new Config();
        Role cheapRole = new Role();
        cheapRole.setLimits(Map.of());
        cheapRole.setCostLimit(costLimit("100"));
        Role richRole = new Role();
        richRole.setLimits(Map.of());
        richRole.setCostLimit(costLimit("200"));
        config.setRoles(Map.of("cheap", cheapRole, "rich", richRole));

        ProxyContext proxyContext = userContext(config, List.of("cheap", "rich"));
        stubInlineExecutor();

        UserLimitStats stats = rateLimiter.getUserLimitStats(proxyContext, List.of(model("model"))).result();

        assertNotNull(stats);
        // the caller draws on one shared pool capped at the most permissive role - 200, never 300
        assertEquals(0, new BigDecimal("200").compareTo(stats.getDayCostStats().getTotal()));
        assertEquals(0, new BigDecimal("200").compareTo(stats.getMonthCostStats().getTotal()));
        assertEquals(0, BigDecimal.ZERO.compareTo(stats.getDayCostStats().getUsed()));
    }

    /**
     * Characterization test for known pre-existing behaviour, not an endorsement of it: unspecified
     * windows default to Long.MAX_VALUE, so merging two roles that each cap a different window by
     * Math.max leaves both windows uncapped. Granting the second role removes the first role's cap.
     * If the merge is ever fixed to distinguish "unset" from "unlimited", update this test.
     */
    @Test
    public void testGetUserLimitStats_PartialWindowsMergeToUnlimited() {
        Config config = new Config();
        Role dayRole = new Role();
        Limit dayOnly = new Limit();
        dayOnly.setDay(100);
        dayRole.setLimits(Map.of("model", dayOnly));
        Role weekRole = new Role();
        Limit weekOnly = new Limit();
        weekOnly.setWeek(500);
        weekRole.setLimits(Map.of("model", weekOnly));
        config.setRoles(Map.of("day-role", dayRole, "week-role", weekRole));

        ProxyContext proxyContext = userContext(config, List.of("day-role", "week-role"));
        stubInlineExecutor();

        UserLimitStats stats = rateLimiter.getUserLimitStats(proxyContext, List.of(model("model"))).result();

        assertNotNull(stats);
        DeploymentLimitStats deployment = stats.getDeployments().get(0);
        assertEquals(Long.MAX_VALUE, deployment.getDayTokenStats().getTotal());
        assertEquals(Long.MAX_VALUE, deployment.getWeekTokenStats().getTotal());
    }

    @Test
    public void testGetUserLimitStats_NoDeployments() {
        ProxyContext proxyContext = userContext(new Config(), List.of("role"));
        stubInlineExecutor();

        UserLimitStats stats = rateLimiter.getUserLimitStats(proxyContext, List.of()).result();

        assertNotNull(stats);
        assertEquals(List.of(), stats.getDeployments());
        assertNotNull(stats.getDayCostStats());
    }

    /**
     * The controller turns a null result into 503: without Redis there is no usage history to report,
     * which is different from a caller having no deployments.
     */
    @Test
    public void testGetUserLimitStats_LimitStorageUnavailable() {
        RateLimiter limiterWithoutStorage = new RateLimiter(taskExecutor, null);
        // returns before reading any config, so the context needs no config store
        ExtractedClaims claims = new ExtractedClaims("sub", List.of("role"), "user-hash",
                ProxyUtil.MAPPER.createObjectNode(), null, null);
        ProxyContext proxyContext = new ProxyContext(mock(Proxy.class), request, new ApiKeyData(),
                claims, "trace-id", "span-id", "01");

        Future<UserLimitStats> future = limiterWithoutStorage.getUserLimitStats(
                proxyContext, List.of(model("model")));

        assertNotNull(future);
        assertNull(future.cause());
        assertNull(future.result());
    }

    /**
     * Neither a JWT subject nor an api-key project, so there is no bucket to read counters from. The
     * controller maps this {@link IllegalArgumentException} to 401.
     */
    @Test
    public void testGetUserLimitStats_UnresolvableInitiator() {
        Key key = new Key();
        key.setRole("role");
        key.setKey("key");
        // deliberately no project
        ApiKeyData apiKeyData = new ApiKeyData();
        apiKeyData.setOriginalKey(key);
        // fails while resolving the bucket, before any config lookup
        ProxyContext proxyContext = new ProxyContext(mock(Proxy.class), request, apiKeyData,
                null, "trace-id", "span-id", "01");
        stubInlineExecutor();

        Future<UserLimitStats> future = rateLimiter.getUserLimitStats(proxyContext, List.of(model("model")));

        assertNotNull(future);
        assertInstanceOf(IllegalArgumentException.class, future.cause());
    }

    private ProxyContext userContext(Config config, List<String> roles) {
        ExtractedClaims claims = new ExtractedClaims("sub", roles, "user-hash",
                ProxyUtil.MAPPER.createObjectNode(), null, null);
        return new ProxyContext(mockProxy(config), request, new ApiKeyData(), claims, "trace-id", "span-id", "01");
    }

    private static Model model(String name) {
        Model model = new Model();
        model.setName(name);
        return model;
    }

    private static CostLimit costLimit(String perWindow) {
        CostLimit costLimit = new CostLimit();
        costLimit.setMinute(new BigDecimal(perWindow));
        costLimit.setDay(new BigDecimal(perWindow));
        costLimit.setWeek(new BigDecimal(perWindow));
        costLimit.setMonth(new BigDecimal(perWindow));
        return costLimit;
    }

    @SuppressWarnings("unchecked")
    private void stubInlineExecutor() {
        when(taskExecutor.submit(any(Callable.class))).thenAnswer(invocation -> {
            Callable<?> callable = invocation.getArgument(0);
            return Future.succeededFuture(callable.call());
        });
    }

}
