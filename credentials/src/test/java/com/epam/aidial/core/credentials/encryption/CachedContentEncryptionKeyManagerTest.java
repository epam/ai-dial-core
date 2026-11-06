package com.epam.aidial.core.credentials.encryption;

import com.epam.aidial.core.storage.resource.ResourceDescriptor;
import com.epam.aidial.core.storage.resource.ResourceTypes;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class CachedContentEncryptionKeyManagerTest {

    private static final ResourceDescriptor CEK =
            new ResourceDescriptor(ResourceTypes.ENCRYPTION_KEYS, "cek", List.of(), "public", "public/", false);

    @Test
    void invalidate_makesTheNextReadGoToTheDelegateAgain() {
        AtomicInteger reads = new AtomicInteger();
        AtomicInteger invalidations = new AtomicInteger();
        ContentEncryptionKeyManager delegate = new ContentEncryptionKeyManager() {
            @Override
            public byte[] getOrCreateKey(ResourceDescriptor cekDescriptor) {
                return new byte[] {(byte) reads.incrementAndGet()};
            }

            @Override
            public void invalidate(ResourceDescriptor cekDescriptor) {
                invalidations.incrementAndGet();
            }
        };
        CachedContentEncryptionKeyManager cached = new CachedContentEncryptionKeyManager(delegate, 10, 60_000);

        assertArrayEquals(new byte[] {1}, cached.getOrCreateKey(CEK));
        assertArrayEquals(new byte[] {1}, cached.getOrCreateKey(CEK));
        assertEquals(1, reads.get(), "the second read comes from the cache");

        cached.invalidate(CEK);

        assertArrayEquals(new byte[] {2}, cached.getOrCreateKey(CEK));
        assertEquals(1, invalidations.get(), "the invalidation reaches the wrapped manager too");
    }
}
