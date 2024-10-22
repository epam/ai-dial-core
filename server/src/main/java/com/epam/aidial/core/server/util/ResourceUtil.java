package com.epam.aidial.core.server.util;

import com.epam.aidial.core.server.data.ResourceAccessType;
import com.epam.aidial.core.server.data.ResourceTypes;
import com.epam.aidial.core.server.data.SharedResource;
import com.epam.aidial.core.server.resource.ResourceType;
import com.epam.aidial.core.server.resource.ResourceTypeRegistry;
import com.epam.aidial.core.server.security.EncryptionService;
import com.epam.aidial.core.server.storage.BlobStorageUtil;
import com.epam.aidial.core.server.resource.ResourceDescription;
import lombok.experimental.UtilityClass;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@UtilityClass
public class ResourceUtil {
    public static final String ETAG_ATTRIBUTE = "etag";
    public static final String CREATED_AT_ATTRIBUTE = "created_at";
    public static final String UPDATED_AT_ATTRIBUTE = "updated_at";
    public static final String RESOURCE_TYPE_ATTRIBUTE = "resource_type";
    // Default ETag for old records
    public static final String DEFAULT_ETAG = "0";

    public ResourceType toExternal(String group) {
        ResourceType resourceType = ResourceTypeRegistry.getByGroup(group);
        if (resourceType == null || !resourceType.external()) {
            throw new IllegalArgumentException("Unsupported resource type: " + group);
        }
        return resourceType;
    }

    public ResourceTypes getResourceType(String url) {
        if (url == null) {
            throw new IllegalStateException("Resource link can not be null");
        }

        String[] paths = url.split(BlobStorageUtil.PATH_SEPARATOR);

        if (paths.length < 2) {
            throw new IllegalStateException("Invalid resource link provided: " + url);
        }

        return ResourceTypes.of(paths[0]);
    }

    public String getBucket(String url) {
        if (url == null) {
            throw new IllegalStateException("Resource link can not be null");
        }

        String[] paths = url.split(BlobStorageUtil.PATH_SEPARATOR);

        if (paths.length < 2) {
            throw new IllegalStateException("Invalid resource link provided: " + url);
        }

        return paths[1];
    }

    public Map<String, Set<ResourceAccessType>> sharedResourcesToMap(List<SharedResource> sharedResources) {
        return sharedResources.stream()
                .collect(Collectors.toUnmodifiableMap(SharedResource::url, SharedResource::permissions));
    }

    public ResourceDescription resourceFromUrl(String url, EncryptionService encryptionService) {
        try {
            if (url.startsWith(ProxyUtil.METADATA_PREFIX)) {
                url = url.substring(ProxyUtil.METADATA_PREFIX.length());
            }
            return ResourceDescription.fromPrivateUrl(url, encryptionService);
        } catch (Exception e) {
            throw new IllegalArgumentException("Incorrect resource link provided " + url);
        }
    }

    public String extractEtag(Map<String, String> attributes) {
        return attributes.getOrDefault(ETAG_ATTRIBUTE, DEFAULT_ETAG);
    }
}
