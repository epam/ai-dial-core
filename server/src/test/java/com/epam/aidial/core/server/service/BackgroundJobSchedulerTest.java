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

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith({MockitoExtension.class, VertxExtension.class})
class BackgroundJobSchedulerTest {

    private static final String PREFIX = "testprefix";
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

    @Test
    void startupScanPicksUpJobsFromResourceService(Vertx vertx, VertxTestContext ctx) throws Throwable {
        ResourceDescriptor descriptor = ResponseIdUtil.getBackgroundJobDescriptor(JOB_ID);
        resourceStore.put(descriptor.getAbsoluteFilePath(), ProxyUtil.convertToString(buildRecord()));

        buildScheduler(vertx).scan();

        RScoredSortedSet<String> schedule = redissonClient.getScoredSortedSet(
                "background_job_schedule:" + PREFIX + "/queue", StringCodec.INSTANCE);
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

        RScoredSortedSet<String> schedule = redissonClient.getScoredSortedSet(
                "background_job_schedule:" + PREFIX + "/queue", StringCodec.INSTANCE);
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

    private BackgroundJobScheduler buildScheduler(Vertx vertx) {
        BackgroundJobService.Settings settings = new BackgroundJobService.Settings();
        settings.setInitialPollIntervalMs(20);
        settings.setSchedulerTickIntervalMs(20);
        settings.setLeaseTimeoutMs(100);
        AsyncTaskExecutor taskExecutor = new AsyncTaskExecutor(vertx,
                new JsonObject().put("useVirtualThreads", false));
        return new BackgroundJobScheduler(vertx, redissonClient, PREFIX,
                resourceService, taskExecutor, settings, dialId -> null, dialId -> {});
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
