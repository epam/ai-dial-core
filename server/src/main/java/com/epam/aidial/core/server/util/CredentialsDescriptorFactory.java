package com.epam.aidial.core.server.util;

import com.epam.aidial.core.config.CredentialsLevel;
import com.epam.aidial.core.credentials.data.credentials.BucketInfo;
import com.epam.aidial.core.credentials.data.credentials.CredentialsDescriptor;
import com.epam.aidial.core.server.Proxy;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.data.AuthBucket;
import com.epam.aidial.core.storage.resource.ResourceDescriptor;
import lombok.experimental.UtilityClass;

@UtilityClass
public class CredentialsDescriptorFactory {

    /** Reserved resource id for a user's offline credentials; deliberately not an app-scoped path. */
    public static final String OFFLINE_CREDENTIALS_ID = "offline";

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

        BucketInfo bucketInfo = resolveBucketInfo(credentialsLevel, resourceDescriptor, proxyContext);
        return new CredentialsDescriptor(resourceId, bucketInfo.name(), bucketInfo.location());
    }

    public static CredentialsDescriptor fromResourceDescriptor(
            ResourceDescriptor resourceDescriptor,
            CredentialsLevel credentialsLevel,
            ProxyContext proxyContext
    ) {
        String resourceId = resourceDescriptor.getUrl();
        BucketInfo bucketInfo = resolveBucketInfo(credentialsLevel, resourceDescriptor, proxyContext);
        return new CredentialsDescriptor(resourceId, bucketInfo.name(), bucketInfo.location());
    }

    private BucketInfo resolveBucketInfo(
            CredentialsLevel credentialsLevel,
            ResourceDescriptor resourceDescriptor,
            ProxyContext proxyContext
    ) {
        switch (credentialsLevel) {
            case GLOBAL -> {
                if (resourceDescriptor == null || resourceDescriptor.isPublic()) {
                    return getPublicBucketInfo();
                }
                return new BucketInfo(resourceDescriptor.getBucketName(), resourceDescriptor.getBucketLocation());
            }
            case USER -> {
                return getUserBucketInfo(proxyContext);
            }
            default -> throw new IllegalArgumentException("Invalid credentials level: " + credentialsLevel);
        }
    }

    public static BucketInfo getUserBucketInfo(ProxyContext proxyContext) {
        AuthBucket authBucket = BucketBuilder.buildBucket(proxyContext);
        return new BucketInfo(authBucket.getUserBucket(), authBucket.getUserBucketLocation());
    }

    /**
     * Builds the USER bucket for an arbitrary owner user id (not the caller). Used by the on-behalf-of (OBO)
     * path, where the actor (caller) and the credential owner differ.
     */
    public static BucketInfo getUserBucketInfoForUser(ProxyContext proxyContext, String ownerUserId) {
        String location = BucketBuilder.USER_BUCKET_PATTERN.formatted(ownerUserId);
        String name = proxyContext.getProxy().getEncryptionService().encrypt(location);
        return new BucketInfo(name, location);
    }

    /**
     * The caller's offline credentials. The reserved id sits outside the shape
     * {@code parseExternalServiceScope} requires, so no app-facing endpoint can address it.
     */
    public static CredentialsDescriptor offlineCredentials(ProxyContext proxyContext) {
        BucketInfo bucket = getUserBucketInfo(proxyContext);
        return new CredentialsDescriptor(OFFLINE_CREDENTIALS_ID, bucket.name(), bucket.location());
    }

    /** The same record for an arbitrary owner — the redemption path, where the caller is not the owner. */
    public static CredentialsDescriptor offlineCredentialsForUser(ProxyContext proxyContext, String ownerUserId) {
        BucketInfo bucket = getUserBucketInfoForUser(proxyContext, ownerUserId);
        return new CredentialsDescriptor(OFFLINE_CREDENTIALS_ID, bucket.name(), bucket.location());
    }

    public static BucketInfo getPublicBucketInfo() {
        return new BucketInfo(ResourceDescriptor.PUBLIC_BUCKET, ResourceDescriptor.PUBLIC_LOCATION);
    }

}
