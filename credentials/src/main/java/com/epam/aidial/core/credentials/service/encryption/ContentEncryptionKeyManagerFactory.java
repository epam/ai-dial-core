package com.epam.aidial.core.credentials.service.encryption;

import com.epam.aidial.core.credentials.service.encryption.keymanagement.KeyManagementService;
import com.epam.aidial.core.storage.service.ResourceService;
import io.vertx.core.json.JsonObject;
import lombok.experimental.UtilityClass;

@UtilityClass
public class ContentEncryptionKeyManagerFactory {

    public ContentEncryptionKeyManager create(
            ResourceService resourceService,
            ContentEncryptionKeyGenerator contentEncryptionKeyGenerator,
            KeyManagementService keyManagementService,
            JsonObject toolsetSettings) {

        JsonObject security = toolsetSettings != null ? toolsetSettings.getJsonObject("security") : null;
        JsonObject kmsSettings = security != null ? security.getJsonObject("kms") : null;
        JsonObject cacheSettings = kmsSettings != null ? kmsSettings.getJsonObject("cache") : null;

        Long maxSize = cacheSettings != null ? cacheSettings.getLong("maxSize") : null;
        Long expiration = cacheSettings != null ? cacheSettings.getLong("expiration") : null;

        maxSize = maxSize != null ? maxSize : 10_000;
        expiration = expiration != null ? expiration : 600_000;

        ContentEncryptionKeyManager contentEncryptionKeyManager = new ContentEncryptionKeyManagerImpl(resourceService,
                contentEncryptionKeyGenerator, keyManagementService);
        return new CachedContentEncryptionKeyManager(contentEncryptionKeyManager, maxSize, expiration);
    }

}
