package com.epam.aidial.core.storage.migration;

import com.epam.aidial.core.storage.FileUtil;
import com.epam.aidial.core.storage.blobstore.BlobStorage;
import com.epam.aidial.core.storage.blobstore.Storage;
import com.epam.aidial.core.storage.service.ResourceService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.mockito.Mockito;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The order of a migration is its safety property, so these assert the order and the guards rather than the
 * copy itself, which {@link BucketMigratorTest} covers. The registry and the resource service are mocked so
 * the sequence is observable; the migrator is real, so the locations a copy covers are genuinely enumerated.
 */
public class BucketMigrationTest {

    private static final String TENANT = "acme";
    private static final long WINDOW = 4000;

    private BlobStorage storage;
    private Path testDir;
    private BucketMigrationRegistry states;
    private ResourceService resources;
    private BucketMigration migration;
    private List<Long> waited;

    @BeforeEach
    void init() throws IOException {
        try {
            testDir = FileUtil.baseTestPath(BucketMigrationTest.class);
            FileUtil.createDir(testDir.resolve("test"));
            ObjectMapper mapper = new ObjectMapper();
            String config = """
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
            storage = new BlobStorage(mapper.readValue(config, Storage.class));
            states = Mockito.mock(BucketMigrationRegistry.class);
            resources = Mockito.mock(ResourceService.class);
            Mockito.when(states.propagationWindow()).thenReturn(WINDOW);
            Mockito.when(states.resolve(Mockito.anyString())).thenReturn(BucketMigrationState.MIGRATING);
            waited = new ArrayList<>();
            migration = new BucketMigration(states, new BucketMigrator(storage, TENANT), resources, waited::add);
        } catch (Throwable e) {
            destroy();
            throw e;
        }
    }

    @AfterEach
    void destroy() throws IOException {
        if (storage != null) {
            storage.close();
        }
        FileUtil.deleteDir(testDir);
    }

    @Test
    public void testPrepareSealsEveryLocationTheCopyWillReach() throws InterruptedException {
        put("public/rules/rules", "{}");
        put("public/deployments/app1/files/source.py", "print(1)");

        assertEquals(Set.of("public/", "public/deployments/app1/"), migration.prepare("public/"));

        Mockito.verify(states).seal("public/");
        Mockito.verify(states).seal("public/deployments/app1/");
    }

    @Test
    public void testPrepareSealsBeforeDrainingAndWaitsBetween() throws InterruptedException {
        put("Users/u1/conversations/chat", "{}");

        migration.prepare("Users/u1/");

        // A drain that ran before the seal had propagated would be a drain of a bucket still taking writes.
        InOrder order = Mockito.inOrder(states, resources);
        order.verify(states).seal("Users/u1/");
        order.verify(resources).flushBucket("Users/u1/");
        assertEquals(List.of(WINDOW), waited);
    }

    @Test
    public void testCopyRefusesWhenTheBucketIsNotSealed() {
        put("Users/u1/conversations/chat", "{}");
        Mockito.when(states.resolve("Users/u1/")).thenReturn(BucketMigrationState.LEGACY);

        IllegalStateException error = assertThrows(IllegalStateException.class, () -> migration.copy("Users/u1/"));
        assertTrue(error.getMessage().contains("Seal Users/u1/ before copying it"), error.getMessage());
    }

    @Test
    public void testCopyRefusesWhenItReachesAnUnsealedLocation() {
        put("public/rules/rules", "{}");
        put("public/deployments/app1/files/source.py", "print(1)");
        // The sub-bucket appeared after the seal, so the copy carries bytes nobody stopped writing to.
        Mockito.when(states.resolve("public/deployments/app1/")).thenReturn(BucketMigrationState.LEGACY);

        IllegalStateException error = assertThrows(IllegalStateException.class, () -> migration.copy("public/"));
        assertTrue(error.getMessage().contains("reached public/deployments/app1/"), error.getMessage());
    }

    @Test
    public void testFinishPromotesEveryCoveredLocationAndWaits() throws InterruptedException {
        put("public/rules/rules", "{}");
        put("public/deployments/app1/files/source.py", "print(1)");

        assertEquals(Set.of("public/", "public/deployments/app1/"), migration.finish("public/"));

        Mockito.verify(states).promote("public/");
        Mockito.verify(states).promote("public/deployments/app1/");
        assertEquals(List.of(WINDOW), waited);
    }

    @Test
    public void testRevertSealsFirstAndWaitsOnBothSides() throws InterruptedException {
        put("Users/u1/conversations/chat", "{}");

        migration.revert("Users/u1/");

        InOrder order = Mockito.inOrder(states);
        order.verify(states).seal("Users/u1/");
        order.verify(states).revert("Users/u1/");
        assertEquals(List.of(WINDOW, WINDOW), waited);
    }

    @Test
    public void testMigrateRunsTheWholeSequence() throws InterruptedException {
        put("Users/u1/conversations/chat", "{}");

        BucketMigrator.Result result = migration.migrate("Users/u1/");

        assertEquals(1, result.objects());
        InOrder order = Mockito.inOrder(states, resources);
        order.verify(states).seal("Users/u1/");
        order.verify(resources).flushBucket("Users/u1/");
        order.verify(states).promote("Users/u1/");
        assertEquals(List.of(WINDOW, WINDOW), waited);
    }

    private void put(String path, String body) {
        storage.store(path, "application/json", null, Map.of("author", "u1"), body.getBytes());
    }
}
