package com.epam.aidial.core.util;

import com.epam.aidial.core.data.ResourceAccessType;
import com.epam.aidial.core.data.ResourceType;
import com.epam.aidial.core.data.SharedResource;
import com.epam.aidial.core.service.ResourceService;
import com.epam.aidial.core.storage.BlobStorage;
import com.epam.aidial.core.storage.BlobStorageUtil;
import com.epam.aidial.core.storage.ResourceDescription;
import com.google.common.collect.Sets;
import lombok.experimental.UtilityClass;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@UtilityClass
public class ResourceUtil {

    public static boolean hasResource(ResourceDescription resource, ResourceService resourceService, BlobStorage storage) {
        return switch (resource.getType()) {
            case FILE -> storage.exists(resource.getAbsoluteFilePath());
            case CONVERSATION, PROMPT -> resourceService.hasResource(resource);
            default -> throw new IllegalArgumentException("Unsupported resource type " + resource.getType());
        };
    }

    public ResourceType getResourceType(String url) {
        if (url == null) {
            throw new IllegalStateException("Resource link can not be null");
        }

        String[] paths = url.split(BlobStorageUtil.PATH_SEPARATOR);

        if (paths.length < 2) {
            throw new IllegalStateException("Invalid resource link provided: " + url);
        }

        return ResourceType.of(paths[0]);
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

    public Map<String, Set<ResourceAccessType>> mergePermissions(List<SharedResource> sharedResources) {
        return sharedResources.stream()
                .collect(Collectors.toUnmodifiableMap(SharedResource::url, SharedResource::permissions, Sets::union));
    }
}
