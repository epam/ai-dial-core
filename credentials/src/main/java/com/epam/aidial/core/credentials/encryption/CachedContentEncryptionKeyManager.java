package com.epam.aidial.core.credentials.encryption;

import com.epam.aidial.core.storage.resource.ResourceDescriptor;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import lombok.RequiredArgsConstructor;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

/**
 * A {@link ContentEncryptionKeyManager} decorator that caches decrypted
 * Content Encryption Keys (CEKs) for a configurable amount of time and size limit.
 *
 * <p>This avoids repeated Key Management Service (KMS) calls, which can be costly,
 * by storing CEKs temporarily in memory. Each CEK is fetched or created
 * via the underlying {@link ContentEncryptionKeyManager} if not present in the cache.
 */
@RequiredArgsConstructor
public class CachedContentEncryptionKeyManager implements ContentEncryptionKeyManager {

    private final ContentEncryptionKeyManager contentEncryptionKeyManager;

    private final Cache<ResourceDescriptor, byte[]> cekCache;

    public CachedContentEncryptionKeyManager(
            ContentEncryptionKeyManager contentEncryptionKeyManager,
            long maxSize,
            long expiration
    ) {
        this.contentEncryptionKeyManager = contentEncryptionKeyManager;
        this.cekCache = CacheBuilder.newBuilder()
                .maximumSize(maxSize)
                .expireAfterAccess(expiration, TimeUnit.MILLISECONDS)
                .build();
    }

    @Override
    public byte[] getOrCreateKey(ResourceDescriptor cekDescriptor) {
        try {
            return cekCache.get(cekDescriptor, () -> contentEncryptionKeyManager.getOrCreateKey(cekDescriptor));
        } catch (ExecutionException e) {
            throw new RuntimeException("Failed to getOrCreateKey for " + cekDescriptor, e);
        }
    }

}
