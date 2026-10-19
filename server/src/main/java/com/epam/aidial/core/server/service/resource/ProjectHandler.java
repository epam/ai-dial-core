package com.epam.aidial.core.server.service.resource;

import com.epam.aidial.core.storage.resource.ResourceType;
import com.epam.aidial.core.storage.resource.ResourceTypes;

import java.util.Map;

/**
 * Handler for the {@code projects} group. A project is an arbitrary file tree: no manifest or content
 * validation is applied and the marker carries no type-specific metadata.
 */
public class ProjectHandler implements ComplexResourceHandler {

    @Override
    public ResourceType resourceType() {
        return ResourceTypes.PROJECT;
    }

    @Override
    public void validate(Map<String, byte[]> files) {
        // any non-empty file set is a valid project
    }

    @Override
    public Map<String, Object> buildMarkerMetadata(Map<String, byte[]> files) {
        return Map.of();
    }

    @Override
    public void validateFileMutation(String relativePath, FileMutation mutation) {
        // every file may be added, replaced or removed
    }

    @Override
    public Map<String, Object> refreshMetadataOnPut(String relativePath, byte[] content) {
        return null;
    }
}
