package com.epam.aidial.core.server.upstream;

import com.epam.aidial.core.config.Upstream;
import com.epam.aidial.core.server.data.cache.CachePolicy;
import com.epam.aidial.core.server.data.cache.CachedUpstreamEntry;
import lombok.Data;

import java.util.Map;

@Data
class UpstreamCacheContext {
    private CachedUpstreamEntry entry;
    private CachePolicy policy;
    private Map<String, String> prefixToHash;
    private Upstream originalUpstream;
}
