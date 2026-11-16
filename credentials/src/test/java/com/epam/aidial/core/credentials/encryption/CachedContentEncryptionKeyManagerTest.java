package com.epam.aidial.core.credentials.encryption;

import com.epam.aidial.core.storage.data.ResourceEvent;
import com.epam.aidial.core.storage.resource.ResourceDescriptor;
import com.epam.aidial.core.storage.resource.ResourceTypes;
import com.epam.aidial.core.storage.service.ResourceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class CachedContentEncryptionKeyManagerTest {

    private static final ResourceDescriptor CEK =
            new ResourceDescriptor(ResourceTypes.ENCRYPTION_KEYS, "cek", List.of(), "public", "public/", false);
    private static final ResourceDescriptor OTHER_CEK =
            new ResourceDescriptor(ResourceTypes.ENCRYPTION_KEYS, "cek", List.of(), "platform", "platform/", false);

    private final AtomicInteger reads = new AtomicInteger();
    private final AtomicInteger invalidations = new AtomicInteger();
    private ResourceService resourceService;
    private CachedContentEncryptionKeyManager cached;
    private Consumer<ResourceEvent> subscriber;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
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
        resourceService = mock(ResourceService.class);
        cached = new CachedContentEncryptionKeyManager(delegate, resourceService, 10, 60_000);

        ArgumentCaptor<Consumer<ResourceEvent>> captor = ArgumentCaptor.forClass(Consumer.class);
        verify(resourceService).subscribeAllResources(captor.capture());
        subscriber = captor.getValue();
    }

    @Test
    void invalidate_forgetsTheKeyAndTellsEveryPod() {
        assertArrayEquals(new byte[] {1}, cached.getOrCreateKey(CEK));
        assertArrayEquals(new byte[] {1}, cached.getOrCreateKey(CEK));
        assertEquals(1, reads.get(), "the second read comes from the cache");

        cached.invalidate(CEK);

        assertArrayEquals(new byte[] {2}, cached.getOrCreateKey(CEK));
        assertEquals(1, invalidations.get(), "the invalidation reaches the wrapped manager too");
        verify(resourceService).publishUpdate(CEK);
    }

    @Test
    void eventForTheKey_dropsItFromTheCache() {
        cached.getOrCreateKey(CEK);

        subscriber.accept(new ResourceEvent().setUrl(CEK.getUrl()).setAction(ResourceEvent.Action.UPDATE));

        assertArrayEquals(new byte[] {2}, cached.getOrCreateKey(CEK));
    }

    @Test
    void eventForAnotherResource_keepsTheKey() {
        cached.getOrCreateKey(CEK);

        subscriber.accept(new ResourceEvent().setUrl(OTHER_CEK.getUrl()).setAction(ResourceEvent.Action.UPDATE));
        subscriber.accept(new ResourceEvent().setUrl("models/platform/m1").setAction(ResourceEvent.Action.UPDATE));
        subscriber.accept(new ResourceEvent().setAction(ResourceEvent.Action.UPDATE));

        assertArrayEquals(new byte[] {1}, cached.getOrCreateKey(CEK));
        assertEquals(1, reads.get());
    }
}
