package com.epam.aidial.core.server.function;

import com.epam.aidial.core.config.Features;
import com.epam.aidial.core.config.Model;
import com.epam.aidial.core.server.Proxy;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.data.cache.CacheBreakpointContext;
import com.epam.aidial.core.server.data.cache.CachePolicy;
import com.epam.aidial.core.server.service.UpstreamCacheService;
import com.fasterxml.jackson.databind.node.ObjectNode;

public class BuildUpstreamCacheFn extends BaseRequestFunction<ObjectNode> {

    private final UpstreamCacheService upstreamCacheService;

    public BuildUpstreamCacheFn(Proxy proxy, ProxyContext context) {
        super(proxy, context);
        this.upstreamCacheService = proxy.getUpstreamCacheService();
    }

    @Override
    public Boolean apply(ObjectNode body) {
        if (context.getDeployment() instanceof Model model && isCacheSupported(model)) {
            CachePolicy policy = CachePolicy.fromString(context.getRequestHeader(Proxy.HEADER_CACHE_POLICY));
            CacheBreakpointContext cacheBreakpointContext = upstreamCacheService.buildCacheBreakpointContext(body, policy, model);
            context.setCacheBreakpointContext(cacheBreakpointContext);
        }
        return false;
    }

    private static boolean isCacheSupported(Model model) {
        Features features = model.getFeatures();
        if (features == null) {
            return false;
        }
        Boolean cacheSupported = features.getCacheSupported();
        return cacheSupported != null && cacheSupported;
    }
}
