package com.epam.aidial.core.server.service;

import com.epam.aidial.core.config.Config;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.config.ConfigStore;
import com.epam.aidial.core.server.data.ResponseMapping;
import com.epam.aidial.core.server.limiter.RateLimiter;
import com.epam.aidial.core.server.security.ApiKeyStore;
import com.epam.aidial.core.server.token.TokenStatsTracker;
import com.epam.aidial.core.server.token.TokenUsage;
import com.epam.aidial.core.storage.service.LockService;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
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
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith({MockitoExtension.class, VertxExtension.class})
class BackgroundJobServiceTest {

    private static final long TEST_POLL_INTERVAL_MS = 20;
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
    private BackgroundJobPoller poller;

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
        LockService lockService = new LockService(redissonClient, null);
        BackgroundJobService.Settings testSettings = new BackgroundJobService.Settings();
        testSettings.setPollIntervalMs(TEST_POLL_INTERVAL_MS);
        service = new BackgroundJobService(
                vertx, redissonClient, PREFIX, () -> UUID.randomUUID().toString(),
                lockService, configStore, apiKeyStore,
                rateLimiter, tokenStatsTracker, responseMappingService,
                poller, testSettings);

        lenient().when(proxyContext.getResponseId()).thenReturn(JOB_ID);
        lenient().when(proxyContext.getProxyApiKeyData().getPerRequestKey()).thenReturn("test-per-request-key");
    }

    @Test
    void saveJobPersistsRecord(Vertx vertx, VertxTestContext ctx) throws Throwable {
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
    void isJobActiveReturnsTrueWhenRecordExists(Vertx vertx, VertxTestContext ctx) throws Throwable {
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
    void isJobActiveReturnsFalseWhenNoRecord(Vertx vertx, VertxTestContext ctx) throws Throwable {
        service.isJobActive(JOB_ID)
                .onSuccess(active -> ctx.verify(() -> {
                    assertFalse(active);
                    ctx.completeNow();
                }))
                .onFailure(ctx::failNow);
        await(ctx);
    }

    @Test
    void cancelStreamingJobDeletesRecord(Vertx vertx, VertxTestContext ctx) throws Throwable {
        service.saveJob(proxyContext)
                .compose(ignored -> service.startStreamingJob(JOB_ID))
                .compose(ignored -> service.cancelStreamingJob(JOB_ID))
                .compose(deleted -> service.isJobActive(JOB_ID)
                        .onSuccess(active -> ctx.verify(() -> {
                            assertTrue(deleted, "cancelStreamingJob should return true when record existed");
                            assertFalse(active, "job should no longer be active after cancellation");
                            ctx.completeNow();
                        })))
                .onFailure(ctx::failNow);
        await(ctx);
    }

    @Test
    void cancelStreamingJobReturnsFalseWhenRecordAlreadyGone(Vertx vertx, VertxTestContext ctx) throws Throwable {
        service.cancelStreamingJob(JOB_ID)
                .onSuccess(deleted -> ctx.verify(() -> {
                    assertFalse(deleted);
                    ctx.completeNow();
                }))
                .onFailure(ctx::failNow);
        await(ctx);
    }

    @Test
    void submitStartsPollingAndCompletesJob(Vertx vertx, VertxTestContext ctx) throws Throwable {
        ResponseMapping mapping = buildMapping();
        when(responseMappingService.getMapping(anyString())).thenReturn(mapping);
        when(poller.poll(any())).thenReturn(Future.succeededFuture(new TokenUsage()));
        Config config = mock(Config.class);
        when(configStore.get()).thenReturn(config);

        when(apiKeyStore.invalidatePerRequestApiKey(any()))
                .thenAnswer(inv -> {
                    ctx.completeNow();
                    return Future.succeededFuture(true);
                });

        service.saveJob(proxyContext)
                .onSuccess(ignored -> service.submit(JOB_ID))
                .onFailure(ctx::failNow);

        await(ctx);

        verify(apiKeyStore).invalidatePerRequestApiKey(any());
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private static ResponseMapping buildMapping() {
        return ResponseMapping.builder()
                .upstreamResponseId(UPSTREAM_RESPONSE_ID)
                .upstreamKey(UPSTREAM_KEY)
                .deploymentName(DEPLOYMENT_NAME)
                .initiatorBucket("Users/test-user/")
                .build();
    }

    private static void await(VertxTestContext ctx) throws Throwable {
        assertTrue(ctx.awaitCompletion(5, TimeUnit.SECONDS), "Test timed out");
        if (ctx.failed()) {
            throw ctx.causeOfFailure();
        }
    }
}
