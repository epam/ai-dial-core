package com.epam.aidial.core.server.data.cache;

import com.epam.aidial.core.storage.http.HttpException;
import com.epam.aidial.core.storage.http.HttpStatus;

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
    CACHE_PRIORITY("cache-priority");

    private final String val;
    CachePolicy(String val) {
        this.val = val;
    }

    public static CachePolicy fromString(String val) {
        if (val == null) {
            return AVAILABILITY_PRIORITY;
        }
        for (CachePolicy policy : CachePolicy.values()) {
            if (policy.val.equals(val)) {
                return policy;
            }
        }
        throw new HttpException(HttpStatus.BAD_REQUEST, "Invalid cache policy: " + val);
    }
}
