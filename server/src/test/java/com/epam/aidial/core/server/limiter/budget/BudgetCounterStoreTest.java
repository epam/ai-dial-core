package com.epam.aidial.core.server.limiter.budget;

import com.epam.aidial.core.config.RateLimitSchedule;
import com.epam.aidial.core.config.policy.BudgetInstance;
import com.epam.aidial.core.config.policy.Policy;
import com.epam.aidial.core.server.FileUtil;
import com.epam.aidial.core.server.util.ProxyUtil;
import com.epam.aidial.core.server.vertx.AsyncTaskExecutor;
import com.epam.aidial.core.storage.blobstore.BlobStorage;
import com.epam.aidial.core.storage.blobstore.Storage;
import com.epam.aidial.core.storage.service.LockService;
import com.epam.aidial.core.storage.service.ResourceService;
import com.epam.aidial.core.storage.service.TimerService;
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
import java.util.concurrent.Callable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BudgetCounterStoreTest {

    private static final String COST_OBJECT = "projA";
    private static final RateLimitSchedule SCHEDULE = new RateLimitSchedule();
    private static final long DAY1 = Instant.parse("2026-11-03T10:00:00Z").toEpochMilli();
    private static final long DAY2 = Instant.parse("2026-11-04T10:00:00Z").toEpochMilli();
    private static final long NEXT_MONTH = Instant.parse("2026-12-03T10:00:00Z").toEpochMilli();

    private static RedisServer redisServer;
    private static RedissonClient redissonClient;

    private BlobStorage blobStorage;
    private Path testDir;
    private BudgetCounterStore store;

    @BeforeAll
    static void beforeAll() throws IOException {
        redisServer = RedisServer.newRedisServer()
                .port(16371)
                .bind("127.0.0.1")
                .setting("maxmemory 16M")
                .setting("maxmemory-policy volatile-lfu")
                .build();
        redisServer.start();
        org.redisson.config.Config redisClientConfig = new ConfigSupport().fromJSON("""
                {
                  "singleServerConfig": {
                     "address": "redis://localhost:16371"
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

        testDir = FileUtil.baseTestPath(BudgetCounterStoreTest.class);
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
        ResourceService resourceService = new ResourceService(mock(TimerService.class), redissonClient, blobStorage,
                new LockService(redissonClient, null), settings, null);
        AsyncTaskExecutor taskExecutor = mock(AsyncTaskExecutor.class);
        when(taskExecutor.submit(any(Callable.class))).thenAnswer(invocation -> {
            Callable<?> callable = invocation.getArgument(0);
            return Future.succeededFuture(callable.call());
        });
        store = new BudgetCounterStore(taskExecutor, resourceService);
    }

    @AfterEach
    void afterEach() {
        if (blobStorage != null) {
            blobStorage.close();
        }
        FileUtil.deleteDir(testDir);
    }

    @Test
    void testEmptyStoreCountsZero() {
        BudgetUsage usage = load(instance("projA/g1"), "alice", DAY1);

        assertEquals(0, BigDecimal.ZERO.compareTo(usage.day()));
        assertEquals(0, BigDecimal.ZERO.compareTo(usage.month()));
        assertEquals(Instant.parse("2026-11-04T00:00:00Z").toEpochMilli(), usage.dayResetsAt());
        assertEquals(Instant.parse("2026-12-01T00:00:00Z").toEpochMilli(), usage.monthResetsAt());
    }

    @Test
    void testChargesAccumulateAcrossModels() {
        BudgetInstance instance = instance("projA/g1");

        charge(instance, "alice", "gpt", "1.50", DAY1);
        charge(instance, "alice", "claude", "2.25", DAY1);
        charge(instance, "alice", "gpt", "0.25", DAY1);

        BudgetUsage usage = load(instance, "alice", DAY1);
        assertEquals(0, new BigDecimal("4.00").compareTo(usage.day()));
        assertEquals(0, new BigDecimal("4.00").compareTo(usage.month()));
    }

    @Test
    void testCountersAreKeptPerUserAndInstance() {
        BudgetInstance first = instance("projA/g1");
        BudgetInstance second = instance("projA/g2");

        charge(first, "alice", "gpt", "1", DAY1);
        charge(first, "bob", "gpt", "10", DAY1);

        assertEquals(0, BigDecimal.ONE.compareTo(load(first, "alice", DAY1).day()));
        assertEquals(0, BigDecimal.TEN.compareTo(load(first, "bob", DAY1).day()));
        assertEquals(0, BigDecimal.ZERO.compareTo(load(second, "alice", DAY1).day()));
    }

    @Test
    void testStaleDayCountsZeroAndMonthSurvives() {
        // trace 11
        BudgetInstance instance = instance("projA/g1");
        charge(instance, "alice", "gpt", "3", DAY1);

        BudgetUsage nextDay = load(instance, "alice", DAY2);
        assertEquals(0, BigDecimal.ZERO.compareTo(nextDay.day()));
        assertEquals(0, new BigDecimal("3").compareTo(nextDay.month()));

        BudgetUsage nextMonth = load(instance, "alice", NEXT_MONTH);
        assertEquals(0, BigDecimal.ZERO.compareTo(nextMonth.day()));
        assertEquals(0, BigDecimal.ZERO.compareTo(nextMonth.month()));
    }

    @Test
    void testChargeRollsStaleWindowsOver() {
        BudgetInstance instance = instance("projA/g1");
        charge(instance, "alice", "gpt", "3", DAY1);

        charge(instance, "alice", "gpt", "2", DAY2);

        BudgetUsage usage = load(instance, "alice", DAY2);
        assertEquals(0, new BigDecimal("2").compareTo(usage.day()));
        assertEquals(0, new BigDecimal("5").compareTo(usage.month()));
    }

    @Test
    void testUpdateKeepsCounterAndRegrantStartsAtZero() {
        BudgetInstance original = instance("projA/g1");
        charge(original, "alice", "gpt", "4", DAY1);

        // an update of the policy keeps the instance id
        BudgetInstance updated = new BudgetInstance("projA/g1", "p", new Policy(), null, null);
        assertEquals(0, new BigDecimal("4").compareTo(load(updated, "alice", DAY1).day()));

        // a re-grant gets a new id
        assertEquals(0, BigDecimal.ZERO.compareTo(load(instance("projA/g1-regrant"), "alice", DAY1).day()));
    }

    @Test
    void testOverLimitChargeIsRecordedInFull() {
        BudgetInstance instance = instance("projA/g1");

        charge(instance, "alice", "gpt", "1000000", DAY1);

        assertEquals(0, new BigDecimal("1000000").compareTo(load(instance, "alice", DAY1).day()));
    }

    @Test
    void testNonPositiveChargeIsIgnored() {
        BudgetInstance instance = instance("projA/g1");

        charge(instance, "alice", "gpt", "0", DAY1);
        charge(instance, "alice", "gpt", "-5", DAY1);
        store.charge(COST_OBJECT, instance, "alice", "gpt", null, DAY1, SCHEDULE);

        assertEquals(0, BigDecimal.ZERO.compareTo(load(instance, "alice", DAY1).day()));
    }

    @Test
    void testPercentInUserIdIsStoredOnceEncoded() {
        BudgetInstance instance = instance("projA/g1");

        charge(instance, "50%25", "gpt", "1", DAY1);
        charge(instance, "50%", "gpt", "2", DAY1);

        assertEquals(0, BigDecimal.ONE.compareTo(load(instance, "50%25", DAY1).day()));
        assertEquals(0, new BigDecimal("2").compareTo(load(instance, "50%", DAY1).day()));
    }

    @Test
    void testUserIdAndModelWithSpecialCharactersAreStored() {
        BudgetInstance instance = instance("projA/g1");

        charge(instance, "a@b.com", "claude-opus-4-8[1m]", "1", DAY1);

        assertEquals(0, BigDecimal.ONE.compareTo(load(instance, "a@b.com", DAY1).day()));
    }

    @Test
    void testUnstorableUserIdIsRejected() {
        BudgetInstance instance = instance("projA/g1");

        assertThrows(IllegalArgumentException.class, () -> load(instance, "a/b", DAY1));
        assertThrows(IllegalArgumentException.class, () -> load(instance, "a\"b", DAY1));
        assertThrows(IllegalArgumentException.class, () -> charge(instance, "a{b}", "gpt", "1", DAY1));
    }

    @Test
    void testOverLongUserIdIsRejected() {
        BudgetInstance instance = instance("projA/g1");
        String userId = "u".repeat(1000);

        assertThrows(IllegalArgumentException.class, () -> load(instance, userId, DAY1));
        assertThrows(IllegalArgumentException.class, () -> charge(instance, userId, "gpt", "1", DAY1));
    }

    private BudgetUsage load(BudgetInstance instance, String userId, long timestamp) {
        return store.load(COST_OBJECT, instance, userId, timestamp, SCHEDULE).result();
    }

    private void charge(BudgetInstance instance, String userId, String model, String cost, long timestamp) {
        store.charge(COST_OBJECT, instance, userId, model, new BigDecimal(cost), timestamp, SCHEDULE);
    }

    private static BudgetInstance instance(String id) {
        return new BudgetInstance(id, "p", new Policy(), null, null);
    }
}
