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
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    @Test
    public void testCompleteIsRefusedWhileSomeBucketIsStillMoving() {
        registry.seal("Users/u1/");

        assertThrows(IllegalStateException.class, registry::complete);
        assertEquals(BucketMigrationState.LEGACY, registry.resolve("Users/u2/"),
                "a refused completion changes nothing");
    }

    @Test
    public void testCompleteMakesEveryBucketMigratedAndSurvivesRestart() {
        registry.seal("Users/u1/");
        registry.promote("Users/u1/");

        registry.complete();

        // Compaction: the default flips and the per-bucket entries go, so a bucket the document has never
        // heard of — one born after the migration — resolves to the tenant tree with everyone else.
        assertEquals(BucketMigrationState.MIGRATED, registry.resolve("Users/u1/"));
        assertEquals(BucketMigrationState.MIGRATED, registry.resolve("Users/u2/"));
        assertEquals(BucketMigrationState.MIGRATED, registry.resolve("public/deployments/born-later/"));

        registry.close();
        registry = newRegistry();
        assertEquals(BucketMigrationState.MIGRATED, registry.resolve("Users/u2/"));
        assertTrue(BucketMigrationRegistry.hasMigratedBuckets(storage));
    }

    @Test
    public void testCompleteIsRepeatable() {
        registry.complete();
        registry.complete();
        assertEquals(BucketMigrationState.MIGRATED, registry.resolve("Users/u1/"));
    }

    @Test
    public void testTenantRootedLayoutIsRefusedUntilTheMigrationIsComplete() {
        // An empty store is a greenfield deployment, which serves the tenant-rooted layout from day one.
        BucketMigrationRegistry.requireReadyForTenantRootedLayout(storage);

        // Data on the legacy layout and no migration state: the flip would point every bucket at a tree
        // with none of its data.
        storage.store("Users/u1/conversations/chat", "application/json", null, Map.of(), "{}".getBytes());
        IllegalStateException untouched = assertThrows(IllegalStateException.class,
                () -> BucketMigrationRegistry.requireReadyForTenantRootedLayout(storage));
        assertTrue(untouched.getMessage().contains("Users/"), untouched.getMessage());

        // Part way through: the same, whether the bucket is being copied or has already moved.
        registry.seal("Users/u1/");
        assertThrows(IllegalStateException.class,
                () -> BucketMigrationRegistry.requireReadyForTenantRootedLayout(storage));
        registry.promote("Users/u1/");
        assertThrows(IllegalStateException.class,
                () -> BucketMigrationRegistry.requireReadyForTenantRootedLayout(storage));

        // Declared complete: the legacy tree is still there, because a migration copies rather than
        // moves, and that is no longer a reason to refuse.
        registry.complete();
        BucketMigrationRegistry.requireReadyForTenantRootedLayout(storage);
    }

    @Test
    public void testHasMigratedBucketsReadsTheStoreWithoutStartingAnything() {
        assertFalse(BucketMigrationRegistry.hasMigratedBuckets(storage),
                "a store nobody has migrated has nothing to report");

        registry.seal("Users/u1/");

        // What a node asks before deciding whether it can serve this store at all.
        assertTrue(BucketMigrationRegistry.hasMigratedBuckets(storage));

        registry.revert("Users/u1/");
        assertFalse(BucketMigrationRegistry.hasMigratedBuckets(storage));
    }
}
