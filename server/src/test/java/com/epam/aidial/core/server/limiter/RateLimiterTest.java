package com.epam.aidial.core.server.limiter;

import com.epam.aidial.core.config.Application;
import com.epam.aidial.core.config.Config;
import com.epam.aidial.core.config.CostLimit;
import com.epam.aidial.core.config.Key;
import com.epam.aidial.core.config.Limit;
import com.epam.aidial.core.config.Model;
import com.epam.aidial.core.config.Pricing;
import com.epam.aidial.core.config.Role;
import com.epam.aidial.core.server.FileUtil;
import com.epam.aidial.core.server.Proxy;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.config.ConfigStore;
import com.epam.aidial.core.server.data.ApiKeyData;
import com.epam.aidial.core.server.data.LimitStats;
import com.epam.aidial.core.server.data.UserLimitStats;
import com.epam.aidial.core.server.security.ExtractedClaims;
import com.epam.aidial.core.server.token.TokenUsage;
import com.epam.aidial.core.server.util.BucketBuilder;
import com.epam.aidial.core.server.util.ProxyUtil;
import com.epam.aidial.core.server.util.ResourceDescriptorFactory;
import com.epam.aidial.core.server.vertx.AsyncTaskExecutor;
import com.epam.aidial.core.storage.blobstore.BlobStorage;
import com.epam.aidial.core.storage.blobstore.Storage;
import com.epam.aidial.core.storage.data.MetadataBase;
import com.epam.aidial.core.storage.data.ResourceFolderMetadata;
import com.epam.aidial.core.storage.data.ResourceItemMetadata;
import com.epam.aidial.core.storage.http.HttpStatus;
import com.epam.aidial.core.storage.resource.ResourceDescriptor;
import com.epam.aidial.core.storage.resource.ResourceTypes;
import com.epam.aidial.core.storage.service.LockService;
import com.epam.aidial.core.storage.service.ResourceService;
import com.epam.aidial.core.storage.service.TimerService;
import com.epam.aidial.core.storage.util.EtagHeader;
import io.vertx.core.Future;
import io.vertx.core.http.HttpServerRequest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.Redisson;
import org.redisson.api.RKeys;
import org.redisson.api.RedissonClient;
import org.redisson.config.ConfigSupport;
import redis.embedded.RedisServer;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
public class RateLimiterTest {

    private static RedisServer redisServer;

    private static RedissonClient redissonClient;

    @Mock
    private AsyncTaskExecutor taskExecutor;

    @Mock
    private HttpServerRequest request;

    private BlobStorage blobStorage;

    private Path testDir;

    private ResourceService resourceService;

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

    /**
     * A real filesystem blob store rather than a mock: reading usage now starts by listing the caller's
     * limit records, and a mocked store lists nothing, so the discovery step would never be exercised.
     */
    @BeforeEach
    public void beforeEach() throws IOException {
        RKeys keys = redissonClient.getKeys();
        for (String key : keys.getKeys()) {
            keys.delete(key);
        }

        testDir = FileUtil.baseTestPath(RateLimiterTest.class);
        FileUtil.deleteDir(testDir);
        FileUtil.createDir(testDir.resolve("test"));
        // the path must be JSON-encoded, otherwise a Windows path breaks parsing on its backslashes
        String storageConfig = """
                {
                    "bucket": "test",
                    "provider": "filesystem",
                    "identity": "access-key",
                    "credential": "secret-key",
                    "prefix": "test-2",
                    "overrides": {
                      "jclouds.filesystem.basedir": %s
                    }
                  }
                """.formatted(ProxyUtil.MAPPER.writeValueAsString(testDir.toString()));
        blobStorage = new BlobStorage(ProxyUtil.MAPPER.readValue(storageConfig, Storage.class));

        LockService lockService = new LockService(redissonClient, null);
        ResourceService.Settings settings = new ResourceService.Settings(64 * 1048576, 1048576, 60000, 120000, 4096, 300000, 256);
        resourceService = new ResourceService(mock(TimerService.class), redissonClient, blobStorage,
                lockService, settings, null);
        rateLimiter = new RateLimiter(taskExecutor, resourceService);
    }

