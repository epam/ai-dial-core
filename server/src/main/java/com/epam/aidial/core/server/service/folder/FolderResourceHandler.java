package com.epam.aidial.core.server.service.folder;

import com.epam.aidial.core.storage.resource.ResourceType;

import java.util.Map;
import javax.annotation.Nullable;

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

    /**
     * Validates a single-file mutation before it is applied to a new version. Implementations must throw
     * {@link com.epam.aidial.core.storage.http.HttpException} with
     * {@link com.epam.aidial.core.storage.http.HttpStatus#BAD_REQUEST} when the mutation is not allowed
     * (e.g. deleting a mandatory manifest).
     *
     * @param relativePath the file being added, replaced or removed
     * @param mutation     the kind of mutation
     */
    void validateFileMutation(String relativePath, FileMutation mutation);

    /**
     * Validates a single-file PUT and returns refreshed marker metadata if this file affects it, or
     * {@code null} to keep the existing metadata unchanged. Implementations must throw
     * {@link com.epam.aidial.core.storage.http.HttpException} with
     * {@link com.epam.aidial.core.storage.http.HttpStatus#BAD_REQUEST} when the file content is invalid.
     *
     * @param relativePath the file being added or replaced
     * @param content      its new content
     */
    @Nullable
    Map<String, Object> refreshMetadataOnPut(String relativePath, byte[] content);

    /**
     * Kind of single-file mutation.
     */
    enum FileMutation {
        PUT, DELETE
    }
}
