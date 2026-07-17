package com.epam.aidial.core.server.service;

import com.epam.aidial.core.server.data.BackgroundJobRecord;
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
import io.vertx.core.json.JsonObject;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.Redisson;
import org.redisson.api.RMap;
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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith({MockitoExtension.class, VertxExtension.class})
class BackgroundJobSchedulerTest {

    private static final String PREFIX = "testprefix";
    private static final String KEY_TAG = "{background_jobs:" + PREFIX + "}";
    private static final String SCHEDULE_KEY = "background_job_schedule:" + KEY_TAG + ":queue";
    private static final String JOB_ID = "dial_test-model_abc123";

    private static RedisServer redisServer;
    private static RedissonClient redissonClient;

    private ResourceService resourceService;
    private Map<String, String> resourceStore;

    @BeforeAll
    static void startRedis() throws IOException {
        redisServer = RedisServer.newRedisServer()
                .port(16371)
                .bind("127.0.0.1")
                .setting("maxmemory 16M")
                .setting("maxmemory-policy volatile-lfu")
                .build();
        redisServer.start();
        ConfigSupport configSupport = new ConfigSupport();
        org.redisson.config.Config redisClientConfig = configSupport.fromJSON(
                "{\"singleServerConfig\":{\"address\":\"redis://localhost:16371\"}}",
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
    void setUp() {
        redissonClient.getKeys().flushall();
        resourceStore = new ConcurrentHashMap<>();
        resourceService = buildResourceServiceMock();
    }

    // -------------------------------------------------------------------------
    // scan() tests
    // -------------------------------------------------------------------------

    @Test
    void startupScanPicksUpJobsFromResourceService(Vertx vertx, VertxTestContext ctx) throws Throwable {
        ResourceDescriptor descriptor = ResponseIdUtil.getBackgroundJobDescriptor(JOB_ID);
        resourceStore.put(descriptor.getAbsoluteFilePath(), ProxyUtil.convertToString(buildRecord()));

        buildScheduler(vertx).scan();

        RScoredSortedSet<String> schedule = redissonClient.getScoredSortedSet(SCHEDULE_KEY, StringCodec.INSTANCE);
        vertx.setTimer(500, id -> ctx.verify(() -> {
            assertTrue(schedule.contains(JOB_ID), "scan should add orphaned job to schedule");
            ctx.completeNow();
        }));
        await(ctx);
    }

    @Test
    void startupScanDoesNotOverwriteExistingScheduledScore(Vertx vertx, VertxTestContext ctx) throws Throwable {
        ResourceDescriptor descriptor = ResponseIdUtil.getBackgroundJobDescriptor(JOB_ID);
        resourceStore.put(descriptor.getAbsoluteFilePath(), ProxyUtil.convertToString(buildRecord()));

        RScoredSortedSet<String> schedule = redissonClient.getScoredSortedSet(SCHEDULE_KEY, StringCodec.INSTANCE);
        long futureScore = System.currentTimeMillis() + 60_000;
        schedule.add(futureScore, JOB_ID);

        buildScheduler(vertx).scan();

        vertx.setTimer(500, id -> ctx.verify(() -> {
            Double score = schedule.getScore(JOB_ID);
            assertTrue(score != null && score >= futureScore,
                    "scan should not overwrite existing future score");
            ctx.completeNow();
        }));
        await(ctx);
    }

    @Test
    void scanExpiresTtlExceededJobs(Vertx vertx, VertxTestContext ctx) throws Throwable {
        ResourceDescriptor descriptor = ResponseIdUtil.getBackgroundJobDescriptor(JOB_ID);
        resourceStore.put(descriptor.getAbsoluteFilePath(), ProxyUtil.convertToString(buildRecord()));

        AtomicBoolean expireCalled = new AtomicBoolean(false);
        BackgroundJobService.Settings settings = defaultSettings();
        settings.setJobTtlMs(0); // every job is immediately expired
        BackgroundJobScheduler scheduler = buildScheduler(vertx, settings, dialId -> expireCalled.set(true));

        scheduler.scan();

        RScoredSortedSet<String> schedule = redissonClient.getScoredSortedSet(SCHEDULE_KEY, StringCodec.INSTANCE);
        vertx.setTimer(500, id -> ctx.verify(() -> {
            assertTrue(expireCalled.get(), "expirer should have been called");
            assertFalse(schedule.contains(JOB_ID), "expired job should not be scheduled");
            ctx.completeNow();
        }));
        await(ctx);
    }

    @Test
    void scanFetchesIndividualMetadataWhenCreatedAtMissing(Vertx vertx, VertxTestContext ctx) throws Throwable {
        ResourceDescriptor descriptor = ResponseIdUtil.getBackgroundJobDescriptor(JOB_ID);
        resourceStore.put(descriptor.getAbsoluteFilePath(), ProxyUtil.convertToString(buildRecord()));

        // Folder listing returns item without createdAt
        ResourceItemMetadata itemWithoutCreatedAt = new ResourceItemMetadata();
        itemWithoutCreatedAt.setName(JOB_ID);
        itemWithoutCreatedAt.setNodeType(NodeType.ITEM);
        itemWithoutCreatedAt.setDescriptor(descriptor);
        // createdAt intentionally left null

        ResourceItemMetadata fullMeta = new ResourceItemMetadata();
        fullMeta.setName(JOB_ID);
        fullMeta.setNodeType(NodeType.ITEM);
        fullMeta.setDescriptor(descriptor);
        fullMeta.setCreatedAt(System.currentTimeMillis());

        ResourceFolderMetadata folder = mock(ResourceFolderMetadata.class);
        doReturn(List.of(itemWithoutCreatedAt)).when(folder).getItems();
        when(folder.getNextToken()).thenReturn(null);
        doReturn(folder).when(resourceService).getFolderMetadata(any(), any(), anyInt(), anyBoolean());
        when(resourceService.getResourceMetadata(descriptor)).thenReturn(fullMeta);

        buildScheduler(vertx).scan();

        RScoredSortedSet<String> schedule = redissonClient.getScoredSortedSet(SCHEDULE_KEY, StringCodec.INSTANCE);
        vertx.setTimer(500, id -> ctx.verify(() -> {
            assertTrue(schedule.contains(JOB_ID), "job with fetched metadata should be scheduled");
            verify(resourceService).getResourceMetadata(descriptor);
            ctx.completeNow();
        }));
        await(ctx);
    }

    @Test
    void scanSkipsJobWhenIndividualMetadataNotFound(Vertx vertx, VertxTestContext ctx) throws Throwable {
        ResourceDescriptor descriptor = ResponseIdUtil.getBackgroundJobDescriptor(JOB_ID);

        ResourceItemMetadata itemWithoutCreatedAt = new ResourceItemMetadata();
        itemWithoutCreatedAt.setName(JOB_ID);
        itemWithoutCreatedAt.setNodeType(NodeType.ITEM);
        itemWithoutCreatedAt.setDescriptor(descriptor);

        ResourceFolderMetadata folder = mock(ResourceFolderMetadata.class);
        doReturn(List.of(itemWithoutCreatedAt)).when(folder).getItems();
        when(folder.getNextToken()).thenReturn(null);
        when(resourceService.getFolderMetadata(any(), any(), anyInt(), anyBoolean())).thenReturn(folder);
        when(resourceService.getResourceMetadata(descriptor)).thenReturn(null);

        buildScheduler(vertx).scan();

        RScoredSortedSet<String> schedule = redissonClient.getScoredSortedSet(SCHEDULE_KEY, StringCodec.INSTANCE);
        vertx.setTimer(500, id -> ctx.verify(() -> {
            assertFalse(schedule.contains(JOB_ID), "job with no metadata should be skipped");
            ctx.completeNow();
        }));
        await(ctx);
    }

    @Test
    void scanIteratesMultiplePages(Vertx vertx, VertxTestContext ctx) throws Throwable {
        String jobId1 = "dial_test-model_page1job";
        String jobId2 = "dial_test-model_page2job";

        ResourceItemMetadata item1 = itemWithCreatedAt(jobId1);
        ResourceItemMetadata item2 = itemWithCreatedAt(jobId2);

        ResourceFolderMetadata page1 = mock(ResourceFolderMetadata.class);
        doReturn(List.of(item1)).when(page1).getItems();
        when(page1.getNextToken()).thenReturn("page2token");

        ResourceFolderMetadata page2 = mock(ResourceFolderMetadata.class);
        doReturn(List.of(item2)).when(page2).getItems();
        when(page2.getNextToken()).thenReturn(null);

        AtomicInteger callCount = new AtomicInteger(0);
        when(resourceService.getFolderMetadata(any(), any(), anyInt(), anyBoolean()))
                .thenAnswer(inv -> callCount.getAndIncrement() == 0 ? page1 : page2);

        buildScheduler(vertx).scan();

        RScoredSortedSet<String> schedule = redissonClient.getScoredSortedSet(SCHEDULE_KEY, StringCodec.INSTANCE);
        vertx.setTimer(500, id -> ctx.verify(() -> {
            assertTrue(schedule.contains(jobId1), "page 1 job should be scheduled");
            assertTrue(schedule.contains(jobId2), "page 2 job should be scheduled");
            ctx.completeNow();
        }));
        await(ctx);
    }

    // -------------------------------------------------------------------------
    // cancel() tests
    // -------------------------------------------------------------------------

    @Test
    void cancelRemovesJobFromScheduleAndState(Vertx vertx, VertxTestContext ctx) throws Throwable {
        RScoredSortedSet<String> schedule = redissonClient.getScoredSortedSet(SCHEDULE_KEY, StringCodec.INSTANCE);
        schedule.add(System.currentTimeMillis(), JOB_ID);

        RMap<String, String> state = redissonClient.getMap(stateKey(JOB_ID), StringCodec.INSTANCE);
        state.put("owner", "99999");

        buildScheduler(vertx).cancel(JOB_ID)
                .onComplete(ctx.succeeding(ignored -> ctx.verify(() -> {
                    assertFalse(schedule.contains(JOB_ID), "cancel should remove from schedule");
                    assertFalse(state.isExists(), "cancel should delete state hash");
                    ctx.completeNow();
                })));
        await(ctx);
    }

    // -------------------------------------------------------------------------
    // pollJobs() end-to-end tests
    // -------------------------------------------------------------------------

    @Test
    void pollCompletesJobWhenPollerReturnsFinished(Vertx vertx, VertxTestContext ctx) throws Throwable {
        BackgroundJobService.Poller poller = mock(BackgroundJobService.Poller.class);
        when(poller.poll()).thenReturn(Future.succeededFuture(true));

        BackgroundJobScheduler scheduler = buildScheduler(vertx, defaultSettings(), id -> {}, id -> poller);
        scheduler.schedule(JOB_ID, System.currentTimeMillis() - 1000);
        scheduler.init();

        RScoredSortedSet<String> schedule = redissonClient.getScoredSortedSet(SCHEDULE_KEY, StringCodec.INSTANCE);
        vertx.setTimer(1000, id -> ctx.verify(() -> {
            assertFalse(schedule.contains(JOB_ID), "finished job should be removed from schedule");
            assertFalse(redissonClient.getMap(stateKey(JOB_ID), StringCodec.INSTANCE).isExists(),
                    "finished job state should be deleted");
            ctx.completeNow();
        }));
        await(ctx);
    }

    @Test
    void pollReschedulesJobWhenPollerReturnsNotFinished(Vertx vertx, VertxTestContext ctx) throws Throwable {
        BackgroundJobService.Poller poller = mock(BackgroundJobService.Poller.class);
        when(poller.poll()).thenReturn(Future.succeededFuture(false));

        BackgroundJobService.Settings settings = defaultSettings();
        settings.setInitialPollIntervalMs(500);
        settings.setPollBackoffFactor(1.0); // no backoff growth

        BackgroundJobScheduler scheduler = buildScheduler(vertx, settings, id -> {}, id -> poller);
        long before = System.currentTimeMillis();
        scheduler.schedule(JOB_ID, before - 1000);
        scheduler.init();

        RScoredSortedSet<String> schedule = redissonClient.getScoredSortedSet(SCHEDULE_KEY, StringCodec.INSTANCE);
        vertx.setTimer(1000, id -> ctx.verify(() -> {
            Double score = schedule.getScore(JOB_ID);
            assertNotNull(score, "not-finished job should remain in schedule");
            assertTrue(score > before, "rescheduled score should be in the future");
            ctx.completeNow();
        }));
        await(ctx);
    }

    @Test
    void pollReschedulesJobOnPollFailure(Vertx vertx, VertxTestContext ctx) throws Throwable {
        BackgroundJobService.Poller poller = mock(BackgroundJobService.Poller.class);
        when(poller.poll()).thenReturn(Future.failedFuture(new RuntimeException("upstream error")));

        BackgroundJobService.Settings settings = defaultSettings();
        settings.setMaxSequentialPollFailures(3);
        settings.setInitialPollIntervalMs(10_000);

        BackgroundJobScheduler scheduler = buildScheduler(vertx, settings, id -> {}, id -> poller);
        scheduler.schedule(JOB_ID, System.currentTimeMillis() - 1000);
        scheduler.init();

        RScoredSortedSet<String> schedule = redissonClient.getScoredSortedSet(SCHEDULE_KEY, StringCodec.INSTANCE);
        vertx.setTimer(1000, id -> ctx.verify(() -> {
            assertTrue(schedule.contains(JOB_ID), "failed-once job should remain scheduled for retry");
            String errors = (String) redissonClient.getMap(stateKey(JOB_ID), StringCodec.INSTANCE).get("errors");
            assertTrue(errors != null && Integer.parseInt(errors) >= 1,
                    "error count should be incremented in state");
            ctx.completeNow();
        }));
        await(ctx);
    }

    @Test
    void pollCompletesAndCallsFailWhenErrorLimitExceeded(Vertx vertx, VertxTestContext ctx) throws Throwable {
        AtomicBoolean failCalled = new AtomicBoolean(false);
        BackgroundJobService.Poller poller = mock(BackgroundJobService.Poller.class);
        when(poller.poll()).thenReturn(Future.failedFuture(new RuntimeException("upstream error")));
        when(poller.fail()).thenAnswer(inv -> {
            failCalled.set(true);
            return Future.succeededFuture();
        });

        BackgroundJobService.Settings settings = defaultSettings();
        settings.setMaxSequentialPollFailures(1); // give up immediately on first failure

        BackgroundJobScheduler scheduler = buildScheduler(vertx, settings, id -> {}, id -> poller);
        scheduler.schedule(JOB_ID, System.currentTimeMillis() - 1000);
        scheduler.init();

        RScoredSortedSet<String> schedule = redissonClient.getScoredSortedSet(SCHEDULE_KEY, StringCodec.INSTANCE);
        vertx.setTimer(1000, id -> ctx.verify(() -> {
            assertFalse(schedule.contains(JOB_ID), "job should be removed after hitting error limit");
            assertTrue(failCalled.get(), "fail() should be called when error limit is exceeded");
            ctx.completeNow();
        }));
        await(ctx);
    }

    @Test
    void pollCompletesJobWhenPollerNotFound(Vertx vertx, VertxTestContext ctx) throws Throwable {
        BackgroundJobScheduler scheduler = buildScheduler(vertx, defaultSettings(), id -> {}, id -> null);
        scheduler.schedule(JOB_ID, System.currentTimeMillis() - 1000);
        scheduler.init();

        RScoredSortedSet<String> schedule = redissonClient.getScoredSortedSet(SCHEDULE_KEY, StringCodec.INSTANCE);
        vertx.setTimer(1000, id -> ctx.verify(() -> {
            assertFalse(schedule.contains(JOB_ID), "job with no poller should be cleaned up from schedule");
            ctx.completeNow();
        }));
        await(ctx);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private BackgroundJobScheduler buildScheduler(Vertx vertx) {
        return buildScheduler(vertx, defaultSettings(), id -> {});
    }

    private BackgroundJobScheduler buildScheduler(Vertx vertx, BackgroundJobService.Settings settings,
                                                  Consumer<String> jobExpirer) {
        return buildScheduler(vertx, settings, jobExpirer, id -> null);
    }

    private BackgroundJobScheduler buildScheduler(Vertx vertx, BackgroundJobService.Settings settings,
                                                  Consumer<String> jobExpirer,
                                                  Function<String, BackgroundJobService.Poller> pollerProvider) {
        AsyncTaskExecutor taskExecutor = new AsyncTaskExecutor(vertx,
                new JsonObject().put("useVirtualThreads", false));
        return new BackgroundJobScheduler(vertx, redissonClient, PREFIX,
                resourceService, taskExecutor, settings, pollerProvider, jobExpirer);
    }

    private static BackgroundJobService.Settings defaultSettings() {
        BackgroundJobService.Settings settings = new BackgroundJobService.Settings();
        settings.setInitialPollIntervalMs(20);
        settings.setSchedulerTickIntervalMs(20);
        settings.setLeaseTimeoutMs(500);
        settings.setMaxSequentialPollFailures(5);
        settings.setMaxParallelJobs(10);
        settings.setJobTtlMs(TimeUnit.DAYS.toMillis(1));
        return settings;
    }

    private static String stateKey(String jobId) {
        return "background_job_state:" + KEY_TAG + ":" + jobId;
    }

    private static ResourceItemMetadata itemWithCreatedAt(String name) {
        ResourceItemMetadata meta = new ResourceItemMetadata();
        meta.setName(name);
        meta.setNodeType(NodeType.ITEM);
        meta.setCreatedAt(System.currentTimeMillis());
        return meta;
    }

    private ResourceService buildResourceServiceMock() {
        ResourceService mock = mock(ResourceService.class);
        lenient().when(mock.putResource(any(ResourceDescriptor.class), any(), any(EtagHeader.class)))
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
        lenient().when(mock.getFolderMetadata(any(), any(), anyInt(), anyBoolean()))
                .thenAnswer(inv -> {
                    if (resourceStore.isEmpty()) {
                        return null;
                    }
                    List<MetadataBase> items = resourceStore.keySet().stream()
                            .map(path -> {
                                String name = path.substring(path.lastIndexOf('/') + 1);
                                return (MetadataBase) itemWithCreatedAt(name);
                            })
                            .toList();
                    ResourceFolderMetadata folder = mock(ResourceFolderMetadata.class);
                    doReturn(items).when(folder).getItems();
                    when(folder.getNextToken()).thenReturn(null);
                    return folder;
                });
        return mock;
    }

    private static BackgroundJobRecord buildRecord() {
        String encodedKey = Base64.getEncoder().encodeToString(
                "test-per-request-key".getBytes(StandardCharsets.UTF_8));
        return BackgroundJobRecord.builder()
                .perRequestKey(encodedKey)
                .isRootSpan(false)
                .requestBody("{}")
                .build();
    }

    private static void await(VertxTestContext ctx) throws Throwable {
        assertTrue(ctx.awaitCompletion(5, TimeUnit.SECONDS), "Test timed out");
        if (ctx.failed()) {
            throw ctx.causeOfFailure();
        }
    }
}