    @AfterEach
    public void afterEach() {
        if (blobStorage != null) {
            blobStorage.close();
        }
        FileUtil.deleteDir(testDir);
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

        UserLimitStats stats = rateLimiter.getUserStats(proxyContext, List.of(usedModel, unusedModel), false).result();

        assertNotNull(stats);
        // reported in the order the deployments were passed in
        assertEquals(List.of("used-model", "unused-model"), List.copyOf(stats.getDeployments().keySet()));

        LimitStats used = stats.getDeployments().get("used-model");
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
        // an entry carries cost too, with no cap of its own - only the global budget bounds it
        assertEquals(Long.MAX_VALUE, used.getDayCostStats().getTotal().longValue());

        // a deployment with a finite limit but no traffic is still reported, with used = 0
        LimitStats unused = stats.getDeployments().get("unused-model");
        assertEquals(500, unused.getDayTokenStats().getTotal());
        assertEquals(0, unused.getDayTokenStats().getUsed());
        assertEquals(0, unused.getMonthTokenStats().getUsed());
        assertEquals(0, unused.getHourRequestStats().getUsed());

        // the same call restricted to what was actually used drops the untouched deployment
        UserLimitStats usage = rateLimiter.getUserStats(proxyContext, List.of(usedModel, unusedModel), true).result();
        assertNotNull(usage);
        assertEquals(List.of("used-model"), List.copyOf(usage.getDeployments().keySet()));
    }

    /**
     * Cost is written twice: the global document enforces, the deployment-scoped one attributes. Both must
     * carry the same figure, since the second is a copy of what the hot path already computed.
     */
    @Test
    public void testGetUserStats_CostIsAttributedPerDeployment() {
        Config config = new Config();
        Role role = new Role();
        role.setLimits(Map.of());
        role.setCostLimit(costLimit("100"));
        config.setRoles(Map.of("role", role));

        ProxyContext proxyContext = userContext(config, List.of("role"));
        Model model = model("priced-model");
        Pricing pricing = new Pricing();
        pricing.setUnit("token");
        pricing.setPrompt("0.001");
        pricing.setCompletion("0.002");
        model.setPricing(pricing);
        stubInlineExecutor();

        TokenUsage tokenUsage = new TokenUsage();
        tokenUsage.setPromptTokens(1000);
        tokenUsage.setCompletionTokens(2000);
        tokenUsage.setTotalTokens(3000);
        assertNull(rateLimiter.increase(model, BucketBuilder.buildInitiatorBucket(proxyContext), tokenUsage, null, null).cause());

        UserLimitStats stats = rateLimiter.getUserStats(proxyContext, List.of(model), false).result();

        assertNotNull(stats);
        BigDecimal expected = new BigDecimal("5.000");
        // the caller's budget and the spend against it
        assertEquals(0, expected.compareTo(stats.getDayCostStats().getUsed()));
        assertEquals(0, new BigDecimal("100").compareTo(stats.getDayCostStats().getTotal()));
        // the same spend, attributed to the deployment that incurred it
        LimitStats deployment = stats.getDeployments().get("priced-model");
        assertEquals(0, expected.compareTo(deployment.getDayCostStats().getUsed()));
        assertEquals(0, expected.compareTo(deployment.getMonthCostStats().getUsed()));
    }

