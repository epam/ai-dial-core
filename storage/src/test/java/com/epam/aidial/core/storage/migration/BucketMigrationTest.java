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
import java.util.HashMap;
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

    /**
     * Makes the mocked registry behave like the real one: state per location, and transitions checked with
     * the production rule rather than with whatever a stub was told to allow. Re-runnability cannot be
     * judged against a registry that accepts every call.
     */
    private Map<String, BucketMigrationState> useRealTransitions() {
        Map<String, BucketMigrationState> actual = new HashMap<>();
        Mockito.when(states.resolve(Mockito.anyString()))
                .thenAnswer(call -> actual.getOrDefault(call.getArgument(0), BucketMigrationState.LEGACY));
        Mockito.doAnswer(call -> transition(actual, call.getArgument(0), BucketMigrationState.MIGRATING))
                .when(states).seal(Mockito.anyString());
        Mockito.doAnswer(call -> transition(actual, call.getArgument(0), BucketMigrationState.MIGRATED))
                .when(states).promote(Mockito.anyString());
        Mockito.doAnswer(call -> transition(actual, call.getArgument(0), BucketMigrationState.LEGACY))
                .when(states).revert(Mockito.anyString());
        return actual;
    }

    /**
     * Mirrors {@link BucketMigrationRegistry}'s own transition, including its tolerance of a step that has
     * already taken effect. {@code BucketMigrationRegistryTest} pins that against the real registry — this
     * one only has to agree with it.
     */
    private static Object transition(Map<String, BucketMigrationState> actual, String location, BucketMigrationState next) {
        BucketMigrationState current = actual.getOrDefault(location, BucketMigrationState.LEGACY);
        if (current == next) {
            return null;
        }

        if (!current.canTransitionTo(next)) {
            throw new IllegalStateException("Bucket %s cannot go from %s to %s".formatted(location, current, next));
        }
        actual.put(location, next);
        return null;
    }

    @Test
    public void testPrepareCanBeRerunAfterPartialFailure() throws InterruptedException {
        Map<String, BucketMigrationState> actual = useRealTransitions();
        put("Users/u1/conversations/chat", "{}");

        migration.prepare("Users/u1/");
        // A prepare that died after sealing some of its locations has to be retryable, or a half-sealed
        // bucket can only be finished by hand.
        migration.prepare("Users/u1/");

        assertEquals(BucketMigrationState.MIGRATING, actual.get("Users/u1/"));
    }

    @Test
    public void testFinishCanBeRerunAfterPartialFailure() throws InterruptedException {
        Map<String, BucketMigrationState> actual = useRealTransitions();
        put("Users/u1/conversations/chat", "{}");
        migration.prepare("Users/u1/");
        migration.finish("Users/u1/");

        migration.finish("Users/u1/");

        assertEquals(BucketMigrationState.MIGRATED, actual.get("Users/u1/"));
    }

    @Test
    public void testPromotingUnsealedBucketIsStillRefused() {
        useRealTransitions();
        put("Users/u1/conversations/chat", "{}");

        // Idempotence is about repeating a step, not about skipping one.
        assertThrows(IllegalStateException.class, () -> migration.finish("Users/u1/"));
    }

    @Test
    public void testMigrateUnsealsTheBucketWhenTheCopyFails() {
        Map<String, BucketMigrationState> actual = useRealTransitions();
        BucketMigrator failing = Mockito.mock(BucketMigrator.class);
        Mockito.when(failing.locations("Users/u1/")).thenReturn(Set.of("Users/u1/"));
        Mockito.when(failing.copyBucket("Users/u1/")).thenThrow(new IllegalStateException("copy died"));
        BucketMigration migrating = new BucketMigration(states, failing, resources, waited::add);

        assertThrows(IllegalStateException.class, () -> migrating.migrate("Users/u1/"));

        // Left sealed, the bucket would answer 503 to every write until an operator noticed.
        assertEquals(BucketMigrationState.LEGACY, actual.get("Users/u1/"));
    }

    private void put(String path, String body) {
        storage.store(path, "application/json", null, Map.of("author", "u1"), body.getBytes());
    }
}
