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

    @Test
    public void testMigratingBucketTwiceIsRefusedSoLiveDataSurvives() throws InterruptedException {
        useRealTransitions();
        put("Users/u1/conversations/chat", "before the move");

        migration.migrate("Users/u1/");
        assertEquals("before the move", body(".org/acme/.users/u1/.conversations/chat"));

        // Everything written once the bucket is migrated goes to the tenant tree.
        put(".org/acme/.users/u1/.conversations/chat", "written after the move");

        // Refused rather than repeated: a second copy would put the legacy tree back over the live one.
        assertThrows(IllegalStateException.class, () -> migration.migrate("Users/u1/"));

        assertEquals("written after the move", body(".org/acme/.users/u1/.conversations/chat"),
                "a second migration copied the stale legacy tree over live data");
    }

    @Test
    public void testMigrateIsRefusedWhenOneSubBucketHasAlreadyMoved() {
        Map<String, BucketMigrationState> actual = useRealTransitions();
        put("public/rules/rules", "{}");
        put("public/deployments/app1/files/source.py", "print(1)");
        // The sub-bucket was migrated on its own first. Its legacy tree is now stale.
        actual.put("public/deployments/app1/", BucketMigrationState.MIGRATED);

        assertThrows(IllegalStateException.class, () -> migration.migrate("public/"));

        // Refused as a precondition: had public/ been sealed first, the sub-bucket would have been walked
        // back to MIGRATING with it and copied over.
        assertEquals(Map.of("public/deployments/app1/", BucketMigrationState.MIGRATED), actual);
        Mockito.verify(states, Mockito.never()).seal(Mockito.anyString());
        assertEquals(List.of(), waited);
    }

    @Test
    public void testMigrateSkipsTheRollbackWhenNothingWasSealed() {
        Map<String, BucketMigrationState> actual = useRealTransitions();
        put("Users/u1/conversations/chat", "{}");
        Mockito.doThrow(new IllegalStateException("the document would not write"))
                .when(states).seal("Users/u1/");

        assertThrows(IllegalStateException.class, () -> migration.migrate("Users/u1/"));

        // Nothing moved, so there is nothing to undo — and undoing it would have cost two propagation
        // windows of refused writes on a bucket the failure never touched.
        assertEquals(Map.of(), actual);
        Mockito.verify(states, Mockito.never()).revert(Mockito.anyString());
        assertEquals(List.of(), waited);
    }

    @Test
    public void testMigrateKeepsTheInterruptWhenTheCleanupIsInterrupted() {
        useRealTransitions();
        put("Users/u1/conversations/chat", "{}");
        BucketMigrator failing = Mockito.mock(BucketMigrator.class);
        Mockito.when(failing.locations("Users/u1/")).thenReturn(Set.of("Users/u1/"));
        Mockito.when(failing.copyBucket("Users/u1/")).thenThrow(new IllegalStateException("copy died"));
        // The first wait is prepare's; the rollback's is where the interrupt lands.
        BucketMigration.Delay interruptedOnRollback = new BucketMigration.Delay() {
            private int waits;

            @Override
            public void await(long millis) throws InterruptedException {
                if (++waits > 1) {
                    throw new InterruptedException("stop");
                }
            }
        };
        BucketMigration migrating = new BucketMigration(states, failing, resources, interruptedOnRollback);

        try {
            IllegalStateException error = assertThrows(IllegalStateException.class,
                    () -> migrating.migrate("Users/u1/"));

            assertEquals("copy died", error.getMessage());
            assertTrue(Thread.currentThread().isInterrupted(),
                    "an interrupt swallowed into a suppressed exception is an interrupt the caller never sees");
        } finally {
            assertTrue(Thread.interrupted());
        }
    }

    @Test
    public void testCompleteIsRefusedWhileSomeBucketInTheStoreIsNotMigrated() {
        Map<String, BucketMigrationState> actual = useRealTransitions();
        put("Users/u1/conversations/chat", "{}");
        put("Users/u2/conversations/chat", "{}");
        actual.put("Users/u1/", BucketMigrationState.MIGRATED);

        // u2 is in the store and has never been touched: the document cannot know about it, only a walk of
        // the store can. Declaring the store migrated would send u2's reads to an empty tenant tree.
        IllegalStateException error = assertThrows(IllegalStateException.class, () -> migration.complete());
        assertTrue(error.getMessage().contains("Users/u2/"), error.getMessage());
        Mockito.verify(states, Mockito.never()).complete();
    }

    @Test
    public void testCompleteCompactsOnceEveryBucketHasMoved() {
        Map<String, BucketMigrationState> actual = useRealTransitions();
        put("Users/u1/conversations/chat", "{}");
        put("public/rules/rules", "{}");
        put("public/deployments/app1/files/source.py", "print(1)");
        // The tenant tree the copies produced, and the state document itself, are not buckets to migrate.
        put(".org/acme/.users/u1/.conversations/chat", "{}");
        put(".dial-migration/bucket-states.json", "{}");
        for (String location : List.of("Users/u1/", "public/", "public/deployments/app1/")) {
            actual.put(location, BucketMigrationState.MIGRATED);
        }

        migration.complete();

        Mockito.verify(states).complete();
    }

    @Test
    public void testMigrateUnsealsWhenTheDrainFails() {
        Map<String, BucketMigrationState> actual = useRealTransitions();
        put("Users/u1/conversations/chat", "{}");
        Mockito.doThrow(new IllegalStateException("redis is unwell"))
                .when(resources).flushBucket("Users/u1/");

        assertThrows(IllegalStateException.class, () -> migration.migrate("Users/u1/"));

        // The drain is inside prepare, which used to sit outside the cleanup: a failure there left the
        // bucket sealed and refusing writes with nothing to end it.
        assertEquals(BucketMigrationState.LEGACY, actual.get("Users/u1/"));
    }

    @Test
    public void testMigrateKeepsTheOriginalFailureWhenTheCleanupAlsoFails() {
        useRealTransitions();
        put("Users/u1/conversations/chat", "{}");
        BucketMigrator failing = Mockito.mock(BucketMigrator.class);
        Mockito.when(failing.locations("Users/u1/")).thenReturn(Set.of("Users/u1/"));
        Mockito.when(failing.copyBucket("Users/u1/")).thenThrow(new IllegalStateException("copy died"));
        // And the rollback that follows fails too.
        Mockito.doThrow(new IllegalStateException("and so did the rollback"))
                .when(states).revert(Mockito.anyString());
        BucketMigration migrating = new BucketMigration(states, failing, resources, waited::add);

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> migrating.migrate("Users/u1/"));

        // Whoever reads this needs the failure that started it, not the one that happened while tidying up.
        assertEquals("copy died", error.getMessage());
        assertEquals("and so did the rollback", error.getSuppressed()[0].getMessage());
    }

    private String body(String path) {
        org.jclouds.blobstore.domain.Blob blob = storage.load(path);
        if (blob == null) {
            throw new AssertionError("Nothing at " + path);
        }
        try (java.io.InputStream stream = blob.getPayload().openStream()) {
            return new String(stream.readAllBytes());
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    private void put(String path, String body) {
        storage.store(path, "application/json", null, Map.of("author", "u1"), body.getBytes());
    }
}