    /**
     * Discovery lists the caller's whole folder, so it can turn up a record for a deployment that is not in
     * the response. The key set comes from config, so such a name is never reported and never trusted.
     */
    @Test
    public void testGetUserStats_IgnoresRecordsOutsideTheKeySet() {
        Config config = new Config();
        Role role = new Role();
        role.setLimits(Map.of());
        config.setRoles(Map.of("role", role));

        ProxyContext proxyContext = userContext(config, List.of("role"));
        Model reported = model("reported-model");
        Model revoked = model("revoked-model");
        stubInlineExecutor();

        TokenUsage tokenUsage = new TokenUsage();
        tokenUsage.setTotalTokens(70);
        String bucket = BucketBuilder.buildInitiatorBucket(proxyContext);
        assertNull(rateLimiter.increase(reported, bucket, tokenUsage, null, null).cause());
        assertNull(rateLimiter.increase(revoked, bucket, tokenUsage, null, null).cause());

        // only the accessible model is passed in, as the controller would after the access check
        UserLimitStats stats = rateLimiter.getUserStats(proxyContext, List.of(reported), false).result();

        assertNotNull(stats);
        assertEquals(List.of("reported-model"), List.copyOf(stats.getDeployments().keySet()));
        assertEquals(70, stats.getDeployments().get("reported-model").getMinuteTokenStats().getUsed());
    }

    /**
     * A deployment named "costs" writes to "costs/costs", a sibling of the caller's global "costs" document,
     * so the two cannot be confused for one another.
     */
    @Test
    public void testGetUserStats_DeploymentNamedCostsDoesNotShadowTheGlobalDocument() {
        Config config = new Config();
        Role role = new Role();
        role.setLimits(Map.of());
        role.setCostLimit(costLimit("50"));
        config.setRoles(Map.of("role", role));

        ProxyContext proxyContext = userContext(config, List.of("role"));
        Model model = model("costs");
        stubInlineExecutor();

        TokenUsage tokenUsage = new TokenUsage();
        tokenUsage.setTotalTokens(11);
        assertNull(rateLimiter.increase(model, BucketBuilder.buildInitiatorBucket(proxyContext), tokenUsage, null, null).cause());

        UserLimitStats stats = rateLimiter.getUserStats(proxyContext, List.of(model), false).result();

        assertNotNull(stats);
        assertEquals(11, stats.getDeployments().get("costs").getMinuteTokenStats().getUsed());
        // no cost was priced, so the global budget stands untouched at its configured value
        assertEquals(0, new BigDecimal("50").compareTo(stats.getDayCostStats().getTotal()));
        assertEquals(0, BigDecimal.ZERO.compareTo(stats.getDayCostStats().getUsed()));
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

        UserLimitStats stats = rateLimiter.getUserStats(proxyContext, List.of(model("model")), false).result();

        assertNotNull(stats);
        assertEquals(1, stats.getDeployments().size());
        assertEquals(777, stats.getDeployments().get("model").getDayTokenStats().getTotal());
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

        UserLimitStats stats = rateLimiter.getUserStats(proxyContext, List.of(model("model")), false).result();

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

        UserLimitStats stats = rateLimiter.getUserStats(proxyContext, List.of(model("model")), false).result();

        assertNotNull(stats);
        LimitStats deployment = stats.getDeployments().get("model");
        assertEquals(Long.MAX_VALUE, deployment.getDayTokenStats().getTotal());
        assertEquals(Long.MAX_VALUE, deployment.getWeekTokenStats().getTotal());
    }

    @Test
    public void testGetUserLimitStats_NoDeployments() {
        ProxyContext proxyContext = userContext(new Config(), List.of("role"));
        stubInlineExecutor();

        UserLimitStats stats = rateLimiter.getUserStats(proxyContext, List.of(), false).result();

        assertNotNull(stats);
        assertEquals(Map.of(), stats.getDeployments());
        assertNotNull(stats.getDayCostStats());
    }

