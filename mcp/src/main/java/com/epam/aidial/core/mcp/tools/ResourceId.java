package com.epam.aidial.core.mcp.tools;

import java.util.Set;

/**
 * Canonical id parser for MCP read tools — {@code {type}/{bucket}/{name}}. M.1.0 pilot type set
 * is {@code models}, {@code roles}, {@code settings}; other types are rejected with a hint
 * pointing at {@code dial_describe_schema}. Bucket is stored verbatim — alias resolution
 * (private/public/platform) is handled by {@link SessionBucketCache} at call time.
 */
public record ResourceId(String type, String bucket, String name) {

    static final Set<String> PILOT_TYPES = Set.of("models", "roles", "settings");

    public static ResourceId parse(String id) {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("id must not be blank. Expected '{type}/{bucket}/{name}'.");
        }
        String[] parts = id.split("/", 3);
        if (parts.length < 3 || parts[0].isBlank() || parts[1].isBlank() || parts[2].isBlank()) {
            throw new IllegalArgumentException("Malformed id '" + id + "'. Expected '{type}/{bucket}/{name}'.");
        }
        if (!PILOT_TYPES.contains(parts[0])) {
            throw new IllegalArgumentException("Unsupported type '" + parts[0]
                    + "' in M.1.0. Pilot set: " + PILOT_TYPES + ". Call dial_describe_schema for the full type catalog.");
        }
        return new ResourceId(parts[0], parts[1], parts[2]);
    }

    /**
     * Parses the {@code path} arg accepted by {@code dial_list_resources} — {@code {type}/{bucket}/[subpath/]}.
     * Returns a {@code ResourceId} with empty {@code name}; the type is NOT validated against
     * {@link #PILOT_TYPES} here so callers can short-circuit type-specific handling
     * (e.g. {@code settings} list returns 405) before rejecting unknown types.
     */
    public static ResourceId parseListPath(String path) {
        if (path == null || path.isBlank()) {
            throw new IllegalArgumentException("path must not be blank. Expected '{type}/{bucket}/'.");
        }
        String[] parts = path.split("/", 3);
        if (parts.length < 2 || parts[0].isBlank() || parts[1].isBlank()) {
            throw new IllegalArgumentException("Malformed path '" + path + "'. Expected '{type}/{bucket}/'.");
        }
        return new ResourceId(parts[0], parts[1], "");
    }

    public String toCorePath(String resolvedBucket) {
        return "/v1/" + type + "/" + resolvedBucket + "/" + name;
    }

    public String toListCorePath(String resolvedBucket) {
        return "/v1/" + type + "/" + resolvedBucket + "/";
    }
}
