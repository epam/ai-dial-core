package com.epam.aidial.core.server.service;

import com.epam.aidial.core.config.Config;
import com.epam.aidial.core.credentials.encryption.CredentialEncryptionService;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.config.ConfigStore;
import com.epam.aidial.core.server.data.ApiKeyData;
import com.epam.aidial.core.server.limiter.RateLimiter;
import com.epam.aidial.core.server.log.LogStore;
import com.epam.aidial.core.server.security.ApiKeyStore;
import com.epam.aidial.core.server.token.TokenStatsTracker;
import com.epam.aidial.core.server.token.TokenUsage;
import com.epam.aidial.core.server.util.ProxyUtil;
import com.epam.aidial.core.server.vertx.AsyncTaskExecutor;
import com.epam.aidial.core.storage.data.ResourceItemMetadata;
import com.epam.aidial.core.storage.resource.ResourceDescriptor;
import com.epam.aidial.core.storage.service.ResourceService;
import com.epam.aidial.core.storage.util.EtagHeader;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientRequest;
import io.vertx.core.http.HttpClientResponse;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpVersion;
import io.vertx.core.http.RequestOptions;
import io.vertx.core.json.JsonObject;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Answers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.ConfigSupport;
import redis.embedded.RedisServer;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith({MockitoExtension.class, VertxExtension.class})
class BackgroundJobServiceTest {

    private static final long TEST_POLL_INTERVAL_MS = 20;
    private static final long TEST_LEASE_TIMEOUT_MS = 100;
    private static final String PREFIX = "testprefix";
    private static final String JOB_ID = "dial_test-model_abc123";

    private static RedisServer redisServer;
    private static RedissonClient redissonClient;

    @Mock(answer = Answers.RETURNS_DEEP_STUBS)
    private ProxyContext proxyContext;

    @Mock
    private ConfigStore configStore;

    @Mock
    private RateLimiter rateLimiter;

    @Mock
    private TokenStatsTracker tokenStatsTracker;

    @Mock
    private ApiKeyStore apiKeyStore;

    @Mock
    private LogStore logStore;

    @Mock
    private CredentialEncryptionService encryptionService;

    @Mock
    private HttpClient httpClient;

    @Mock(answer = Answers.RETURNS_DEEP_STUBS)
    private HttpClientRequest httpRequest;

    @Mock
    private HttpClientResponse httpResponse;

    private ResourceService resourceService;
    private Map<String, String> resourceStore;
    private BackgroundJobService service;
    private BackgroundJobService poller;

    @BeforeAll
    static void startRedis() throws IOException {
        redisServer = RedisServer.newRedisServer()
                .port(16370)
                .bind("127.0.0.1")
                .setting("maxmemory 16M")
                .setting("maxmemory-policy volatile-lfu")
                .build();
        redisServer.start();
        ConfigSupport configSupport = new ConfigSupport();
        org.redisson.config.Config redisClientConfig = configSupport.fromJSON(
                "{\"singleServerConfig\":{\"address\":\"redis://localhost:16370\"}}",
                org.redisson.config.Config.class);
        redissonClient = Redisson.create(redisClientConfig);
    }

    @AfterAll
    static void stopRedis() throws IOException {
        if (redissonClient != null) {
            redissonClient.shutdown();
        }
        if (redisServer != null) {
            redisServer.stop();
        }
    }

