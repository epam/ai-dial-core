package com.epam.aidial.core.server.service.dependency;

import com.epam.aidial.core.config.Config;
import com.epam.aidial.core.server.config.InvalidEntityRecord;
import com.epam.aidial.core.server.config.MergedConfigStore;
import com.epam.aidial.core.server.security.ApiKeyStore;
import com.epam.aidial.core.storage.resource.ResourceTypes;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ConfigSnapshotReaderTest {

    @Test
    void testReadWaitsForConfigurationPublicationThatHoldsTheLock() throws Exception {
        ReentrantLock publicationLock = new ReentrantLock();
        ConfigSnapshotReader reader = new ConfigSnapshotReader(publicationLock, Map::of, Config::new, () -> null);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        publicationLock.lock();
        try {
            Future<ConfigSnapshot> read = executor.submit(reader::get);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (!publicationLock.hasQueuedThreads() && System.nanoTime() < deadline) {
                Thread.sleep(1);
            }

            assertTrue(publicationLock.hasQueuedThreads(), "the read must queue on the publication lock");
            assertFalse(read.isDone(), "the read must not answer while a publication holds the lock");

            publicationLock.unlock();
            assertNotNull(read.get(10, TimeUnit.SECONDS));
        } finally {
            if (publicationLock.isHeldByCurrentThread()) {
                publicationLock.unlock();
            }
            executor.shutdownNow();
        }
        assertFalse(publicationLock.isLocked());
    }

    @Test
    void testLockIsHeldForTheTwoConfigurationReadsOnly() {
        ReentrantLock publicationLock = new ReentrantLock();
        List<String> held = new ArrayList<>();
        ConfigSnapshotReader reader = new ConfigSnapshotReader(publicationLock,
                () -> {
                    held.add("invalid=" + publicationLock.isHeldByCurrentThread());
                    return Map.of();
                },
                () -> {
                    held.add("merged=" + publicationLock.isHeldByCurrentThread());
                    return new Config();
                },
                () -> {
                    held.add("file=" + publicationLock.isHeldByCurrentThread());
                    return null;
                });

        ConfigSnapshot snapshot = reader.get();

        assertEquals(List.of("invalid=true", "merged=true", "file=false"), held);
        assertNull(snapshot.file(), "the store has no file configuration");
        assertFalse(publicationLock.isLocked());
    }

    @Test
    void testStoresAreTheSourcesAndTheMutationLockOfTheKeyStoreIsThePublicationLock() {
        ApiKeyStore apiKeyStore = mock(ApiKeyStore.class);
        MergedConfigStore store = mock(MergedConfigStore.class);
        ReentrantLock mutationLock = new ReentrantLock();
        Config merged = new Config();
        Config file = new Config();
        Map<ResourceTypes, Map<String, InvalidEntityRecord>> invalidEntities = Map.of();
        when(apiKeyStore.getMutationLock()).thenReturn(mutationLock);
        when(store.getFileSourcedConfig()).thenReturn(file);
        when(store.getInvalidEntities()).thenReturn(invalidEntities);
        List<Boolean> heldWhileMergedIsRead = new ArrayList<>();
        when(store.get()).thenAnswer(invocation -> {
            heldWhileMergedIsRead.add(mutationLock.isHeldByCurrentThread());
            return merged;
        });

        ConfigSnapshot snapshot = new ConfigSnapshotReader(apiKeyStore, store).get();

        assertSame(merged, snapshot.merged());
        assertSame(file, snapshot.file());
        assertSame(invalidEntities, snapshot.invalidEntities());
        assertEquals(List.of(true), heldWhileMergedIsRead, "the merged configuration is read under the lock of the key store");
    }
}
