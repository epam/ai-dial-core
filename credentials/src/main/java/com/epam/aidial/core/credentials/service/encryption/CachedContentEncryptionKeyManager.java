package com.epam.aidial.core.credentials.service.encryption;

import com.epam.aidial.core.storage.resource.ResourceDescriptor;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.RequiredArgsConstructor;

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
        this.cekCache = Caffeine.newBuilder()
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
        return cekCache.get(cekDescriptor, contentEncryptionKeyManager::getOrCreateKey);
    }

    @Override
    public byte[] getKey(ResourceDescriptor cekDescriptor) {
        return cekCache.get(cekDescriptor, contentEncryptionKeyManager::getKey);
    }

}