    @BeforeEach
    void setUp(Vertx vertx) {
        redissonClient.getKeys().flushall();
        resourceStore = new ConcurrentHashMap<>();
        resourceService = buildResourceServiceMock();

        lenient().when(encryptionService.encrypt(any(), any(), any())).thenAnswer(inv -> inv.getArgument(1));
        lenient().when(encryptionService.decrypt(any(), any(), any())).thenAnswer(inv -> inv.getArgument(1));
        lenient().doAnswer(inv -> {
            ApiKeyData data = inv.getArgument(0);
            data.setPerRequestKey("test-polling-key");
            return null;
        }).when(apiKeyStore).assignPerRequestApiKey(any(ApiKeyData.class), any(Duration.class));

        BackgroundJobService.Settings settings = buildTestSettings(10);
        AsyncTaskExecutor taskExecutor = new AsyncTaskExecutor(vertx,
                new JsonObject().put("useVirtualThreads", false));
        service = spy(new BackgroundJobService(vertx, redissonClient, PREFIX,
                resourceService, taskExecutor,
                configStore, apiKeyStore, rateLimiter, tokenStatsTracker,
                httpClient, logStore, encryptionService, settings));
        service.init();

        lenient().when(proxyContext.getUserId()).thenReturn("test-user");
        lenient().when(proxyContext.getProxyApiKeyData().getPerRequestKey()).thenReturn("test-per-request-key");
        lenient().when(proxyContext.getRequest().version()).thenReturn(HttpVersion.HTTP_1_1);
        lenient().when(proxyContext.getRequest().method()).thenReturn(HttpMethod.POST);
        lenient().when(proxyContext.getRequest().uri()).thenReturn("/v1/responses");
        lenient().when(proxyContext.getRequestBody()).thenReturn(Buffer.buffer("{}"));
        // a real claims node: the deep-stub default is an ObjectNode mock, which serializes the record into broken JSON
        lenient().when(proxyContext.getUserClaims())
                .thenReturn(ProxyUtil.MAPPER.createObjectNode().put("email", "jane.doe@example.com"));

        poller = new BackgroundJobService(null, null, null,
                null, null,
                configStore, null, null, null,
                httpClient, null, encryptionService, new BackgroundJobService.Settings());
        poller.setLocalBaseUrl("http://localhost");
    }

    @Test
    void isJobActiveReturnsTrueWhenRecordExists(VertxTestContext ctx) throws Throwable {
        service.saveJob(JOB_ID, proxyContext)
                .compose(ignored -> service.isJobActive(JOB_ID))
                .onSuccess(active -> ctx.verify(() -> {
                    assertTrue(active);
                    ctx.completeNow();
                }))
                .onFailure(ctx::failNow);
        await(ctx);
    }

    @Test
    void isJobActiveReturnsFalseWhenNoRecord(VertxTestContext ctx) throws Throwable {
        service.isJobActive(JOB_ID)
                .onSuccess(active -> ctx.verify(() -> {
                    assertFalse(active);
                    ctx.completeNow();
                }))
                .onFailure(ctx::failNow);
        await(ctx);
    }

    @Test
    void deleteJobDeletesRecord(VertxTestContext ctx) throws Throwable {
        service.saveJob(JOB_ID, proxyContext)
                .compose(ignored -> service.deleteJob(JOB_ID))
                .compose(deleted -> service.isJobActive(JOB_ID)
                        .onSuccess(active -> ctx.verify(() -> {
                            assertTrue(deleted, "finishStreamingJob should return true when record existed");
                            assertFalse(active, "job should no longer be active after cancellation");
                            ctx.completeNow();
                        })))
                .onFailure(ctx::failNow);
        await(ctx);
    }

    @Test
    void deleteJobReturnsFalseWhenRecordAlreadyGone(VertxTestContext ctx) throws Throwable {
        service.deleteJob(JOB_ID)
                .onSuccess(deleted -> ctx.verify(() -> {
                    assertFalse(deleted);
                    ctx.completeNow();
                }))
                .onFailure(ctx::failNow);
        await(ctx);
    }

    @Test
    void saveJobStartsPollingAndCompletesJob(VertxTestContext ctx) throws Throwable {
        doReturn(Future.succeededFuture(new ResponsesApiClient.TerminalResult(Buffer.buffer("{}"), new TokenUsage())))
                .when(service).poll(any(), any());
        Config config = mock(Config.class);
        when(configStore.get()).thenReturn(config);
        when(apiKeyStore.getApiKeyData(anyString(), any())).thenReturn(Future.failedFuture("not found"));
        when(apiKeyStore.invalidatePerRequestApiKey(any()))
                .thenAnswer(inv -> {
                    ctx.completeNow();
                    return Future.succeededFuture(true);
                });

        service.saveJob(JOB_ID, proxyContext).onFailure(ctx::failNow);

        await(ctx);
        verify(apiKeyStore, times(2)).invalidatePerRequestApiKey(any());
        verify(logStore, timeout(1000)).save(any());
    }

    @Test
    void pollingContinuesUntilTerminalResult(VertxTestContext ctx) throws Throwable {
        doReturn(Future.succeededFuture(null))
                .doReturn(Future.succeededFuture(null))
                .doReturn(Future.succeededFuture(new ResponsesApiClient.TerminalResult(Buffer.buffer("{}"), new TokenUsage())))
                .when(service).poll(any(), any());
        when(configStore.get()).thenReturn(mock(Config.class));
        when(apiKeyStore.getApiKeyData(anyString(), any())).thenReturn(Future.failedFuture("not found"));
        when(apiKeyStore.invalidatePerRequestApiKey(any()))
                .thenAnswer(inv -> {
                    ctx.completeNow();
                    return Future.succeededFuture(true);
                });

        service.saveJob(JOB_ID, proxyContext).onFailure(ctx::failNow);

        await(ctx);
        verify(service, times(3)).poll(any(), any());
    }

