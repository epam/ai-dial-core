package com.epam.aidial.core.server.limiter.budget;

import com.epam.aidial.core.config.Config;
import com.epam.aidial.core.config.RateLimitSchedule;
import com.epam.aidial.core.config.policy.BudgetInstance;
import com.epam.aidial.core.config.policy.Policy;
import com.epam.aidial.core.config.policy.PolicyAssignment;
import com.epam.aidial.core.server.FileUtil;
import com.epam.aidial.core.server.config.ConfigStore;
import com.epam.aidial.core.server.service.policy.PolicyRecordCodec;
import com.epam.aidial.core.server.service.policy.PolicyService;
import com.epam.aidial.core.server.tracing.BackgroundOperationTracer;
import com.epam.aidial.core.server.util.ProxyUtil;
import com.epam.aidial.core.server.util.ResourceDescriptorFactory;
import com.epam.aidial.core.server.vertx.AsyncTaskExecutor;
import com.epam.aidial.core.storage.blobstore.BlobStorage;
import com.epam.aidial.core.storage.blobstore.Storage;
import com.epam.aidial.core.storage.data.MetadataBase;
import com.epam.aidial.core.storage.data.ResourceFolderMetadata;
import com.epam.aidial.core.storage.resource.ResourceDescriptor;
import com.epam.aidial.core.storage.resource.ResourceTypes;
import com.epam.aidial.core.storage.service.LockService;
import com.epam.aidial.core.storage.service.ResourceService;
import com.epam.aidial.core.storage.service.TimerService;
import com.epam.aidial.core.storage.util.EtagHeader;
import io.vertx.core.Future;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.redisson.Redisson;
import org.redisson.api.RKeys;
import org.redisson.api.RedissonClient;
import org.redisson.config.ConfigSupport;
import redis.embedded.RedisServer;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.Callable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BudgetCounterSweepServiceTest {

    private static final String COST_OBJECT = "projA";
    private static final RateLimitSchedule SCHEDULE = new RateLimitSchedule();
    private static final long DAY1 = Instant.parse("2026-11-03T10:00:00Z").toEpochMilli();
    private static final long SAME_DAY = Instant.parse("2026-11-03T20:00:00Z").toEpochMilli();
    private static final long DAY2 = Instant.parse("2026-11-04T10:00:00Z").toEpochMilli();
    private static final long NEXT_MONTH = Instant.parse("2026-12-03T10:00:00Z").toEpochMilli();

    private static RedisServer redisServer;
    private static RedissonClient redissonClient;

    private BlobStorage blobStorage;
    private Path testDir;
    private ResourceService resourceService;
    private BudgetCounterStore store;
    private BudgetCounterSweepService sweeper;
    private final ResourceDescriptor limitsFolder = ResourceDescriptorFactory.fromDecoded(
            ResourceTypes.LIMIT, ResourceDescriptor.PLATFORM_BUCKET, ResourceDescriptor.PLATFORM_LOCATION, "");

    @BeforeAll
    static void beforeAll() throws IOException {
        redisServer = RedisServer.newRedisServer()
                .port(16372)
                .bind("127.0.0.1")
                .setting("maxmemory 16M")
                .setting("maxmemory-policy volatile-lfu")
                .build();
        redisServer.start();
        org.redisson.config.Config redisClientConfig = new ConfigSupport().fromJSON("""
                {
                  "singleServerConfig": {
                     "address": "redis://localhost:16372"
                  }
                }
                """, org.redisson.config.Config.class);
        redissonClient = Redisson.create(redisClientConfig);
    }

    @AfterAll
    static void afterAll() throws IOException {
        if (redissonClient != null) {
            redissonClient.shutdown();
        }
        if (redisServer != null) {
            redisServer.stop();
        }
    }

    @BeforeEach
    void beforeEach() throws IOException {
        RKeys keys = redissonClient.getKeys();
        for (String key : keys.getKeys()) {
            keys.delete(key);
        }

        testDir = FileUtil.baseTestPath(BudgetCounterSweepServiceTest.class);
        FileUtil.deleteDir(testDir);
        FileUtil.createDir(testDir.resolve("test"));
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

        ResourceService.Settings settings = new ResourceService.Settings(64 * 1048576, 1048576, 60000, 120000, 4096, 300000, 256);
        LockService lockService = new LockService(redissonClient, null);
        resourceService = new ResourceService(mock(TimerService.class), redissonClient, blobStorage, lockService, settings, null);
        AsyncTaskExecutor taskExecutor = mock(AsyncTaskExecutor.class);
        when(taskExecutor.submit(any(Callable.class))).thenAnswer(invocation -> {
            Callable<?> callable = invocation.getArgument(0);
            return Future.succeededFuture(callable.call());
        });
        store = new BudgetCounterStore(taskExecutor, resourceService);

        ConfigStore configStore = mock(ConfigStore.class);
        when(configStore.get()).thenReturn(new Config());
        sweeper = newSweeper(lockService, configStore, 500);
    }

    @AfterEach
    void afterEach() {
        if (blobStorage != null) {
            blobStorage.close();
        }
        FileUtil.deleteDir(testDir);
    }

    @Test
    void testOpenWindowsAreKept() {
        charge("g1", "alice");

        sweeper.tick(SAME_DAY);

        assertEquals(1, countRows());
    }

    @Test
    void testRowWithOpenMonthIsKept() {
        charge("g1", "alice");

        sweeper.tick(DAY2);

        assertEquals(1, countRows());
    }

    @Test
    void testClosedRowOfWithdrawnBudgetIsDeleted() {
        charge("g1", "alice");
        charge("g1", "bob");

        sweeper.tick(NEXT_MONTH);

        assertEquals(0, countRows());
    }

    @Test
    void testClosedRowOfLiveAssignmentIsKept() {
        charge("g1", "alice");
        putAssignment("g1", null);

        sweeper.tick(NEXT_MONTH);

        assertEquals(1, countRows());
    }

    @Test
    void testClosedRowOfExpiredAssignmentIsDeleted() {
        charge("g1", "alice");
        putAssignment("g1", "2026-11-10T00:00:00Z");

        sweeper.tick(NEXT_MONTH);

        assertEquals(0, countRows());
    }

    @Test
    void testClosedRowOfNotYetExpiredAssignmentIsKept() {
        charge("g1", "alice");
        putAssignment("g1", "2026-12-31T00:00:00Z");

        sweeper.tick(NEXT_MONTH);

        assertEquals(1, countRows());
    }

    @Test
    void testClosedRowOfSelfApplyingPolicyIsKept() {
        charge("g1", "alice");
        resourceService.putResource(PolicyService.describe(ResourceTypes.POLICY, COST_OBJECT + "/g1"),
                PolicyRecordCodec.write(new Policy()), EtagHeader.ANY, null);

        sweeper.tick(NEXT_MONTH);

        assertEquals(1, countRows());
    }

    @Test
    void testOnlyRowsOfWithdrawnBudgetAreDeleted() {
        charge("g1", "alice");
        charge("g2", "alice");
        putAssignment("g2", null);

        sweeper.tick(NEXT_MONTH);

        assertEquals(1, countRows());
        assertEquals(0, BigDecimal.ZERO.compareTo(store.loadBlocking(COST_OBJECT, instance("g1"), "alice", DAY1, SCHEDULE).month()));
    }

    @Test
    void testRowsOutsideBudgetLayoutAreIgnored() {
        ResourceDescriptor other = ResourceDescriptorFactory.fromDecoded(ResourceTypes.LIMIT, ResourceDescriptor.PLATFORM_BUCKET,
                ResourceDescriptor.PLATFORM_LOCATION, "projA/other/costs");
        resourceService.putResource(other, "{}", EtagHeader.ANY, null);

        sweeper.tick(NEXT_MONTH);

        assertEquals(1, countRows());
    }

    @Test
    void testSweepContinuesFromTheCursorOfThePreviousTick() {
        sweeper = newSweeper(new LockService(redissonClient, null), configStore(), 1);
        charge("g1", "alice");
        charge("g1", "bob");
        charge("g1", "carol");

        sweeper.tick(NEXT_MONTH);
        assertTrue(countRows() > 0, "a tick handles one page only");

        // the filesystem provider also lists the folders, which take up a page without being rows
        for (int i = 0; i < 40; i++) {
            sweeper.tick(NEXT_MONTH);
        }
        assertEquals(0, countRows());
    }

    @Test
    void testSweepersClaimDifferentPagesWithoutWaitingForTheRowsToBeHandled() {
        BudgetCounterSweepService other = newSweeper(new LockService(redissonClient, null), configStore(), 1);
        sweeper = newSweeper(new LockService(redissonClient, null), configStore(), 1);
        charge("g1", "alice");
        charge("g1", "bob");
        charge("g1", "carol");

        Set<String> claimed = new HashSet<>();
        int rows = 0;
        ResourceFolderMetadata page;
        do {
            // the pages are claimed one after another, none of them handled, by alternating sweepers
            page = (claimed.size() % 2 == 0 ? sweeper : other).claimPage();
            for (MetadataBase meta : page.getItems()) {
                rows++;
                claimed.add(meta.getUrl());
            }
        } while (page.getNextToken() != null);

        assertEquals(3, rows);
        assertEquals(3, claimed.size());
        assertEquals(3, countRows());
    }

    private BudgetCounterSweepService newSweeper(LockService lockService, ConfigStore configStore, int batch) {
        BudgetCounterSweepService.Settings settings = new BudgetCounterSweepService.Settings();
        settings.setBatch(batch);
        return new BudgetCounterSweepService(mock(TimerService.class), resourceService, lockService, redissonClient,
                configStore, settings, mock(BackgroundOperationTracer.class));
    }

    private static ConfigStore configStore() {
        ConfigStore configStore = mock(ConfigStore.class);
        when(configStore.get()).thenReturn(new Config());
        return configStore;
    }

    private void charge(String assignmentId, String userId) {
        store.charge(COST_OBJECT, instance(assignmentId), userId, "gpt", BigDecimal.ONE, DAY1, SCHEDULE).result();
    }

    private void putAssignment(String assignmentId, String expiresAt) {
        PolicyAssignment assignment = new PolicyAssignment();
        assignment.setPolicy("p");
        assignment.setExpiresAt(expiresAt);
        resourceService.putResource(PolicyService.describe(ResourceTypes.POLICY_ASSIGNMENT, COST_OBJECT + "/" + assignmentId),
                PolicyRecordCodec.write(assignment), EtagHeader.ANY, null);
    }

    private int countRows() {
        ResourceFolderMetadata folder = resourceService.getFolderMetadata(limitsFolder, null, 1000, true);
        return folder == null ? 0 : folder.getItems().size();
    }

    private static BudgetInstance instance(String assignmentId) {
        return new BudgetInstance(COST_OBJECT + "/" + assignmentId, "p", new Policy(), null, null);
    }
}
