package com.epam.aidial.core.storage.migration;

import com.epam.aidial.core.storage.FileUtil;
import com.epam.aidial.core.storage.blobstore.BlobStorage;
import com.epam.aidial.core.storage.blobstore.Storage;
import com.epam.aidial.core.storage.service.LockService;
import com.epam.aidial.core.storage.service.TimerService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import redis.embedded.RedisServer;

import java.io.IOException;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class BucketMigrationRegistryTest {

    private static final long REFRESH_PERIOD = 60_000;

    private RedisServer server;
    private RedissonClient client;
    private BlobStorage storage;
    private LockService lockService;
    private Path testDir;
    private BucketMigrationRegistry registry;

    @BeforeEach
    void init() throws IOException {
        try {
            server = RedisServer.newRedisServer()
                    .port(16375)
                    .bind("127.0.0.1")
                    .setting("maxmemory 8M")
                    .setting("maxmemory-policy volatile-lfu")
                    .build();
            server.start();

            Config config = new Config();
            config.useSingleServer().setAddress("redis://localhost:16375");
            client = Redisson.create(config);

            testDir = FileUtil.baseTestPath(BucketMigrationRegistryTest.class);
            FileUtil.createDir(testDir.resolve("test"));
            ObjectMapper mapper = new ObjectMapper();
            String blobStorageConfig = """
                    {
                        "bucket": "test",
                        "provider": "filesystem",
                        "identity": "access-key",
                        "credential": "secret-key",
                        "overrides": {
                          "jclouds.filesystem.basedir": %s
                        }
                      }
                    """.formatted(mapper.writeValueAsString(testDir.toString()));
            storage = new BlobStorage(mapper.readValue(blobStorageConfig, Storage.class));
            lockService = new LockService(client, null);
            registry = newRegistry();
        } catch (Throwable e) {
            destroy();
            throw e;
        }
    }

    @AfterEach
    void destroy() throws IOException {
        try {
            if (registry != null) {
                registry.close();
            }
            if (client != null) {
                client.shutdown();
            }
            if (storage != null) {
                storage.close();
            }
        } finally {
            if (server != null) {
                server.stop();
            }
            FileUtil.deleteDir(testDir);
        }
    }

    @Test
    public void testEveryBucketIsLegacyUntilOneIsSealed() {
        assertEquals(BucketMigrationState.LEGACY, registry.resolve("Users/u1/"));
        assertEquals(BucketMigrationState.LEGACY, registry.resolve("public/"));
    }

    @Test
    public void testBucketMovesThroughTheSealToMigrated() {
        registry.seal("Users/u1/");
        assertEquals(BucketMigrationState.MIGRATING, registry.resolve("Users/u1/"));

        registry.promote("Users/u1/");
        assertEquals(BucketMigrationState.MIGRATED, registry.resolve("Users/u1/"));
    }

    @Test
    public void testOneBucketMigratingLeavesTheOthersAlone() {
        registry.seal("Users/u1/");
        registry.promote("Users/u1/");

        assertEquals(BucketMigrationState.LEGACY, registry.resolve("Users/u2/"));
        assertEquals(BucketMigrationState.LEGACY, registry.resolve("public/"));
    }

    @Test
    public void testPromotingWithoutSealingIsRejected() {
        assertThrows(IllegalStateException.class, () -> registry.promote("Users/u1/"));
        assertEquals(BucketMigrationState.LEGACY, registry.resolve("Users/u1/"));
    }

    @Test
    public void testRollbackGoesBackThroughTheSeal() {
        registry.seal("Users/u1/");
        registry.promote("Users/u1/");

        assertThrows(IllegalStateException.class, () -> registry.revert("Users/u1/"));

        registry.seal("Users/u1/");
        registry.revert("Users/u1/");
        assertEquals(BucketMigrationState.LEGACY, registry.resolve("Users/u1/"));
    }

    @Test
    public void testStateSurvivesRestart() {
        registry.seal("Users/u1/");
        registry.promote("Users/u1/");
        registry.close();

        registry = newRegistry();
        assertEquals(BucketMigrationState.MIGRATED, registry.resolve("Users/u1/"));
    }

    @Test
    public void testAnotherPodPicksUpChangeOnRefresh() {
        BucketMigrationRegistry other = newRegistry();
        try {
            registry.seal("Users/u1/");
            assertEquals(BucketMigrationState.LEGACY, other.resolve("Users/u1/"),
                    "a change is not visible to another pod before it refreshes");

            other.refresh();
            assertEquals(BucketMigrationState.MIGRATING, other.resolve("Users/u1/"));
        } finally {
            other.close();
        }
    }

    @Test
    public void testPropagationWindowCoversTwoRefreshes() {
        assertEquals(2 * REFRESH_PERIOD, registry.propagationWindow());
    }

    private BucketMigrationRegistry newRegistry() {
        TimerService timerService = Mockito.mock(TimerService.class);
        Mockito.when(timerService.scheduleWithFixedDelay(Mockito.anyLong(), Mockito.anyLong(), Mockito.any()))
                .thenReturn(Mockito.mock(TimerService.Timer.class));
        return new BucketMigrationRegistry(storage, lockService, timerService, REFRESH_PERIOD);
    }

    @Test
    public void testRepeatedTransitionIsAccepted() {
        registry.seal("Users/u1/");
        registry.seal("Users/u1/");
        assertEquals(BucketMigrationState.MIGRATING, registry.resolve("Users/u1/"));

        registry.promote("Users/u1/");
        registry.promote("Users/u1/");
        assertEquals(BucketMigrationState.MIGRATED, registry.resolve("Users/u1/"));
    }

    @Test
    public void testSkippedStepIsStillRefused() {
        // Tolerating a repeated step must not tolerate a missing one: a bucket cannot be promoted without
        // having been sealed, whatever else has happened to it.
        assertThrows(IllegalStateException.class, () -> registry.promote("Users/u2/"));
    }
}
