package com.epam.aidial.core.util;

import com.epam.aidial.core.service.ResourceService;
import com.epam.aidial.core.storage.BlobStorage;
import com.epam.aidial.core.storage.ResourceDescription;
import lombok.experimental.UtilityClass;

@UtilityClass
public class ResourceUtil {

    public static boolean hasResource(ResourceDescription resource, ResourceService resourceService, BlobStorage storage) {
        return switch (resource.getType()) {
            case FILE -> storage.exists(resource.getAbsoluteFilePath());
            case CONVERSATION, PROMPT, APPLICATION -> resourceService.hasResource(resource);
            default -> throw new IllegalArgumentException("Unsupported resource type " + resource.getType());
        };
    }
}
