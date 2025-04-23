package com.epam.aidial.core.storage.cache;

public enum CacheProvider {
    AWS_ELASTI_CACHE,
    GCP_MEMORY_STORE,
    AZURE_REDIS_CACHE;

    public static CacheProvider from(String cacheProviderName) {
        return switch (cacheProviderName) {
            case "aws-elasti-cache" -> AWS_ELASTI_CACHE;
            case "gcp-memory-store" -> GCP_MEMORY_STORE;
            case "azure-redis-cache" -> AZURE_REDIS_CACHE;
            default -> throw new IllegalArgumentException("Unknown cache provider: " + cacheProviderName);
        };
    }
}
