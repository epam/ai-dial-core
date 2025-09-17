package com.epam.aidial.core.server.util;

import com.epam.aidial.core.config.CredentialsLevel;
import com.epam.aidial.core.credentials.data.credentials.BucketInfo;
import com.epam.aidial.core.credentials.data.credentials.CredentialsDescriptor;
import com.epam.aidial.core.server.Proxy;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.data.AuthBucket;
import com.epam.aidial.core.storage.resource.ResourceDescriptor;
import lombok.experimental.UtilityClass;

import java.util.Objects;

@UtilityClass
public class CredentialsDescriptorFactory {

    public static CredentialsDescriptor fromAnyUrl(
            String resourceId,
            CredentialsLevel credentialsLevel,
            ProxyContext proxyContext
    ) {
        ResourceDescriptor resourceDescriptor = null;
        try {
            Proxy proxy = proxyContext.getProxy();
            resourceDescriptor = ResourceDescriptorFactory.fromAnyUrl(resourceId, proxy.getEncryptionService());
        } catch (IllegalArgumentException ignored) {
            // resource might be static, resourceDescriptor remains null
        }

        BucketInfo bucketInfo = resolveBucketInfo(credentialsLevel, resourceDescriptor, resourceId, proxyContext);
        return new CredentialsDescriptor(resourceId, bucketInfo.name(), bucketInfo.location());
    }

    private BucketInfo resolveBucketInfo(
            CredentialsLevel credentialsLevel,
            ResourceDescriptor resourceDescriptor,
            String resourceId,
            ProxyContext proxyContext
    ) {
        BucketInfo globalLocation = getPublicBucketInfo();
        BucketInfo userLocation = getUserBucketInfo(proxyContext);

        switch (credentialsLevel) {
            case GLOBAL -> {
                if (resourceDescriptor == null || resourceDescriptor.isPublic()) {
                    return globalLocation;
                }
                if (Objects.equals(resourceDescriptor.getBucketLocation(), userLocation.location())) {
                    return userLocation;
                }
                throw new IllegalArgumentException(
                        "Cannot modify global credentials of other users: " + resourceId);
            }
            case USER -> {
                return userLocation;
            }
            default -> throw new IllegalArgumentException("Invalid credentials level: " + credentialsLevel);
        }
    }

    public static BucketInfo getUserBucketInfo(ProxyContext proxyContext) {
        AuthBucket authBucket = BucketBuilder.buildBucket(proxyContext);
        return new BucketInfo(authBucket.getUserBucket(), authBucket.getUserBucketLocation());
    }

    public static BucketInfo getPublicBucketInfo() {
        return new BucketInfo(ResourceDescriptor.PUBLIC_BUCKET, ResourceDescriptor.PUBLIC_LOCATION);
    }

}