    @Test
    void pollingAbandonedAfterMaxSequentialFailures(Vertx vertx, VertxTestContext ctx) throws Throwable {
        var bundle = buildServiceBundle(vertx, 3);
        doAnswer(inv -> Future.failedFuture("upstream error")).when(bundle.service()).poll(any(), any());
        when(configStore.get()).thenReturn(mock(Config.class));
        when(apiKeyStore.getApiKeyData(anyString(), any())).thenReturn(Future.failedFuture("not found"));
        when(apiKeyStore.invalidatePerRequestApiKey(any()))
                .thenAnswer(inv -> {
                    ctx.completeNow();
                    return Future.succeededFuture(true);
                });

        bundle.service().init();
        bundle.service().saveJob(JOB_ID, proxyContext).onFailure(ctx::failNow);

        await(ctx);
        verify(bundle.service(), times(3)).poll(any(), any());
    }

    @Test
    void failureCounterResetsOnNonTerminalPoll(Vertx vertx, VertxTestContext ctx) throws Throwable {
        var bundle = buildServiceBundle(vertx, 3);
        // Without the reset: after fail, fail, non-terminal, fail, fail the counter would hit 3 and give up.
        // With the reset: counter goes 1, 2, reset-to-0, 1, 2, then terminal completes normally.
        doReturn(Future.failedFuture("upstream error"))
                .doReturn(Future.failedFuture("upstream error"))
                .doReturn(Future.succeededFuture(null))
                .doReturn(Future.failedFuture("upstream error"))
                .doReturn(Future.failedFuture("upstream error"))
                .doReturn(Future.succeededFuture(new ResponsesApiClient.TerminalResult(Buffer.buffer("{}"), new TokenUsage())))
                .when(bundle.service()).poll(any(), any());
        when(configStore.get()).thenReturn(mock(Config.class));
        when(apiKeyStore.getApiKeyData(anyString(), any())).thenReturn(Future.failedFuture("not found"));
        when(apiKeyStore.invalidatePerRequestApiKey(any()))
                .thenAnswer(inv -> {
                    ctx.completeNow();
                    return Future.succeededFuture(true);
                });

        bundle.service().init();
        bundle.service().saveJob(JOB_ID, proxyContext).onFailure(ctx::failNow);

        await(ctx);
        verify(bundle.service(), times(6)).poll(any(), any());
    }

    @Test
    void tryCompleteFinalizesJobWhenTerminalResult(VertxTestContext ctx) throws Throwable {
        when(configStore.get()).thenReturn(mock(Config.class));
        when(apiKeyStore.getApiKeyData(anyString(), any())).thenReturn(Future.failedFuture("not found"));
        when(apiKeyStore.invalidatePerRequestApiKey(any()))
                .thenAnswer(inv -> {
                    ctx.completeNow();
                    return Future.succeededFuture(true);
                });

        service.saveJob(JOB_ID, proxyContext)
                .compose(ignored -> service.tryComplete(
                        JOB_ID, new ResponsesApiClient.TerminalResult(Buffer.buffer("{}"), new TokenUsage())))
                .onFailure(ctx::failNow);

        await(ctx);
        verify(apiKeyStore, times(2)).invalidatePerRequestApiKey(any());
    }

