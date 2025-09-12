package com.epam.aidial.core.server.util;

import com.epam.aidial.core.config.CredentialsLevel;
import com.epam.aidial.core.credentials.data.credentials.BucketInfo;
import com.epam.aidial.core.credentials.data.credentials.CredentialsDescriptor;
import com.epam.aidial.core.server.security.EncryptionService;
import com.epam.aidial.core.storage.resource.ResourceDescriptor;
import lombok.experimental.UtilityClass;

import java.util.Objects;

@UtilityClass
public class CredentialsDescriptorFactory {

    public static CredentialsDescriptor fromAnyUrl(
            String resourceId,
            String userSub,
            CredentialsLevel credentialsLevel,
            EncryptionService encryption
    ) {
        ResourceDescriptor resourceDescriptor = null;
        try {
            resourceDescriptor = ResourceDescriptorFactory.fromAnyUrl(resourceId, encryption);
        } catch (RuntimeException ignored) {
            // resource might be static, resourceDescriptor remains null
        }

        BucketInfo bucketInfo = resolveBucketInfo(credentialsLevel, resourceDescriptor, resourceId, userSub, encryption);
        return new CredentialsDescriptor(resourceId, bucketInfo.name(), bucketInfo.location());
    }

    private BucketInfo resolveBucketInfo(
            CredentialsLevel credentialsLevel,
            ResourceDescriptor resourceDescriptor,
            String resourceId,
            String userSub,
            EncryptionService encryption
    ) {
        BucketInfo globalLocation = getPublicBucketInfo();
        BucketInfo userLocation = getUserBucketInfo(userSub, encryption);

        switch (credentialsLevel) {
            case GLOBAL -> {
                if (resourceDescriptor == null || resourceDescriptor.isPublic()) {
                    return globalLocation;
                }
                if (Objects.equals(resourceDescriptor.getBucketLocation(), userLocation.location())) {
                    return userLocation;
                }
                throw new IllegalArgumentException(
                        "Cannot modify global credentials of other users: " + resourceId
                );
            }
            case USER -> {
                return userLocation;
            }
            default -> throw new IllegalArgumentException("Invalid credentials level: " + credentialsLevel);
        }
    }

    public static BucketInfo getUserBucketInfo(String userSub, EncryptionService encryption) {
        String userBucketLocation = BucketBuilder.USER_BUCKET_PATTERN.formatted(userSub);
        String userBucketName = encryption.encrypt(userBucketLocation);
        return new BucketInfo(userBucketLocation, userBucketName);
    }

    public static BucketInfo getPublicBucketInfo() {
        return new BucketInfo(ResourceDescriptor.PUBLIC_LOCATION, ResourceDescriptor.PUBLIC_BUCKET);
    }

}
