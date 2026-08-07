package com.epam.aidial.core.server.service;

import com.epam.aidial.core.config.Config;
import com.epam.aidial.core.config.Deployment;
import com.epam.aidial.core.config.Upstream;
import com.epam.aidial.core.credentials.encryption.CredentialEncryptionService;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.config.ConfigStore;
import com.epam.aidial.core.server.data.ResponseMapping;
import com.epam.aidial.core.server.limiter.RateLimiter;
import com.epam.aidial.core.server.log.LogStore;
import com.epam.aidial.core.server.security.ApiKeyStore;
import com.epam.aidial.core.server.security.ExtractedClaims;
import com.epam.aidial.core.server.token.TokenStatsTracker;
import com.epam.aidial.core.server.token.TokenUsage;
import com.epam.aidial.core.server.upstream.UpstreamRoute;
import com.epam.aidial.core.server.upstream.UpstreamRouteProvider;
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
import io.vertx.core.http.HttpClientOptions;
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
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.isNull;
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

        BackgroundJobService.Settings settings = buildTestSettings(10);
        AsyncTaskExecutor taskExecutor = new AsyncTaskExecutor(vertx,
                new JsonObject().put("useVirtualThreads", false));
        service = spy(new BackgroundJobService(vertx, redissonClient, PREFIX,
                responseMappingService, resourceService, taskExecutor,
                configStore, apiKeyStore, rateLimiter, tokenStatsTracker,
                upstreamRouteProvider, responsesApiClient, logStore, encryptionService, settings));
        service.init();

        lenient().when(proxyContext.getProxyApiKeyData().getPerRequestKey()).thenReturn("test-per-request-key");
        lenient().when(proxyContext.getRequest().version()).thenReturn(HttpVersion.HTTP_1_1);
        lenient().when(proxyContext.getRequest().method()).thenReturn(HttpMethod.POST);
        lenient().when(proxyContext.getRequest().uri()).thenReturn("/v1/responses");
        lenient().when(proxyContext.getRequestBody()).thenReturn(Buffer.buffer("{}"));
        // a real claims node: the deep-stub default is an ObjectNode mock, which serializes the record into broken JSON
        lenient().when(proxyContext.getExtractedClaims()).thenReturn(new ExtractedClaims("sub", List.of("role"), "hash",
                ProxyUtil.MAPPER.createObjectNode().put("email", "jane.doe@example.com"), null, "Jane Doe"));
        lenient().when(responseMappingService.getMapping(anyString())).thenReturn(buildMapping());

        ResponsesApiClient pollClient = new ResponsesApiClient(httpClient, new HttpClientOptions());
        poller = new BackgroundJobService(null, null, null,
                null, null, null,
                configStore, null, null, null, upstreamRouteProvider, pollClient,
                null, encryptionService, new BackgroundJobService.Settings());
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
                .when(service).poll(any());
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
        verify(apiKeyStore).invalidatePerRequestApiKey(any());
        verify(logStore, timeout(1000)).save(any());
    }

    @Test
    void pollingContinuesUntilTerminalResult(VertxTestContext ctx) throws Throwable {
        doReturn(Future.succeededFuture(null))
                .doReturn(Future.succeededFuture(null))
                .doReturn(Future.succeededFuture(new ResponsesApiClient.TerminalResult(Buffer.buffer("{}"), new TokenUsage())))
                .when(service).poll(any());
        when(configStore.get()).thenReturn(mock(Config.class));
        when(apiKeyStore.getApiKeyData(anyString(), any())).thenReturn(Future.failedFuture("not found"));
        when(apiKeyStore.invalidatePerRequestApiKey(any()))
                .thenAnswer(inv -> {
                    ctx.completeNow();
                    return Future.succeededFuture(true);
                });

        service.saveJob(JOB_ID, proxyContext).onFailure(ctx::failNow);

        await(ctx);
        verify(service, times(3)).poll(any());
    }

    @Test
    void pollingAbandonedAfterMaxSequentialFailures(Vertx vertx, VertxTestContext ctx) throws Throwable {
        var bundle = buildServiceBundle(vertx, 3);
        doAnswer(inv -> Future.failedFuture("upstream error")).when(bundle.service()).poll(any());
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
        verify(bundle.service(), times(3)).poll(any());
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
                .when(bundle.service()).poll(any());
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
        verify(bundle.service(), times(6)).poll(any());
    }

    @Test
    void tryCompleteFinalizesJobWhenTerminalResult(VertxTestContext ctx) throws Throwable {
        ResponseMapping mapping = buildMapping();
        when(configStore.get()).thenReturn(mock(Config.class));
        when(apiKeyStore.getApiKeyData(anyString(), any())).thenReturn(Future.failedFuture("not found"));
        when(apiKeyStore.invalidatePerRequestApiKey(any()))
                .thenAnswer(inv -> {
                    ctx.completeNow();
                    return Future.succeededFuture(true);
                });

        service.saveJob(JOB_ID, proxyContext)
                .compose(ignored -> service.tryComplete(
                        JOB_ID, mapping, new ResponsesApiClient.TerminalResult(Buffer.buffer("{}"), new TokenUsage())))
                .onFailure(ctx::failNow);

        await(ctx);
        verify(apiKeyStore).invalidatePerRequestApiKey(any());
    }

    @Test
    void tryCompleteIsNoOpWhenNoRecord(VertxTestContext ctx) throws Throwable {
        ResponseMapping mapping = buildMapping();

        service.tryComplete(JOB_ID, mapping, new ResponsesApiClient.TerminalResult(Buffer.buffer("{}"), null))
                .onSuccess(ignored -> ctx.completeNow())
                .onFailure(ctx::failNow);

        await(ctx);
        verify(apiKeyStore, never()).invalidatePerRequestApiKey(any());
    }

    @Test
    void pollReturnsResultForTerminalStatus(VertxTestContext ctx) throws Throwable {
        setupHttpMocks("{\"status\":\"completed\",\"usage\":{}}");
        setupDeploymentMocks();

        poller.poll(buildMapping())
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
        setupDeploymentMocks();

        poller.poll(buildMapping())
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
        setupDeploymentMocks();

        poller.poll(buildMapping())
                .onSuccess(result -> ctx.verify(() -> {
                    assertNull(result);
                    ctx.completeNow();
                }))
                .onFailure(ctx::failNow);
        await(ctx);
    }

    @Test
    void pollFailsWhenDeploymentNotFound(VertxTestContext ctx) throws Throwable {
        Config config = mock(Config.class);
        when(configStore.get()).thenReturn(config);
        when(config.selectDeployment(anyString())).thenReturn(null);

        poller.poll(buildMapping())
                .onSuccess(ignored -> ctx.failNow(new AssertionError("Expected failure but got success")))
                .onFailure(error -> ctx.verify(() -> {
                    assertTrue(error.getMessage().contains("not found"));
                    ctx.completeNow();
                }));
        await(ctx);
    }

    @Test
    void pollFailsWhenUpstreamReturnsNonOkStatus(VertxTestContext ctx) throws Throwable {
        setupDeploymentMocks();
        when(httpClient.request(any(RequestOptions.class))).thenReturn(Future.succeededFuture(httpRequest));
        when(httpRequest.putHeader(anyString(), anyString())).thenReturn(httpRequest);
        when(httpRequest.send()).thenReturn(Future.succeededFuture(httpResponse));
        when(httpResponse.statusCode()).thenReturn(500);

        poller.poll(buildMapping())
                .onSuccess(ignored -> ctx.failNow(new AssertionError("Expected failure but got success")))
                .onFailure(error -> ctx.verify(() -> {
                    assertTrue(error.getMessage().contains("500"));
                    ctx.completeNow();
                }));
        await(ctx);
    }

    @Test
    void pollFailsWhenResponseBodyIsNotJson(VertxTestContext ctx) throws Throwable {
        setupDeploymentMocks();
        setupHttpMocks("not valid json {{{");

        poller.poll(buildMapping())
                .onSuccess(ignored -> ctx.failNow(new AssertionError("Expected failure but got success")))
                .onFailure(error -> ctx.completeNow());
        await(ctx);
    }

    @Test
    void pollFailsWhenResponseBodyIsJsonArray(VertxTestContext ctx) throws Throwable {
        setupDeploymentMocks();
        setupHttpMocks("[1, 2, 3]");

        poller.poll(buildMapping())
                .onSuccess(ignored -> ctx.failNow(new AssertionError("Expected failure but got success")))
                .onFailure(error -> ctx.verify(() -> {
                    assertTrue(error.getMessage().contains("not a JSON object"));
                    ctx.completeNow();
                }));
        await(ctx);
    }

    @Test
    void pollFailsWhenUpstreamRouteNotFound(VertxTestContext ctx) throws Throwable {
        Config config = mock(Config.class);
        Deployment deployment = mock(Deployment.class);
        when(configStore.get()).thenReturn(config);
        when(config.selectDeployment(anyString())).thenReturn(deployment);
        when(deployment.getResponsesEndpoint()).thenReturn("http://test-upstream/responses");
        when(upstreamRouteProvider.get(any(), any(), anyString()))
                .thenThrow(new RuntimeException("No available upstream"));

        poller.poll(buildMapping())
                .onSuccess(ignored -> ctx.failNow(new AssertionError("Expected failure but got success")))
                .onFailure(error -> ctx.verify(() -> {
                    assertTrue(error.getMessage().contains("Failed to get upstream"));
                    ctx.completeNow();
                }));
        await(ctx);
    }

    @Test
    void pollFailsWhenNoResponsesEndpoint(VertxTestContext ctx) throws Throwable {
        Config config = mock(Config.class);
        Deployment deployment = mock(Deployment.class);
        when(configStore.get()).thenReturn(config);
        when(config.selectDeployment(anyString())).thenReturn(deployment);
        when(deployment.getResponsesEndpoint()).thenReturn(null);
        when(deployment.getName()).thenReturn(DEPLOYMENT_NAME);

        poller.poll(buildMapping())
                .onSuccess(ignored -> ctx.failNow(new AssertionError("Expected failure but got success")))
                .onFailure(error -> ctx.verify(() -> {
                    assertTrue(error.getMessage().contains("responses endpoint"));
                    ctx.completeNow();
                }));
        await(ctx);
    }

    private void setupDeploymentMocks() {
        Config config = mock(Config.class);
        Deployment deployment = mock(Deployment.class);
        when(configStore.get()).thenReturn(config);
        when(config.selectDeployment(anyString())).thenReturn(deployment);
        when(deployment.getResponsesEndpoint()).thenReturn("http://test-upstream/responses");

        Upstream upstream = mock(Upstream.class);
        when(upstream.getKey()).thenReturn("api-key");
        when(upstream.getResponsesEndpoint()).thenReturn("http://test-upstream");
        when(upstream.getExtraData()).thenReturn("{}");

        UpstreamRoute route = mock(UpstreamRoute.class);
        when(route.next()).thenReturn(upstream);
        when(upstreamRouteProvider.get(any(Deployment.class), isNull(), anyString())).thenReturn(route);
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

    private static ResponseMapping buildMapping() {
        return ResponseMapping.builder()
                .upstreamResponseId(UPSTREAM_RESPONSE_ID)
                .upstreamKey(UPSTREAM_KEY)
                .deploymentName(DEPLOYMENT_NAME)
                .initiatorBucket("Users/test-user/")
                .build();
    }

    private record ServiceBundle(BackgroundJobService service) {}

    private ServiceBundle buildServiceBundle(Vertx vertx, int maxFailures) {
        BackgroundJobService.Settings settings = buildTestSettings(maxFailures);
        AsyncTaskExecutor taskExecutor = new AsyncTaskExecutor(vertx,
                new JsonObject().put("useVirtualThreads", false));
        BackgroundJobService svc = spy(new BackgroundJobService(vertx, redissonClient, PREFIX,
                responseMappingService, resourceService, taskExecutor,
                configStore, apiKeyStore, rateLimiter, tokenStatsTracker,
                upstreamRouteProvider, responsesApiClient, logStore, encryptionService, settings));
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