    /**
     * Mirrors {@code BaseDeploymentPostController}'s ancestor write for the synchronous request path:
     * every {@link TokenStatsTracker.AggregatedCost} the trace update returns gets its own
     * {@code recordAggregatedCost} write, using the response mapping's initiator bucket since there is
     * no live {@code ProxyContext} on this polled/background path.
     */
    @Test
    void tryCompleteRecordsAggregatedCostPerAncestor(VertxTestContext ctx) throws Throwable {
        when(configStore.get()).thenReturn(mock(Config.class));
        when(proxyContext.getDeployment().getName()).thenReturn("test-model");

        ApiKeyData decryptedKeyData = new ApiKeyData();
        decryptedKeyData.setTraceId("trace-id");
        decryptedKeyData.setSpanId("span-id");
        when(apiKeyStore.getApiKeyData(anyString(), any())).thenReturn(Future.succeededFuture(decryptedKeyData));

        List<TokenStatsTracker.AggregatedCost> aggregatedCosts = List.of(
                new TokenStatsTracker.AggregatedCost("inner-app", new BigDecimal("0.40")),
                new TokenStatsTracker.AggregatedCost("router-app", new BigDecimal("0.40")));
        when(tokenStatsTracker.updateDeploymentStats(eq("trace-id"), eq("span-id"), eq("test-model"), any()))
                .thenReturn(Future.succeededFuture(new TokenStatsTracker.UsageStats(new TokenUsage(), List.of(), aggregatedCosts)));
        when(rateLimiter.recordAggregatedCost(any(), any(), any())).thenReturn(Future.succeededFuture());
        when(apiKeyStore.invalidatePerRequestApiKey(any()))
                .thenAnswer(inv -> {
                    ctx.completeNow();
                    return Future.succeededFuture(true);
                });

        TokenUsage usage = new TokenUsage();
        usage.setTotalTokens(30);

        service.saveJob(JOB_ID, proxyContext)
                .compose(ignored -> service.tryComplete(
                        JOB_ID, new ResponsesApiClient.TerminalResult(Buffer.buffer("{}"), usage)))
                .onFailure(ctx::failNow);

        await(ctx);
        verify(rateLimiter).recordAggregatedCost(eq("inner-app"), eq("Users/test-user/"), eq(new BigDecimal("0.40")));
        verify(rateLimiter).recordAggregatedCost(eq("router-app"), eq("Users/test-user/"), eq(new BigDecimal("0.40")));
    }

    @Test
    void tryCompleteIsNoOpWhenNoRecord(VertxTestContext ctx) throws Throwable {
        service.tryComplete(JOB_ID, new ResponsesApiClient.TerminalResult(Buffer.buffer("{}"), null))
                .onSuccess(ignored -> ctx.completeNow())
                .onFailure(ctx::failNow);

        await(ctx);
        verify(apiKeyStore, never()).invalidatePerRequestApiKey(any());
    }

    @Test
    void pollReturnsResultForTerminalStatus(VertxTestContext ctx) throws Throwable {
        setupHttpMocks("{\"status\":\"completed\",\"usage\":{}}");

        poller.poll(JOB_ID, "test-per-request-key")
                .onSuccess(result -> ctx.verify(() -> {
                    assertNotNull(result);
                    ctx.completeNow();
                }))
                .onFailure(ctx::failNow);
        await(ctx);
    }

    @Test
    void pollReturnsNullForNonTerminalStatus(VertxTestContext ctx) throws Throwable {
        setupHttpMocks("{\"status\":\"in_progress\"}");

        poller.poll(JOB_ID, "test-per-request-key")
                .onSuccess(result -> ctx.verify(() -> {
                    assertNull(result);
                    ctx.completeNow();
                }))
                .onFailure(ctx::failNow);
        await(ctx);
    }

    @Test
    void pollReturnsNullForQueuedStatus(VertxTestContext ctx) throws Throwable {
        setupHttpMocks("{\"status\":\"queued\"}");

        poller.poll(JOB_ID, "test-per-request-key")
                .onSuccess(result -> ctx.verify(() -> {
                    assertNull(result);
                    ctx.completeNow();
                }))
                .onFailure(ctx::failNow);
        await(ctx);
    }

    @Test
    void pollFailsWhenUpstreamReturnsNonOkStatus(VertxTestContext ctx) throws Throwable {
        when(httpClient.request(any(RequestOptions.class))).thenReturn(Future.succeededFuture(httpRequest));
        when(httpRequest.putHeader(anyString(), anyString())).thenReturn(httpRequest);
        when(httpRequest.send()).thenReturn(Future.succeededFuture(httpResponse));
        when(httpResponse.statusCode()).thenReturn(500);

        poller.poll(JOB_ID, "test-per-request-key")
                .onSuccess(ignored -> ctx.failNow(new AssertionError("Expected failure but got success")))
                .onFailure(error -> ctx.verify(() -> {
                    assertTrue(error.getMessage().contains("500"));
                    ctx.completeNow();
                }));
        await(ctx);
    }

