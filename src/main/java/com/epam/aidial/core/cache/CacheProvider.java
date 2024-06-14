package com.epam.aidial.core.cache;

public enum CacheProvider {
    AWS_ELASTI_CACHE;

    public static CacheProvider from(String cacheProviderName) {
        return switch (cacheProviderName) {
            case "aws-elasti-cache" -> AWS_ELASTI_CACHE;
            default -> throw new IllegalArgumentException("Unknown cache provider");
        };
    }
}
