package com.epam.aidial.core.credentials.encryption;

import com.epam.aidial.core.credentials.data.configuration.CacheSettings;
import com.epam.aidial.core.credentials.keymanagement.KeyManagementService;
import com.epam.aidial.core.storage.service.ResourceService;
import jakarta.annotation.Nullable;
import lombok.experimental.UtilityClass;


@UtilityClass
public class ContentEncryptionKeyManagerFactory {

    public ContentEncryptionKeyManager create(
            ResourceService resourceService,
            ContentEncryptionKeyGenerator contentEncryptionKeyGenerator,
            KeyManagementService keyManagementService,
            @Nullable CacheSettings cacheSettings) {

        ContentEncryptionKeyManager baseManager =
                new ContentEncryptionKeyManagerImpl(resourceService, contentEncryptionKeyGenerator, keyManagementService);

        return addCacheIfEnabled(baseManager, cacheSettings);
    }

    private ContentEncryptionKeyManager addCacheIfEnabled(
            ContentEncryptionKeyManager contentEncryptionKeyManager,
            CacheSettings cacheSettings) {

        if (cacheSettings == null) {
            cacheSettings = CacheSettings.builder().build();
        }
        if (cacheSettings.isEnabled()) {
            long maxSize = cacheSettings.getMaxSize();
            long expiration = cacheSettings.getExpiration();
            return new CachedContentEncryptionKeyManager(contentEncryptionKeyManager, maxSize, expiration);
        } else {
            return contentEncryptionKeyManager;
        }
    }

}
