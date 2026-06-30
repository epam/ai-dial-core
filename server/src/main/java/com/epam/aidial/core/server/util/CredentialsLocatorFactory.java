package com.epam.aidial.core.server.util;

import com.epam.aidial.core.config.CredentialsLevel;
import com.epam.aidial.core.credentials.data.credentials.BucketInfo;
import com.epam.aidial.core.credentials.data.credentials.CredentialsLocator;
import com.epam.aidial.core.server.Proxy;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.storage.resource.ResourceDescriptor;
import com.epam.aidial.core.storage.resource.ResourceType;
import com.epam.aidial.core.storage.util.UrlUtil;
import lombok.experimental.UtilityClass;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;

@UtilityClass
public class CredentialsLocatorFactory {

    public static final String EXTERNAL_SERVICES_SEPARATOR = "/external_services/";
    private static final String APPLICATIONS_PREFIX = "applications/";
    private static final String CONFIG_SEGMENT = "config/";

    /**
     * Builds a {@link CredentialsLocator} for an external-service scope id.
     *
     * <p>Accepted scope id shapes (decoded form):
     * <ul>
     *     <li>{@code applications/{configAppName}/external_services/{id}} — static-config app</li>
     *     <li>{@code applications/{bucket}/{path}/external_services/{id}} — dynamic app</li>
     * </ul>
     *
     * <p>For static apps the resource id is normalized to
     * {@code applications/config/{appName}/external_services/{id}} (per §6.3 of the design doc)
     * and the APPLICATION bucket is the public bucket. For dynamic apps the resource id is
     * preserved and the APPLICATION bucket is the owning application's bucket.
     */
    public static CredentialsLocator fromExternalServiceScope(String scopeId, ProxyContext proxyContext) {
        String decoded;
        try {
            decoded = UrlUtil.decodePath(scopeId);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("Invalid external service scope id: " + scopeId);
        }
        // Service ids never contain '/', so the LAST '/external_services/' is the unambiguous delimiter even when
        // the application path itself contains an 'external_services' segment.
        int separatorIdx = decoded.lastIndexOf(EXTERNAL_SERVICES_SEPARATOR);
        if (separatorIdx <= 0 || !decoded.startsWith(APPLICATIONS_PREFIX)) {
            throw new IllegalArgumentException("Invalid external service scope id: " + scopeId);
        }

        String appPart = decoded.substring(APPLICATIONS_PREFIX.length(), separatorIdx);
        String externalServiceId = decoded.substring(separatorIdx + EXTERNAL_SERVICES_SEPARATOR.length());
        if (appPart.isEmpty() || externalServiceId.isEmpty() || externalServiceId.contains("/")) {
            throw new IllegalArgumentException("Invalid external service scope id: " + scopeId);
        }

        Map<CredentialsLevel, BucketInfo> bucketInfo = new EnumMap<>(CredentialsLevel.class);
        bucketInfo.put(CredentialsLevel.USER, CredentialsDescriptorFactory.getUserBucketInfo(proxyContext));

        String resourceId;
        if (proxyContext.getConfig().isDeploymentExists(appPart)) {
            // Static-config app: normalize storage path to applications/config/{appName}/external_services/{id}
            resourceId = APPLICATIONS_PREFIX + CONFIG_SEGMENT
                    + UrlUtil.encodePath(appPart)
                    + EXTERNAL_SERVICES_SEPARATOR
                    + UrlUtil.encodePath(externalServiceId);
            bucketInfo.put(CredentialsLevel.APPLICATION, CredentialsDescriptorFactory.getPublicBucketInfo());
        } else {
            // Dynamic app: resolve owning bucket from the application URL prefix.
            String appUrl = APPLICATIONS_PREFIX + UrlUtil.encodePath(appPart);
            ResourceDescriptor appDescriptor;
            try {
                appDescriptor = ResourceDescriptorFactory.fromAnyUrl(appUrl, proxyContext.getProxy().getEncryptionService());
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("Invalid external service scope id: " + scopeId, e);
            }
            resourceId = APPLICATIONS_PREFIX
                    + UrlUtil.encodePath(appPart)
                    + EXTERNAL_SERVICES_SEPARATOR
                    + UrlUtil.encodePath(externalServiceId);
            bucketInfo.put(CredentialsLevel.APPLICATION,
                    new BucketInfo(appDescriptor.getBucketName(), appDescriptor.getBucketLocation()));
        }

        return new CredentialsLocator(resourceId, bucketInfo);
    }

