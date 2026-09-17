package com.epam.aidial.core.storage.migration;

import com.epam.aidial.core.storage.FileUtil;
import com.epam.aidial.core.storage.blobstore.BlobStorage;
import com.epam.aidial.core.storage.blobstore.Storage;
import com.epam.aidial.core.storage.service.ResourceService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * What every migration operation does from every state, declared as a table.
 *
 * <p>The bugs this exists to catch do not live inside an operation; they live in a combination nobody wrote
 * down. Re-running a migration on a bucket that had already moved restored its pre-migration contents over
 * live data, and the reason it was possible is that the cell for that pair — promote-then-prepare — had never
 * been stated, so nothing disagreed with the code.
 *
 * <p>Every state and operation must appear, and a cell that is deliberately not exercised has to say so out
 * loud. An operation added without cells fails the run rather than passing unnoticed.
 */
public class BucketMigrationMatrixTest {

    private static final String TENANT = "acme";
    private static final String BUCKET = "Users/u1/";

    /** Whether the operation is allowed to run at all, not whether it changed anything. */
    private enum Outcome {
        ACCEPTED, REFUSED
    }

    private record Cell(BucketMigrationState from, String operation, Outcome outcome, BucketMigrationState to, String why) {
    }

    private static final List<Cell> MATRIX = List.of(
            // ---- from LEGACY: nothing has moved, so only the steps that begin a migration are available.
            new Cell(BucketMigrationState.LEGACY, "seal", Outcome.ACCEPTED, BucketMigrationState.MIGRATING,
                    "the first step of a migration"),
            new Cell(BucketMigrationState.LEGACY, "promote", Outcome.REFUSED, BucketMigrationState.LEGACY,
                    "nothing has been copied, so there is nothing to resolve to"),
            new Cell(BucketMigrationState.LEGACY, "revert", Outcome.ACCEPTED, BucketMigrationState.LEGACY,
                    "already there; asking again is how an interrupted step resumes"),
            new Cell(BucketMigrationState.LEGACY, "prepare", Outcome.ACCEPTED, BucketMigrationState.MIGRATING,
                    "seals and drains, which is where a migration starts"),
            new Cell(BucketMigrationState.LEGACY, "copy", Outcome.REFUSED, BucketMigrationState.LEGACY,
                    "copying a bucket still taking writes copies a moving target"),
            new Cell(BucketMigrationState.LEGACY, "finish", Outcome.REFUSED, BucketMigrationState.LEGACY,
                    "promoting without a copy points resolution at nothing"),
            new Cell(BucketMigrationState.LEGACY, "rollback", Outcome.ACCEPTED, BucketMigrationState.LEGACY,
                    "a rollback of a bucket that never moved leaves it where it is"),
            new Cell(BucketMigrationState.LEGACY, "migrate", Outcome.ACCEPTED, BucketMigrationState.MIGRATED,
                    "the whole sequence, which is the point of the mechanism"),

            // ---- from MIGRATING: the copy is in flight; every step of it must be repeatable.
            new Cell(BucketMigrationState.MIGRATING, "seal", Outcome.ACCEPTED, BucketMigrationState.MIGRATING,
                    "already sealed; repeating a step is how a partial failure is resumed"),
            new Cell(BucketMigrationState.MIGRATING, "promote", Outcome.ACCEPTED, BucketMigrationState.MIGRATED,
                    "the copy is done"),
            new Cell(BucketMigrationState.MIGRATING, "revert", Outcome.ACCEPTED, BucketMigrationState.LEGACY,
                    "abandoning the move; the legacy tree was never touched"),
            new Cell(BucketMigrationState.MIGRATING, "prepare", Outcome.ACCEPTED, BucketMigrationState.MIGRATING,
                    "resuming a prepare that died part way through its locations"),
            new Cell(BucketMigrationState.MIGRATING, "copy", Outcome.ACCEPTED, BucketMigrationState.MIGRATING,
                    "the copy itself, and it is repeatable"),
            new Cell(BucketMigrationState.MIGRATING, "finish", Outcome.ACCEPTED, BucketMigrationState.MIGRATED,
                    "promotion after the copy"),
            new Cell(BucketMigrationState.MIGRATING, "rollback", Outcome.ACCEPTED, BucketMigrationState.LEGACY,
                    "the drill: stop writes, wait, go back"),
            new Cell(BucketMigrationState.MIGRATING, "migrate", Outcome.ACCEPTED, BucketMigrationState.MIGRATED,
                    "resuming an interrupted migration from wherever it stopped"),

            // ---- from MIGRATED: the bucket has moved and is being written to at its new paths. Anything
            // that would copy the legacy tree again is destructive, because that tree is now stale.
            new Cell(BucketMigrationState.MIGRATED, "seal", Outcome.ACCEPTED, BucketMigrationState.MIGRATING,
                    "the first half of a rollback"),
            new Cell(BucketMigrationState.MIGRATED, "promote", Outcome.ACCEPTED, BucketMigrationState.MIGRATED,
                    "already promoted; repeating a step is how a partial failure is resumed"),
            new Cell(BucketMigrationState.MIGRATED, "revert", Outcome.REFUSED, BucketMigrationState.MIGRATED,
                    "a migrated bucket must be sealed before it goes back, or writes land in two trees"),
            new Cell(BucketMigrationState.MIGRATED, "prepare", Outcome.REFUSED, BucketMigrationState.MIGRATED,
                    "re-sealing sends resolution back to the legacy tree while live writes are in the "
                            + "tenant one, and the drain then matches none of them"),
            new Cell(BucketMigrationState.MIGRATED, "copy", Outcome.REFUSED, BucketMigrationState.MIGRATED,
                    "the legacy tree is stale; copying it again overwrites everything written since"),
            new Cell(BucketMigrationState.MIGRATED, "finish", Outcome.ACCEPTED, BucketMigrationState.MIGRATED,
                    "already finished; repeating a step is how a partial failure is resumed"),
            new Cell(BucketMigrationState.MIGRATED, "rollback", Outcome.ACCEPTED, BucketMigrationState.LEGACY,
                    "the supported way back, which seals before it reverts"),
            new Cell(BucketMigrationState.MIGRATED, "migrate", Outcome.REFUSED, BucketMigrationState.MIGRATED,
                    "same as copy: it would restore the bucket to how it looked before it moved"));

