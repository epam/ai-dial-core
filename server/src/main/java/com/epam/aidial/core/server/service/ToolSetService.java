package com.epam.aidial.core.server.service;

import com.epam.aidial.core.config.ToolSet;
import com.epam.aidial.core.server.data.ResourceTypes;
import com.epam.aidial.core.server.service.credentials.ToolSetAuthSettingsService;
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
    private final ToolSetAuthSettingsService toolsetAuthSettingsService;

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

        toolsetAuthSettingsService.setToolSetAuthStatuses(toolSet);

        toolSet.setAuthor(meta.getAuthor());
        toolSet.setCreatedAt(meta.getCreatedAt());
        toolSet.setUpdatedAt(meta.getUpdatedAt());

        return Pair.of(meta, toolSet);
    }

    public Pair<ResourceItemMetadata, ToolSet> putToolSet(ResourceDescriptor resource, EtagHeader etag, String author, ToolSet toolSet) {
        toolSet.setName(resource.getUrl());
        if (toolSet.getReference() == null) {
            toolSet.setReference(ProxyUtil.generateReference());
        }
        ResourceItemMetadata meta = resourceService.computeResource(resource, etag, author, json -> {
            ToolSet existing = ProxyUtil.convertToObject(json, ToolSet.class);
            //TODO: now it registers auth settings only on ToolSet create.
            // should we apply registration on update as well?
            if (existing == null) {
                toolsetAuthSettingsService.initToolsetAuthSettings(toolSet);
            }
            //TODO we don't support auth settings update yet
            return ProxyUtil.convertToString(toolSet);
        });

        return Pair.of(meta, toolSet);
    }

    private static void verifyToolSet(ResourceDescriptor resource) {
        if (resource.isFolder() || resource.getType() != ResourceTypes.TOOL_SET) {
            throw new IllegalArgumentException("Invalid application url: " + resource.getUrl());
        }
    }


}
