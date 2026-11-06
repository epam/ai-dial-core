package com.epam.aidial.core.credentials.encryption;

import com.epam.aidial.core.credentials.exception.CekEncryptionException;
import com.epam.aidial.core.storage.data.ResourceEvent;
import com.epam.aidial.core.storage.resource.ResourceDescriptor;
import com.epam.aidial.core.storage.resource.ResourceTypes;
import com.epam.aidial.core.storage.service.ResourceService;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.util.concurrent.UncheckedExecutionException;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

/**
 * A {@link ContentEncryptionKeyManager} decorator that caches decrypted
 * Content Encryption Keys (CEKs) for a configurable amount of time and size limit.
 *
 * <p>This avoids repeated Key Management Service (KMS) calls, which can be costly,
 * by storing CEKs temporarily in memory. Each CEK is fetched or created
 * via the underlying {@link ContentEncryptionKeyManager} if not present in the cache.
 *
 * <p>{@link #invalidate} forgets a CEK on every pod: it publishes an update event for the key, and each pod drops its
 * cached copy when it receives one.
 */
public class CachedContentEncryptionKeyManager implements ContentEncryptionKeyManager {

    private static final String KEY_URL_PREFIX = ResourceTypes.ENCRYPTION_KEYS.urlSegment() + "/";

    private final ContentEncryptionKeyManager contentEncryptionKeyManager;
    private final ResourceService resourceService;
    private final Cache<ResourceDescriptor, byte[]> cekCache;

    public CachedContentEncryptionKeyManager(
            ContentEncryptionKeyManager contentEncryptionKeyManager,
            ResourceService resourceService,
            long maxSize,
            long expiration
    ) {
        this.contentEncryptionKeyManager = contentEncryptionKeyManager;
        this.resourceService = resourceService;
        this.cekCache = CacheBuilder.newBuilder()
                .maximumSize(maxSize)
                .expireAfterAccess(expiration, TimeUnit.MILLISECONDS)
                .build();
        resourceService.subscribeAllResources(this::onResourceEvent);
    }

    @Override
    public byte[] getOrCreateKey(ResourceDescriptor cekDescriptor) {
        try {
            return cekCache.get(cekDescriptor, () -> contentEncryptionKeyManager.getOrCreateKey(cekDescriptor));
        } catch (ExecutionException e) {
            throw new RuntimeException("Failed to getOrCreateKey for " + cekDescriptor, e);
        // block for catching and re-throwing CekEncryptionException, which is unchecked Exception and will be handled later
        } catch (UncheckedExecutionException e) {
            if (e.getCause() instanceof CekEncryptionException encryptionException) {
                throw encryptionException;
            }
            throw e;
        }
    }

    @Override
    public void invalidate(ResourceDescriptor cekDescriptor) {
        cekCache.invalidate(cekDescriptor);
        contentEncryptionKeyManager.invalidate(cekDescriptor);
        resourceService.publishUpdate(cekDescriptor);
    }

    private void onResourceEvent(ResourceEvent event) {
        String url = event.getUrl();
        if (url != null && url.startsWith(KEY_URL_PREFIX)) {
            cekCache.asMap().keySet().removeIf(descriptor -> url.equals(descriptor.getUrl()));
        }
    }

}
