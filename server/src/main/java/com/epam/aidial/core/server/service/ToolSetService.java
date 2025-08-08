package com.epam.aidial.core.server.service;

import com.epam.aidial.core.config.ToolSet;
import com.epam.aidial.core.server.data.ResourceTypes;
import com.epam.aidial.core.server.util.ProxyUtil;
import com.epam.aidial.core.storage.data.ResourceItemMetadata;
import com.epam.aidial.core.storage.resource.ResourceDescriptor;
import com.epam.aidial.core.storage.service.ResourceService;
import com.epam.aidial.core.storage.util.EtagHeader;
import lombok.AllArgsConstructor;
import org.apache.commons.lang3.tuple.Pair;

@AllArgsConstructor
public class ToolSetService {

    private final ResourceService resourceService;

    public Pair<ResourceItemMetadata, ToolSet> getToolSet(ResourceDescriptor resource) {
        return getToolSet(resource, EtagHeader.ANY);
    }

    public Pair<ResourceItemMetadata, ToolSet> getToolSet(ResourceDescriptor resource, EtagHeader etagHeader) {
        verifyToolSet(resource);
        Pair<ResourceItemMetadata, String> result = resourceService.getResourceWithMetadata(resource, etagHeader);

        if (result == null) {
            throw new ResourceNotFoundException("ToolSet is not found: " + resource.getUrl());
        }

        ResourceItemMetadata meta = result.getKey();
        ToolSet toolSet = ProxyUtil.convertToObject(result.getValue(), ToolSet.class);

        if (toolSet == null) {
            throw new ResourceNotFoundException("ToolSet is not found: " + resource.getUrl());
        }

        toolSet.setAuthor(meta.getAuthor());
        toolSet.setCreatedAt(meta.getCreatedAt());
        toolSet.setUpdatedAt(meta.getUpdatedAt());

        return Pair.of(meta, toolSet);
    }

    public Pair<ResourceItemMetadata, ToolSet> putToolSet(ResourceDescriptor resource, EtagHeader etag, String author, ToolSet toolSet) {
        toolSet.setName(resource.getUrl());
        String body = ProxyUtil.convertToString(toolSet);
        ResourceItemMetadata metadata = resourceService.putResource(resource, body, etag, author);
        return Pair.of(metadata, toolSet);
    }

    private static void verifyToolSet(ResourceDescriptor resource) {
        if (resource.isFolder() || resource.getType() != ResourceTypes.TOOL_SET) {
            throw new IllegalArgumentException("Invalid application url: " + resource.getUrl());
        }
    }


}
