package com.epam.aidial.core.storage.cache;

public enum CacheProvider {
    AWS_ELASTI_CACHE,
    GCP_MEMORY_STORE;

    public static CacheProvider from(String cacheProviderName) {
        return switch (cacheProviderName) {
            case "aws-elasti-cache" -> AWS_ELASTI_CACHE;
            case "gcp-memory-store" -> GCP_MEMORY_STORE;
            default -> throw new IllegalArgumentException("Unknown cache provider");
        };
    }
}