    /**
     * Builds a USER-only {@link CredentialsLocator} for an external-service scope, resolving the USER
     * bucket from the given {@code ownerSub} rather than the caller. Used by the on-behalf-of (OBO)
     * retrieval path, where the actor (caller) differs from the credential owner. The resource id is
     * normalized identically to {@link #fromExternalServiceScope} so it reads exactly where sign-in wrote.
     * Carries only the USER level, so no APPLICATION/GLOBAL fallback is structurally possible (fail-closed).
     */
    public static CredentialsLocator fromExternalServiceScopeForOwner(String scopeId, String ownerSub, ProxyContext proxyContext) {
        String[] parts = parseExternalServiceScope(scopeId);
        String appPart = parts[0];
        String externalServiceId = parts[1];

        String resourceId;
        if (proxyContext.getConfig().isDeploymentExists(appPart)) {
            resourceId = APPLICATIONS_PREFIX + CONFIG_SEGMENT
                    + UrlUtil.encodePath(appPart)
                    + EXTERNAL_SERVICES_SEPARATOR
                    + UrlUtil.encodePath(externalServiceId);
        } else {
            resourceId = APPLICATIONS_PREFIX
                    + UrlUtil.encodePath(appPart)
                    + EXTERNAL_SERVICES_SEPARATOR
                    + UrlUtil.encodePath(externalServiceId);
        }

        Map<CredentialsLevel, BucketInfo> bucketInfo = new EnumMap<>(CredentialsLevel.class);
        bucketInfo.put(CredentialsLevel.USER, CredentialsDescriptorFactory.getUserBucketInfoForUser(proxyContext, ownerSub));
        return new CredentialsLocator(resourceId, bucketInfo);
    }

    /**
     * Parses an external-service scope id and returns the (decoded app prefix, external-service id) pair.
     * The app prefix is what comes after the leading {@code applications/} segment, e.g. {@code my-app}
     * for static apps or {@code bucket/path} for dynamic apps.
     */
    public static String[] parseExternalServiceScope(String scopeId) {
        String decoded;
        try {
            decoded = UrlUtil.decodePath(scopeId);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("Invalid external service scope id: " + scopeId);
        }
        // See fromExternalServiceScope: the last '/external_services/' is the unambiguous delimiter.
        int separatorIdx = decoded.lastIndexOf(EXTERNAL_SERVICES_SEPARATOR);
        if (separatorIdx <= 0 || !decoded.startsWith(APPLICATIONS_PREFIX)) {
            throw new IllegalArgumentException("Invalid external service scope id: " + scopeId);
        }
        String appPart = decoded.substring(APPLICATIONS_PREFIX.length(), separatorIdx);
        String externalServiceId = decoded.substring(separatorIdx + EXTERNAL_SERVICES_SEPARATOR.length());
        if (appPart.isEmpty() || externalServiceId.isEmpty() || externalServiceId.contains("/")) {
            throw new IllegalArgumentException("Invalid external service scope id: " + scopeId);
        }
        return new String[]{appPart, externalServiceId};
    }

    public static CredentialsLocator fromAnyUrl(
            String resourceIdEncoded,
            ProxyContext proxyContext,
            ResourceType resourceType
    ) {
        ResourceDescriptor resourceDescriptor = null;
        try {
            Proxy proxy = proxyContext.getProxy();
            String resourceIdDecoded = UrlUtil.decodePath(resourceIdEncoded);
            if (proxyContext.getConfig().isDeploymentExists(resourceIdDecoded)) {
                resourceIdEncoded = createConfigResourceUrl(resourceIdEncoded, resourceType);
            } else {
                resourceDescriptor = ResourceDescriptorFactory.fromAnyUrl(resourceIdEncoded, proxy.getEncryptionService());
            }
        } catch (IllegalArgumentException ignored) {
            // resource might be static, resourceDescriptor remains null
        }

        Map<CredentialsLevel, BucketInfo> bucketInfo = resolveBucketInfo(resourceDescriptor, proxyContext);

        return new CredentialsLocator(resourceIdEncoded, bucketInfo);
    }

    private String createConfigResourceUrl(String resourceIdEncoded, ResourceType resourceType) {
        return resourceType.group()
                + ResourceDescriptor.PATH_SEPARATOR
                + "config"
                + ResourceDescriptor.PATH_SEPARATOR
                + resourceIdEncoded;
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