    private BlobStorage storage;
    private Path testDir;
    private BucketMigrationRegistry states;
    private BucketMigration migration;
    private Map<String, BucketMigrationState> actual;

    @BeforeEach
    void init() throws IOException {
        try {
            testDir = FileUtil.baseTestPath(BucketMigrationMatrixTest.class);
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
            storage.store(BUCKET + "conversations/chat", "application/json", null, Map.of(), "{}".getBytes());
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
    public void testEveryStateAndOperationIsDeclared() {
        Set<String> declared = new TreeSet<>();
        for (Cell cell : MATRIX) {
            assertTrue(declared.add(cell.from() + "/" + cell.operation()),
                    "declared twice: " + cell.from() + "/" + cell.operation());
        }

        List<String> missing = new ArrayList<>();
        for (BucketMigrationState state : BucketMigrationState.values()) {
            for (String operation : operations().keySet()) {
                if (!declared.contains(state + "/" + operation)) {
                    missing.add(state + "/" + operation);
                }
            }
        }

        assertEquals(List.of(), missing, "every state and operation needs a cell, so that adding either "
                + "forces a decision about what the combination means");
    }

    @Test
    public void testTheMatrixHolds() {
        List<String> wrong = new ArrayList<>();
        for (Cell cell : MATRIX) {
            String label = cell.from() + "/" + cell.operation();
            reset(cell.from());

            Outcome outcome;
            try {
                operations().get(cell.operation()).run();
                outcome = Outcome.ACCEPTED;
            } catch (IllegalStateException e) {
                outcome = Outcome.REFUSED;
            } catch (Exception e) {
                wrong.add("%s threw %s: %s".formatted(label, e.getClass().getSimpleName(), e.getMessage()));
                continue;
            }

            BucketMigrationState ended = actual.getOrDefault(BUCKET, BucketMigrationState.LEGACY);
            if (outcome != cell.outcome() || ended != cell.to()) {
                wrong.add("%s expected %s ending %s (%s), got %s ending %s"
                        .formatted(label, cell.outcome(), cell.to(), cell.why(), outcome, ended));
            }
        }

        if (!wrong.isEmpty()) {
            fail("%d cell(s) disagree with the code:%n  %s".formatted(wrong.size(), String.join("\n  ", wrong)));
        }
    }

    @FunctionalInterface
    private interface Invocation {
        void run() throws Exception;
    }

    private Map<String, Invocation> operations() {
        Map<String, Invocation> operations = new LinkedHashMap<>();
        operations.put("seal", () -> states.seal(BUCKET));
        operations.put("promote", () -> states.promote(BUCKET));
        operations.put("revert", () -> states.revert(BUCKET));
        operations.put("prepare", () -> migration.prepare(BUCKET));
        operations.put("copy", () -> migration.copy(BUCKET));
        operations.put("finish", () -> migration.finish(BUCKET));
        operations.put("rollback", () -> migration.revert(BUCKET));
        operations.put("migrate", () -> migration.migrate(BUCKET));
        return operations;
    }

    /**
     * Rebuilds the world with the bucket in one state. The registry is a stand-in that applies the real
     * transition rule; the migrator and the store are real, so a copy actually copies.
     */
    private void reset(BucketMigrationState from) {
        actual = new HashMap<>();
        actual.put(BUCKET, from);
        states = Mockito.mock(BucketMigrationRegistry.class);
        Mockito.when(states.propagationWindow()).thenReturn(0L);
        Mockito.when(states.resolve(Mockito.anyString()))
                .thenAnswer(call -> actual.getOrDefault(call.getArgument(0), BucketMigrationState.LEGACY));
        Mockito.doAnswer(call -> transition(call.getArgument(0), BucketMigrationState.MIGRATING))
                .when(states).seal(Mockito.anyString());
        Mockito.doAnswer(call -> transition(call.getArgument(0), BucketMigrationState.MIGRATED))
                .when(states).promote(Mockito.anyString());
        Mockito.doAnswer(call -> transition(call.getArgument(0), BucketMigrationState.LEGACY))
                .when(states).revert(Mockito.anyString());
        migration = new BucketMigration(states, new BucketMigrator(storage, TENANT),
                Mockito.mock(ResourceService.class), millis -> { });
    }

    private Object transition(String location, BucketMigrationState next) {
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
}
