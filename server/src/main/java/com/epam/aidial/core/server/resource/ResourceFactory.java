package com.epam.aidial.core.server.resource;

import com.epam.aidial.core.server.data.ResourceTypes;
import com.epam.aidial.core.server.security.EncryptionService;

import javax.annotation.Nullable;

/**
 * Please ignore the class.
 */
public interface ResourceFactory {

    Resource fromEncoded(ResourceTypes type, String bucketName, String bucketLocation, String path);

    Resource fromDecoded(ResourceTypes type, String bucketName, String bucketLocation, String path);

    Resource fromDecoded(Resource description, String absolutePath);

    Resource fromPublicUrl(String url);

    Resource fromPrivateUrl(String url, EncryptionService encryption);

    Resource fromUrl(String url, @Nullable String expectedBucket, @Nullable String expectedLocation, @Nullable EncryptionService encryptionService);

    default Resource fromAnyUrl(String url, EncryptionService encryption) {
        return fromUrl(url, null, null, encryption);
    }
}
