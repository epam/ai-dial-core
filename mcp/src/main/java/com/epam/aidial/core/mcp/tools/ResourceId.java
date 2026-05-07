package com.epam.aidial.core.mcp.tools;

import java.util.Map;
import java.util.Set;

/**
 * Canonical id parser for MCP read tools — {@code {type}/{bucket}/{name}}. For hierarchical
 * types ({@code files}, {@code prompts}, {@code conversations}) the {@code name} segment may
 * itself contain slashes (folder/leaf path).
 *
 * <p>Per-type Core path routing is captured here (not at handler call sites): config types use
 * {@code /v1/{type}/{bucket}/...}; resource types listed in {@link #METADATA_LIST_TYPES} use
 * {@code /v1/metadata/{type}/{bucket}/...} for listings; {@code files} additionally use the
 * metadata route for individual GETs (the {@code /v1/files/...} controller returns raw bytes —
 * raw download is the {@code dial_download_file} tool surface).
 */
public record ResourceId(String type, String bucket, String name) {

    static final Set<String> KNOWN_TYPES = Set.of(
            "models", "applications", "toolsets", "interceptors", "roles", "keys", "routes",
            "schemas", "settings", "files", "prompts", "conversations");

    static final Set<String> METADATA_LIST_TYPES = Set.of(
            "applications", "toolsets", "files", "prompts", "conversations");

    private static final Set<String> METADATA_GET_TYPES = Set.of("files");

    /**
     * MCP-side mapping of canonical type segment to the {@code kind} discriminator accepted by
     * {@code POST /v1/admin/validate} (spec 09 §6.1 tools 4-5, §6.6). Hierarchical resource
     * surfaces ({@code files}, {@code prompts}, {@code conversations}) are intentionally absent
     * — they are not validate-only-able; agents must drop {@code validate_only} or call against
     * a config type instead.
     */
    static final Map<String, String> TYPE_TO_KIND = Map.of(
            "models", "Model",
            "applications", "Application",
            "toolsets", "ToolSet",
            "interceptors", "Interceptor",
            "roles", "Role",
            "keys", "Key",
            "routes", "Route",
            "schemas", "Schema",
            "settings", "Settings");

    public static ResourceId parse(String id) {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("id must not be blank. Expected '{type}/{bucket}/{name}'.");
        }
        String[] parts = id.split("/", 3);
        if (parts.length < 3 || parts[0].isBlank() || parts[1].isBlank() || parts[2].isBlank()) {
            throw new IllegalArgumentException("Malformed id '" + id + "'. Expected '{type}/{bucket}/{name}'.");
        }
        if (!KNOWN_TYPES.contains(parts[0])) {
            throw new IllegalArgumentException("Unknown type '" + parts[0]
                    + "'. Call dial_describe_schema for the full type catalog.");
        }
        return new ResourceId(parts[0], parts[1], parts[2]);
    }

    /**
     * Parses the {@code path} arg accepted by {@code dial_list_resources} —
     * {@code {type}/{bucket}/[subpath/]}. The subpath (possibly empty, possibly multi-segment for
     * hierarchical types) is stored in the {@code name} slot; only {@link #toListCorePath} reads
     * it back. Type validation is deferred to the caller so type-specific short-circuits
     * (e.g. {@code settings} → 405) can fire before unknown-type rejection.
     */
    public static ResourceId parseListPath(String path) {
        if (path == null || path.isBlank()) {
            throw new IllegalArgumentException("path must not be blank. Expected '{type}/{bucket}/'.");
        }
        String[] parts = path.split("/", 3);
        if (parts.length < 2 || parts[0].isBlank() || parts[1].isBlank()) {
            throw new IllegalArgumentException("Malformed path '" + path + "'. Expected '{type}/{bucket}/'.");
        }
        String subPath = parts.length == 3 ? parts[2] : "";
        return new ResourceId(parts[0], parts[1], subPath);
    }

    /** Builds the Core URL for a single-resource GET. Pair with a {@link #parse} result. */
    public String toCorePath(String resolvedBucket) {
        String prefix = METADATA_GET_TYPES.contains(type) ? "/v1/metadata/" : "/v1/";
        return prefix + type + "/" + resolvedBucket + "/" + name;
    }

    /** Builds the Core URL for a folder listing. Pair with a {@link #parseListPath} result. */
    public String toListCorePath(String resolvedBucket) {
        String prefix = METADATA_LIST_TYPES.contains(type) ? "/v1/metadata/" : "/v1/";
        return prefix + type + "/" + resolvedBucket + "/" + name;
    }

    /**
     * Whether the type's listing endpoint supports the {@code recursive} query parameter.
     * Coincides today with metadata-routed listings; flat config types reject it.
     */
    public boolean supportsRecursive() {
        return METADATA_LIST_TYPES.contains(type);
    }

    /**
     * Whether this type's writes go through {@code ResourceController} (PUT-upsert) rather than
     * {@code ConfigResourceController} (POST/PUT split). MCP write tools layer
     * {@code If-None-Match: *} / {@code If-Match: *} to recover the create/update split for
     * these types. {@code files} is excluded — its writes are binary and out of M.2.0 scope.
     */
    public boolean isResourceControllerType() {
        return METADATA_LIST_TYPES.contains(type) && !"files".equals(type);
    }
}