    /**
     * Neither a JWT subject nor an api-key project, so there is no bucket to read counters from. The
     * controller maps this {@link IllegalArgumentException} to 500.
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

        Future<UserLimitStats> future = rateLimiter.getUserStats(proxyContext, List.of(model("model")), false);

        assertNotNull(future);
        assertInstanceOf(IllegalArgumentException.class, future.cause());
    }

    /**
     * A counter document that no longer parses is a corrupted record, not a reportable state: the read must
     * fail with the {@link IllegalArgumentException} {@code ProxyUtil.convertToObject} raises rather than
     * quietly report zero usage for that window. The controller answers 500.
     */
    @Test
    public void testGetUserStats_FailsOnUnreadableCounterRecord() {
        Config config = new Config();
        Role role = new Role();
        role.setLimits(Map.of());
        config.setRoles(Map.of("role", role));

        ProxyContext proxyContext = userContext(config, List.of("role"));
        Model model = model("corrupt-model");
        stubInlineExecutor();

        TokenUsage tokenUsage = new TokenUsage();
        tokenUsage.setTotalTokens(42);
        String bucket = BucketBuilder.buildInitiatorBucket(proxyContext);
        // limit() writes the request counter, increase() the token one
        assertEquals(HttpStatus.OK, rateLimiter.limit(proxyContext, model).result().status());
        assertNull(rateLimiter.increase(model, bucket, tokenUsage, null, null).cause());

        // corrupt the token counter in place, leaving the request counter intact
        ResourceDescriptor tokens = ResourceDescriptorFactory
                .fromEncoded(ResourceTypes.LIMIT, bucket, bucket, "corrupt-model/tokens");
        resourceService.putResource(tokens, "{\"minute\":\"not-a-bucket\"}", EtagHeader.ANY);

        Future<UserLimitStats> future = rateLimiter.getUserStats(proxyContext, List.of(model), false);

        assertInstanceOf(IllegalArgumentException.class, future.cause());
    }

    /**
     * A record's age comes from the listing of its blob object, so one last written before the widest
     * window opened is never read. Only the token record is aged here: it drops out of the report while
     * the request record, listed as current, still reports its usage.
     */
    @Test
    public void testGetUserStats_SkipsRecordsOlderThanWidestWindow() {
        Config config = new Config();
        Role role = new Role();
        role.setLimits(Map.of());
        config.setRoles(Map.of("role", role));

        ProxyContext proxyContext = userContext(config, List.of("role"));
        Model model = model("aged-model");
        stubInlineExecutor();

        TokenUsage tokenUsage = new TokenUsage();
        tokenUsage.setTotalTokens(42);
        String bucket = BucketBuilder.buildInitiatorBucket(proxyContext);
        assertEquals(HttpStatus.OK, rateLimiter.limit(proxyContext, model).result().status());
        assertNull(rateLimiter.increase(model, bucket, tokenUsage, null, null).cause());

        // age the token record's listing entry past RateWindow.MONTH, leaving its counter untouched
        ResourceDescriptor tokens = ResourceDescriptorFactory
                .fromEncoded(ResourceTypes.LIMIT, bucket, bucket, "aged-model/tokens");
        long agedOut = System.currentTimeMillis() - Duration.ofDays(60).toMillis();
        ResourceService listingWithAgedRecord = Mockito.spy(resourceService);
        Mockito.doAnswer(invocation -> {
            ResourceFolderMetadata page = (ResourceFolderMetadata) invocation.callRealMethod();
            for (MetadataBase item : page.getItems()) {
                if (item instanceof ResourceItemMetadata metadata
                        && tokens.getAbsoluteFilePath().equals(metadata.getDescriptor().getAbsoluteFilePath())) {
                    metadata.setUpdatedAt(agedOut);
                }
            }
            return page;
        }).when(listingWithAgedRecord).getFolderMetadata(any(), any(), anyInt(), anyBoolean());
        RateLimiter limiter = new RateLimiter(taskExecutor, listingWithAgedRecord);

        Future<UserLimitStats> future = limiter.getUserStats(proxyContext, List.of(model), false);

        assertNull(future.cause(), String.valueOf(future.cause()));
        LimitStats stats = future.result().getDeployments().get("aged-model");
        assertNotNull(stats);
        assertEquals(0, stats.getMinuteTokenStats().getUsed());
        assertEquals(1, stats.getHourRequestStats().getUsed());
    }

