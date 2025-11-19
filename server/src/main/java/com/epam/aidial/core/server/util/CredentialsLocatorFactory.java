package com.epam.aidial.core.server.util;

import com.epam.aidial.core.config.CredentialsLevel;
import com.epam.aidial.core.credentials.data.credentials.BucketInfo;
import com.epam.aidial.core.credentials.data.credentials.CredentialsLocator;
import com.epam.aidial.core.server.Proxy;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.storage.resource.ResourceDescriptor;
import lombok.experimental.UtilityClass;

import java.util.HashMap;
import java.util.Map;

@UtilityClass
public class CredentialsLocatorFactory {

    public static CredentialsLocator fromAnyUrl(
            String resourceIdEncoded,
            ProxyContext proxyContext
    ) {
        ResourceDescriptor resourceDescriptor = null;
        try {
            Proxy proxy = proxyContext.getProxy();
            resourceDescriptor = ResourceDescriptorFactory.fromAnyUrl(resourceIdEncoded, proxy.getEncryptionService());
        } catch (IllegalArgumentException ignored) {
            // resource might be static, resourceDescriptor remains null
        }

        Map<CredentialsLevel, BucketInfo> bucketInfo = resolveBucketInfo(resourceDescriptor, proxyContext);

        return new CredentialsLocator(resourceIdEncoded, bucketInfo);
    }

    private static Map<CredentialsLevel, BucketInfo> resolveBucketInfo(
            ResourceDescriptor resourceDescriptor,
            ProxyContext proxyContext
    ) {
        Map<CredentialsLevel, BucketInfo> bucketInfo = new HashMap<>();

        BucketInfo userBucketInfo = CredentialsDescriptorFactory.getUserBucketInfo(proxyContext);
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
