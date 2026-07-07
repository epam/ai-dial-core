package com.epam.aidial.core.server.service;

import com.epam.aidial.core.config.Config;
import com.epam.aidial.core.credentials.encryption.CredentialEncryptionService;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.config.ConfigStore;
import com.epam.aidial.core.server.data.BackgroundJobRecord;
import com.epam.aidial.core.server.data.ResponseMapping;
import com.epam.aidial.core.server.limiter.RateLimiter;
import com.epam.aidial.core.server.log.LogStore;
import com.epam.aidial.core.server.security.ApiKeyStore;
import com.epam.aidial.core.server.token.TokenStatsTracker;
import com.epam.aidial.core.server.token.TokenUsage;
import com.epam.aidial.core.server.upstream.UpstreamRouteProvider;
import com.epam.aidial.core.server.util.ProxyUtil;
import com.epam.aidial.core.server.util.ResponseIdUtil;
import com.epam.aidial.core.server.vertx.AsyncTaskExecutor;
import com.epam.aidial.core.storage.data.MetadataBase;
import com.epam.aidial.core.storage.data.NodeType;
import com.epam.aidial.core.storage.data.ResourceFolderMetadata;
import com.epam.aidial.core.storage.data.ResourceItemMetadata;
import com.epam.aidial.core.storage.resource.ResourceDescriptor;
import com.epam.aidial.core.storage.service.ResourceService;
import com.epam.aidial.core.storage.util.EtagHeader;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpVersion;
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
import org.redisson.api.RScoredSortedSet;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.redisson.config.ConfigSupport;
import redis.embedded.RedisServer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
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
    private static final String DEPLOYMENT_NAME = "test-model";
    private static final String UPSTREAM_KEY = "upstream-key";
    private static final String UPSTREAM_RESPONSE_ID = "upstream-resp-id";

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
    private ResponseMappingService responseMappingService;

    @Mock
    private UpstreamRouteProvider upstreamRouteProvider;

    @Mock
    private ResponsesApiClient responsesApiClient;

    @Mock
    private LogStore logStore;

    @Mock
    private CredentialEncryptionService encryptionService;

    private ResourceService resourceService;
    private Map<String, String> resourceStore;
    private BackgroundJobService service;

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
        lenient().when(encryptionService.minEncryptedLength()).thenReturn(0);

        BackgroundJobService.Settings settings = buildTestSettings(10);
        AsyncTaskExecutor taskExecutor = new AsyncTaskExecutor(vertx,
                new JsonObject().put("useVirtualThreads", false));
        service = spy(new BackgroundJobService(vertx, redissonClient, PREFIX, resourceService, taskExecutor,
                configStore, apiKeyStore, rateLimiter, tokenStatsTracker, responseMappingService,
                upstreamRouteProvider, responsesApiClient, settings, logStore, encryptionService));

        lenient().when(proxyContext.getDialResponseId()).thenReturn(JOB_ID);
        lenient().when(proxyContext.getProxyApiKeyData().getPerRequestKey()).thenReturn("test-per-request-key");
        lenient().when(proxyContext.getRequest().version()).thenReturn(HttpVersion.HTTP_1_1);
        lenient().when(proxyContext.getRequest().method()).thenReturn(HttpMethod.POST);
        lenient().when(proxyContext.getRequest().uri()).thenReturn("/v1/responses");
        lenient().when(proxyContext.getRequestBody()).thenReturn(Buffer.buffer("{}"));
        lenient().when(responseMappingService.getMapping(anyString())).thenReturn(buildMapping());
    }

    @Test
    void isJobActiveReturnsTrueWhenRecordExists(VertxTestContext ctx) throws Throwable {
        service.saveJob(proxyContext)
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
    void finishStreamingJobDeletesRecord(VertxTestContext ctx) throws Throwable {
        service.saveJob(proxyContext)
                .compose(ignored -> service.finishStreamingJob(JOB_ID))
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
    void finishStreamingJobReturnsFalseWhenRecordAlreadyGone(VertxTestContext ctx) throws Throwable {
        service.finishStreamingJob(JOB_ID)
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
                .when(service).pollMapping(any());
        Config config = mock(Config.class);
        when(configStore.get()).thenReturn(config);
        when(apiKeyStore.getApiKeyData(anyString(), any())).thenReturn(Future.failedFuture("not found"));
        when(apiKeyStore.invalidatePerRequestApiKey(any()))
                .thenAnswer(inv -> {
                    ctx.completeNow();
                    return Future.succeededFuture(true);
                });

        service.init();
        service.saveJob(proxyContext).onFailure(ctx::failNow);

        await(ctx);
        verify(apiKeyStore).invalidatePerRequestApiKey(any());
        verify(logStore, timeout(1000)).save(any());
    }

    @Test
    void pollingContinuesUntilTerminalResult(VertxTestContext ctx) throws Throwable {
        doReturn(Future.succeededFuture(null))
                .doReturn(Future.succeededFuture(null))
                .doReturn(Future.succeededFuture(new ResponsesApiClient.TerminalResult(Buffer.buffer("{}"), new TokenUsage())))
                .when(service).pollMapping(any());
        when(configStore.get()).thenReturn(mock(Config.class));
        when(apiKeyStore.getApiKeyData(anyString(), any())).thenReturn(Future.failedFuture("not found"));
        when(apiKeyStore.invalidatePerRequestApiKey(any()))
                .thenAnswer(inv -> {
                    ctx.completeNow();
                    return Future.succeededFuture(true);
                });

        service.init();
        service.saveJob(proxyContext).onFailure(ctx::failNow);

        await(ctx);
        verify(service, times(3)).pollMapping(any());
    }

    @Test
    void pollingAbandonedAfterMaxSequentialFailures(Vertx vertx, VertxTestContext ctx) throws Throwable {
        BackgroundJobService svc = buildService(vertx, 3);
        doAnswer(inv -> Future.failedFuture("upstream error")).when(svc).pollMapping(any());
        when(configStore.get()).thenReturn(mock(Config.class));
        when(apiKeyStore.getApiKeyData(anyString(), any())).thenReturn(Future.failedFuture("not found"));
        when(apiKeyStore.invalidatePerRequestApiKey(any()))
                .thenAnswer(inv -> {
                    ctx.completeNow();
                    return Future.succeededFuture(true);
                });

        svc.init();
        svc.saveJob(proxyContext).onFailure(ctx::failNow);

        await(ctx);
        verify(svc, times(3)).pollMapping(any());
    }

    @Test
    void failureCounterResetsOnNonTerminalPoll(Vertx vertx, VertxTestContext ctx) throws Throwable {
        BackgroundJobService svc = buildService(vertx, 3);
        // Without the reset: after fail, fail, non-terminal, fail, fail the counter would hit 3 and give up.
        // With the reset: counter goes 1, 2, reset-to-0, 1, 2, then terminal completes normally.
        doReturn(Future.failedFuture("upstream error"))
                .doReturn(Future.failedFuture("upstream error"))
                .doReturn(Future.succeededFuture(null))
                .doReturn(Future.failedFuture("upstream error"))
                .doReturn(Future.failedFuture("upstream error"))
                .doReturn(Future.succeededFuture(new ResponsesApiClient.TerminalResult(Buffer.buffer("{}"), new TokenUsage())))
                .when(svc).pollMapping(any());
        when(configStore.get()).thenReturn(mock(Config.class));
        when(apiKeyStore.getApiKeyData(anyString(), any())).thenReturn(Future.failedFuture("not found"));
        when(apiKeyStore.invalidatePerRequestApiKey(any()))
                .thenAnswer(inv -> {
                    ctx.completeNow();
                    return Future.succeededFuture(true);
                });

        svc.init();
        svc.saveJob(proxyContext).onFailure(ctx::failNow);

        await(ctx);
        verify(svc, times(6)).pollMapping(any());
    }

    @Test
    void tryCompleteOnGetFinalizesJobWhenTerminalResult(VertxTestContext ctx) throws Throwable {
        ResponseMapping mapping = buildMapping();
        when(configStore.get()).thenReturn(mock(Config.class));
        when(apiKeyStore.getApiKeyData(anyString(), any())).thenReturn(Future.failedFuture("not found"));
        when(apiKeyStore.invalidatePerRequestApiKey(any()))
                .thenAnswer(inv -> {
                    ctx.completeNow();
                    return Future.succeededFuture(true);
                });

        service.saveJob(proxyContext)
                .compose(ignored -> service.tryCompleteOnGet(
                        JOB_ID, mapping, new ResponsesApiClient.TerminalResult(Buffer.buffer("{}"), new TokenUsage())))
                .onFailure(ctx::failNow);

        await(ctx);
        verify(apiKeyStore).invalidatePerRequestApiKey(any());
    }

    @Test
    void tryCompleteOnGetIsNoOpWhenNullResult(VertxTestContext ctx) throws Throwable {
        ResponseMapping mapping = buildMapping();

        service.saveJob(proxyContext)
                .compose(ignored -> service.tryCompleteOnGet(JOB_ID, mapping, null))
                .compose(ignored -> service.isJobActive(JOB_ID))
                .onSuccess(active -> ctx.verify(() -> {
                    assertTrue(active, "job should remain active after null tryCompleteOnGet");
                    ctx.completeNow();
                }))
                .onFailure(ctx::failNow);

        await(ctx);
        verify(apiKeyStore, never()).invalidatePerRequestApiKey(any());
    }

    @Test
    void tryCompleteOnGetIsNoOpWhenNoRecord(VertxTestContext ctx) throws Throwable {
        ResponseMapping mapping = buildMapping();

        service.tryCompleteOnGet(JOB_ID, mapping, new ResponsesApiClient.TerminalResult(Buffer.buffer("{}"), null))
                .onSuccess(ignored -> ctx.completeNow())
                .onFailure(ctx::failNow);

        await(ctx);
        verify(apiKeyStore, never()).invalidatePerRequestApiKey(any());
    }

    @Test
    void startupScanPicksUpJobsFromResourceService(Vertx vertx, VertxTestContext ctx) throws Throwable {
        when(configStore.get()).thenReturn(mock(Config.class));
        when(apiKeyStore.getApiKeyData(anyString(), any())).thenReturn(Future.failedFuture("not found"));
        when(apiKeyStore.invalidatePerRequestApiKey(any()))
                .thenAnswer(inv -> {
                    ctx.completeNow();
                    return Future.succeededFuture(true);
                });

        // Directly populate resourceStore to simulate a job that survived a crash
        // (record exists in ResourceService but not in Redis ZSET)
        ResourceDescriptor descriptor = ResponseIdUtil.getBackgroundJobDescriptor(JOB_ID);
        resourceStore.put(descriptor.getAbsoluteFilePath(), ProxyUtil.convertToString(buildRecord()));

        BackgroundJobService newService = buildService(vertx, 10);
        doReturn(Future.succeededFuture(new ResponsesApiClient.TerminalResult(Buffer.buffer("{}"), new TokenUsage())))
                .when(newService).pollMapping(any());
        newService.scan();
        newService.init();

        await(ctx);
        verify(newService, atLeastOnce()).pollMapping(any());
    }

    @Test
    void startupScanDoesNotOverwriteExistingScheduledScore(VertxTestContext ctx) throws Throwable {
        service.saveJob(proxyContext)
                .onSuccess(ignored -> {
                    // Override score to a far future time (job already scheduled)
                    long futureScore = System.currentTimeMillis() + 60_000;
                    RScoredSortedSet<String> schedule = redissonClient.getScoredSortedSet(
                            "background_job_schedule:" + PREFIX + "/queue", StringCodec.INSTANCE);
                    schedule.add(futureScore, JOB_ID);

                    service.scan();

                    Double score = schedule.getScore(JOB_ID);
                    ctx.verify(() -> {
                        assertTrue(score != null && score >= futureScore,
                                "scan should not overwrite existing future score");
                        ctx.completeNow();
                    });
                })
                .onFailure(ctx::failNow);
        await(ctx);
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
        lenient().when(mock.getFolderMetadata(any(), any(), anyInt(), anyBoolean()))
                .thenAnswer(inv -> {
                    if (resourceStore.isEmpty()) {
                        return null;
                    }
                    List<MetadataBase> items = resourceStore.keySet().stream()
                            .map(path -> {
                                String name = path.substring(path.lastIndexOf('/') + 1);
                                ResourceItemMetadata meta = new ResourceItemMetadata();
                                meta.setName(name);
                                meta.setNodeType(NodeType.ITEM);
                                meta.setCreatedAt(System.currentTimeMillis());
                                return (MetadataBase) meta;
                            })
                            .toList();
                    ResourceFolderMetadata folder = mock(ResourceFolderMetadata.class);
                    doReturn(items).when(folder).getItems();
                    when(folder.getNextToken()).thenReturn(null);
                    return folder;
                });
        return mock;
    }

    private BackgroundJobRecord buildRecord() {
        String rawKey = proxyContext.getProxyApiKeyData().getPerRequestKey();
        // Simulate encryptKey with mocked encryptionService (encrypt returns bytes unchanged → Base64-encode)
        String encodedKey = Base64.getEncoder().encodeToString(rawKey.getBytes(StandardCharsets.UTF_8));
        return BackgroundJobRecord.builder()
                .perRequestKey(encodedKey)
                .isRootSpan(proxyContext.getApiKeyData().getPerRequestKey() == null)
                .requestBody("{}")
                .build();
    }

    private static ResponseMapping buildMapping() {
        return ResponseMapping.builder()
                .upstreamResponseId(UPSTREAM_RESPONSE_ID)
                .upstreamKey(UPSTREAM_KEY)
                .deploymentName(DEPLOYMENT_NAME)
                .initiatorBucket("Users/test-user/")
                .build();
    }

    private BackgroundJobService buildService(Vertx vertx, int maxFailures) {
        BackgroundJobService.Settings settings = buildTestSettings(maxFailures);
        AsyncTaskExecutor taskExecutor = new AsyncTaskExecutor(vertx,
                new JsonObject().put("useVirtualThreads", false));
        return spy(new BackgroundJobService(vertx, redissonClient, PREFIX, resourceService, taskExecutor,
                configStore, apiKeyStore, rateLimiter, tokenStatsTracker, responseMappingService,
                upstreamRouteProvider, responsesApiClient, settings, logStore, encryptionService));
    }

    private static BackgroundJobService.Settings buildTestSettings(int maxFailures) {
        BackgroundJobService.Settings settings = new BackgroundJobService.Settings();
        settings.setInitialPollIntervalMs(TEST_POLL_INTERVAL_MS);
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