    /**
     * A deployment name is plain configuration text and has to satisfy nothing but the config, so brackets are
     * legal - and a URI, which forbids them in a path, is the wrong thing to validate the record path as.
     * Covers admission, accounting and both reporting endpoints, all of which failed for such a name.
     */
    @Test
    public void testLimitsForDeploymentNameThatIsNotUriSafe() {
        Config config = new Config();
        Role role = new Role();
        role.setLimits(Map.of());
        role.setCostLimit(costLimit("100"));
        config.setRoles(Map.of("role", role));

        ProxyContext proxyContext = userContext(config, List.of("role"));
        Model model = model("anthropic.claude-opus-4-8[1m]");
        Pricing pricing = new Pricing();
        pricing.setUnit("token");
        pricing.setPrompt("0.001");
        pricing.setCompletion("0.002");
        model.setPricing(pricing);
        stubInlineExecutor();

        TokenUsage tokenUsage = new TokenUsage();
        tokenUsage.setPromptTokens(1000);
        tokenUsage.setCompletionTokens(2000);
        tokenUsage.setTotalTokens(3000);
        String bucket = BucketBuilder.buildInitiatorBucket(proxyContext);
        assertEquals(HttpStatus.OK, rateLimiter.limit(proxyContext, model).result().status());
        assertNull(rateLimiter.increase(model, bucket, tokenUsage, null, null).cause());

        // the record lands under the name exactly as configured
        assertNotNull(resourceService.getResource(ResourceDescriptorFactory
                .fromDecoded(ResourceTypes.LIMIT, bucket, bucket, "anthropic.claude-opus-4-8[1m]/tokens")));

        // dropEmpty=true is what GET /v1/user/usage asks for, the endpoint the incident was reported on
        Future<UserLimitStats> future = rateLimiter.getUserStats(proxyContext, List.of(model), true);
        assertNull(future.cause(), String.valueOf(future.cause()));
        LimitStats stats = future.result().getDeployments().get("anthropic.claude-opus-4-8[1m]");
        assertNotNull(stats);
        assertEquals(3000, stats.getMinuteTokenStats().getUsed());
        assertEquals(1, stats.getHourRequestStats().getUsed());
        assertEquals(0, new BigDecimal("5.000").compareTo(stats.getDayCostStats().getUsed()));

        // and the per-deployment endpoint agrees with it
        assertEquals(3000, rateLimiter.getLimitStats(model, proxyContext).result().getMinuteTokenStats().getUsed());
    }

    /**
     * A custom application reports an already url encoded resource url as its name, and reaches the limiter
     * through increase() even though limit() skips it. Its record must stay where it has always been, which is
     * why the path is still percent decoded.
     */
    @Test
    public void testIncreaseKeepsTheRecordPathOfAnEncodedApplicationName() {
        Config config = new Config();
        Role role = new Role();
        role.setLimits(Map.of());
        config.setRoles(Map.of("role", role));

        ProxyContext proxyContext = userContext(config, List.of("role"));
        Application application = new Application();
        application.setName("applications/buck/my%20app");
        stubInlineExecutor();

        TokenUsage tokenUsage = new TokenUsage();
        tokenUsage.setTotalTokens(7);
        String bucket = BucketBuilder.buildInitiatorBucket(proxyContext);
        assertNull(rateLimiter.increase(application, bucket, tokenUsage, null, null).cause());

        assertNotNull(resourceService.getResource(ResourceDescriptorFactory
                .fromDecoded(ResourceTypes.LIMIT, bucket, bucket, "applications/buck/my app/tokens")));

        // and the read side resolves to the same record, so the encoded name stays symmetric end to end
        UserLimitStats stats = rateLimiter.getUserStats(proxyContext, List.of(application), true).result();
        assertNotNull(stats);
        assertEquals(7, stats.getDeployments().get("applications/buck/my%20app").getMinuteTokenStats().getUsed());
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
