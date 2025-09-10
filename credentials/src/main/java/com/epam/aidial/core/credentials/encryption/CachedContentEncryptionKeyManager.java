package com.epam.aidial.core.credentials.encryption;

import com.epam.aidial.core.storage.resource.ResourceDescriptor;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import lombok.RequiredArgsConstructor;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

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
                .expireAfterWrite(expiration, TimeUnit.MILLISECONDS)
                .build();
    }

    @Override
    public byte[] createKey(ResourceDescriptor cekDescriptor) {
        byte[] cek = contentEncryptionKeyManager.createKey(cekDescriptor);
        cekCache.put(cekDescriptor, cek);
        return cek;
    }

    @Override
    public byte[] getOrCreateKey(ResourceDescriptor cekDescriptor) {
        try {
            return cekCache.get(cekDescriptor, () -> contentEncryptionKeyManager.getOrCreateKey(cekDescriptor));
        } catch (ExecutionException e) {
            throw new RuntimeException("Failed to getOrCreateKey for " + cekDescriptor, e);
        }
    }

    @Override
    public byte[] getKey(ResourceDescriptor cekDescriptor) {
        try {
            return cekCache.get(cekDescriptor, () -> contentEncryptionKeyManager.getKey(cekDescriptor));
        } catch (ExecutionException e) {
            throw new RuntimeException("Failed to getKey for " + cekDescriptor, e);
        }
    }

}
