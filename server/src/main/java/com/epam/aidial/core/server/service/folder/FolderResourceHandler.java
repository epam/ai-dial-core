package com.epam.aidial.core.server.service.folder;

import com.epam.aidial.core.storage.resource.ResourceType;

import java.util.Map;

/**
 * Per-type logic for a folder-as-resource group. The generic {@link FolderResourceService} engine
 * delegates type-specific validation and marker-metadata extraction to a handler keyed by URL group.
 */
public interface FolderResourceHandler {

    /**
     * Resource type this handler serves (e.g. {@code SKILL}).
     */
    ResourceType resourceType();

    /**
     * Validates the uploaded file set. Implementations must throw
     * {@link com.epam.aidial.core.storage.http.HttpException} with
     * {@link com.epam.aidial.core.storage.http.HttpStatus#BAD_REQUEST} when the set is invalid.
     *
     * @param files relative path -> file content
     */
    void validate(Map<String, byte[]> files);

    /**
     * Extracts type-specific metadata to embed in the {@code .dial-resource} marker.
     *
     * @param files relative path -> file content
     */
    Map<String, Object> buildMarkerMetadata(Map<String, byte[]> files);
}