    @Test
    void pollFailsWhenResponseBodyIsNotJson(VertxTestContext ctx) throws Throwable {
        setupHttpMocks("not valid json {{{");

        poller.poll(JOB_ID, "test-per-request-key")
                .onSuccess(ignored -> ctx.failNow(new AssertionError("Expected failure but got success")))
                .onFailure(error -> ctx.completeNow());
        await(ctx);
    }

    @Test
    void pollFailsWhenResponseBodyIsJsonArray(VertxTestContext ctx) throws Throwable {
        setupHttpMocks("[1, 2, 3]");

        poller.poll(JOB_ID, "test-per-request-key")
                .onSuccess(ignored -> ctx.failNow(new AssertionError("Expected failure but got success")))
                .onFailure(error -> ctx.verify(() -> {
                    assertTrue(error.getMessage().contains("not a JSON object"));
                    ctx.completeNow();
                }));
        await(ctx);
    }

    private void setupHttpMocks(String responseJson) {
        when(httpClient.request(any(RequestOptions.class))).thenReturn(Future.succeededFuture(httpRequest));
        when(httpRequest.putHeader(anyString(), anyString())).thenReturn(httpRequest);
        when(httpRequest.send()).thenReturn(Future.succeededFuture(httpResponse));
        when(httpResponse.statusCode()).thenReturn(200);
        when(httpResponse.body()).thenReturn(Future.succeededFuture(Buffer.buffer(responseJson)));
    }

    private ResourceService buildResourceServiceMock() {
        ResourceService mock = mock(ResourceService.class);
        lenient().when(mock.putResource(any(ResourceDescriptor.class), anyString(), any(EtagHeader.class)))
                .thenAnswer(inv -> {
                    ResourceDescriptor desc = inv.getArgument(0);
                    resourceStore.put(desc.getAbsoluteFilePath(), inv.getArgument(1));
                    return null;
                });
        lenient().when(mock.getResource(any(ResourceDescriptor.class)))
                .thenAnswer(inv -> {
                    ResourceDescriptor desc = inv.getArgument(0);
                    return resourceStore.get(desc.getAbsoluteFilePath());
                });
        lenient().when(mock.deleteResource(any(ResourceDescriptor.class), any(EtagHeader.class)))
                .thenAnswer(inv -> {
                    ResourceDescriptor desc = inv.getArgument(0);
                    return resourceStore.remove(desc.getAbsoluteFilePath()) != null;
                });
        lenient().when(mock.hasResource(any(ResourceDescriptor.class)))
                .thenAnswer(inv -> {
                    ResourceDescriptor desc = inv.getArgument(0);
                    return resourceStore.containsKey(desc.getAbsoluteFilePath());
                });
        lenient().when(mock.getResourceMetadata(any(ResourceDescriptor.class)))
                .thenAnswer(inv -> {
                    ResourceDescriptor desc = inv.getArgument(0);
                    return resourceStore.containsKey(desc.getAbsoluteFilePath())
                            ? new ResourceItemMetadata() : null;
                });
        return mock;
    }

    private record ServiceBundle(BackgroundJobService service) {}

    private ServiceBundle buildServiceBundle(Vertx vertx, int maxFailures) {
        BackgroundJobService.Settings settings = buildTestSettings(maxFailures);
        AsyncTaskExecutor taskExecutor = new AsyncTaskExecutor(vertx,
                new JsonObject().put("useVirtualThreads", false));
        BackgroundJobService svc = spy(new BackgroundJobService(vertx, redissonClient, PREFIX,
                resourceService, taskExecutor,
                configStore, apiKeyStore, rateLimiter, tokenStatsTracker,
                httpClient, logStore, encryptionService, settings));
        return new ServiceBundle(svc);
    }

    private static BackgroundJobService.Settings buildTestSettings(int maxFailures) {
        BackgroundJobService.Settings settings = new BackgroundJobService.Settings();
        settings.setInitialPollIntervalMs(TEST_POLL_INTERVAL_MS);
        settings.setSchedulerTickIntervalMs(TEST_POLL_INTERVAL_MS);
        settings.setMaxSequentialPollFailures(maxFailures);
        settings.setLeaseTimeoutMs(TEST_LEASE_TIMEOUT_MS);
        return settings;
    }

    private static void await(VertxTestContext ctx) throws Throwable {
        assertTrue(ctx.awaitCompletion(5, TimeUnit.SECONDS), "Test timed out");
        if (ctx.failed()) {
            throw ctx.causeOfFailure();
        }
    }
}
