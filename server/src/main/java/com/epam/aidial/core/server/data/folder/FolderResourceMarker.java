package com.epam.aidial.core.server.data.folder;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * Self-describing marker (stored as the {@code .dial-resource} JSON document) that turns a folder
 * into a versioned resource. The marker is the commit point of a whole-resource write and points at
 * the current immutable version stored under the {@code v/{currentVersion}/} prefix.
 *
 * <p>The marker is always synthesized server-side (the {@code type} is derived from the request URL
 * group), so clients can never corrupt it.
 */
@Data
@NoArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class FolderResourceMarker {

    /**
     * Resource type, e.g. {@code skills}. Derived from the URL group server-side.
     */
    private String type;
    /**
     * Marker document schema version.
     */
    private int schemaVersion;
    /**
     * Lifecycle state of the resource.
     */
    private String state;
    /**
     * Identifier of the current immutable version (the {@code v/{currentVersion}/} prefix).
     */
    private String currentVersion;
    /**
     * Aggregate etag over all files of the current version.
     */
    private String etag;
    private Long createdAt;
    private Long updatedAt;
    private String author;
    /**
     * Type-specific metadata extracted by the per-type handler (e.g. skill name/description/version).
     */
    private Map<String, Object> metadata;
}
