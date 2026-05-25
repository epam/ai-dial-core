package com.epam.aidial.core.server.data.cache;

public record CachedUpstreamEntry(String endpoint, String id, String prefixPath, String extraMetadata) {
}
