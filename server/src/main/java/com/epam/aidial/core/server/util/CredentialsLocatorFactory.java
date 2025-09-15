package com.epam.aidial.core.server.util;

import com.epam.aidial.core.config.CredentialsLevel;
import com.epam.aidial.core.credentials.data.credentials.BucketInfo;
import com.epam.aidial.core.credentials.data.credentials.CredentialsLocator;
import com.epam.aidial.core.server.security.EncryptionService;
import com.epam.aidial.core.storage.resource.ResourceDescriptor;
import lombok.experimental.UtilityClass;

import java.util.HashMap;
import java.util.Map;

@UtilityClass
public class CredentialsLocatorFactory {

    public static CredentialsLocator fromAnyUrl(
            String resourceId,
            String userSub,
            EncryptionService encryption
    ) {
        ResourceDescriptor resourceDescriptor = null;
        try {
            resourceDescriptor = ResourceDescriptorFactory.fromAnyUrl(resourceId, encryption);
        } catch (RuntimeException ignored) {
            // resource might be static, resourceDescriptor remains null
        }

        Map<CredentialsLevel, BucketInfo> bucketInfo = resolveBucketInfo(resourceDescriptor, userSub, encryption);

        return new CredentialsLocator(resourceId, bucketInfo);
    }

    private static Map<CredentialsLevel, BucketInfo> resolveBucketInfo(
            ResourceDescriptor resourceDescriptor,
            String userSub,
            EncryptionService encryption
    ) {
        Map<CredentialsLevel, BucketInfo> bucketInfo = new HashMap<>();

        BucketInfo userBucketInfo = CredentialsDescriptorFactory.getUserBucketInfo(userSub, encryption);
        bucketInfo.put(CredentialsLevel.USER, userBucketInfo);

        if (resourceDescriptor != null) {
            bucketInfo.put(CredentialsLevel.GLOBAL,
                    new BucketInfo(resourceDescriptor.getBucketName(), resourceDescriptor.getBucketLocation()));
        } else {
            bucketInfo.put(CredentialsLevel.GLOBAL, CredentialsDescriptorFactory.getPublicBucketInfo());
        }

        return bucketInfo;
    }
}
