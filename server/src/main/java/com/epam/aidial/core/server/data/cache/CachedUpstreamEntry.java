package com.epam.aidial.core.server.data.cache;

import com.epam.aidial.core.config.Upstream;
import lombok.Data;

import java.util.Map;

@Data
public class CachedUpstreamEntry {
    private String endpoint;
    private String prefixPath;
    private String hash;
    private CachePolicy policy;
    private Upstream originalUpstream;
    private String expireAt;
    private Map<String, String> prefixToHash;
}
