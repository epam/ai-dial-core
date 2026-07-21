package com.epam.aidial.core.server.data.folder;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
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
    /**
     * Per-file metadata of the current version (relative path -> size/etag). The aggregate {@link #etag} is
     * derived from the per-file etags, so single-file mutations can recompute it without reading file
     * content; the sizes let {@code maxTotalBytes}/{@code maxFiles} be enforced on a single-file mutation
     * without re-reading every existing file's content or metadata.
     */
    private Map<String, ResourceFileMetadata> fileMetadata;
    private Long createdAt;
    private Long updatedAt;
    private Long deletedAt;
    private String author;
    /**
     * Type-specific metadata extracted by the per-type handler (e.g. skill name/description/version).
     */
    private Map<String, Object> metadata;

    /**
     * Per-file size and etag, keyed by relative path in {@link #fileMetadata}.
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ResourceFileMetadata {
        private long size;
        private String etag;
    }
}
