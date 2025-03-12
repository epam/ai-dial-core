package com.epam.aidial.core.server.data.cache;

public enum CachePolicy {
    /**
     * Prioritize service availability over cache hits. We will try to route his request to same upstream, that will provide caching feature.
     * But in case, when cache upstream in not available, we will fallback to another available upstream, and will create cache there.
     */
    AVAILABILITY_PRIORITY("availability-priority"),
    /**
     * Prioritize cache hits over service availability. We will route request with cache flag to the same upstream.
     * Even if cache upstream is not available, we will retry request to the same upstream.
     */
    CACHE_PRIORITY("cache-priority"),
    /**
     * Try to create automatic cache, where it's possible. We will route request that is cached to same upstream.
     * But in case, when cache upstream is not available, we will fallback to another available upstream, and will create cache there.
     * <p>
     *  <b>Note</b>. Core should calculate hash for all prefixes for each request, and check their existence in Redis.
     * </p>
     */
    AUTO_CACHING("auto-caching");

    private final String val;
    CachePolicy(String val) {
        this.val = val;
    }

    public static CachePolicy fromString(String val) {
        for (CachePolicy policy : CachePolicy.values()) {
            if (policy.val.equals(val)) {
                return policy;
            }
        }
        // fallback
        return AVAILABILITY_PRIORITY;
    }
}
