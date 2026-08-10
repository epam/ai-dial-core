package com.epam.aidial.core.server.function;

import com.epam.aidial.core.config.Features;
import com.epam.aidial.core.config.InterfaceType;
import com.epam.aidial.core.config.Model;
import com.epam.aidial.core.server.Proxy;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.data.cache.CacheBreakpointContext;
import com.epam.aidial.core.server.data.cache.CachePolicy;
import com.epam.aidial.core.server.function.request.RequestObject;
import com.epam.aidial.core.server.service.UpstreamCacheService;

public class BuildUpstreamCacheFn extends BaseRequestFunction<RequestObject> {

    private final UpstreamCacheService upstreamCacheService;
    private final InterfaceType interfaceType;

    public BuildUpstreamCacheFn(Proxy proxy, ProxyContext context, InterfaceType interfaceType) {
        super(proxy, context);
        this.upstreamCacheService = proxy.getUpstreamCacheService();
        this.interfaceType = interfaceType;
    }

    @Override
    public Boolean apply(RequestObject request) {
        if (context.getDeployment() instanceof Model model && isCacheSupported(model)) {
            CachePolicy policy = CachePolicy.fromString(context.getRequestHeader(Proxy.HEADER_CACHE_POLICY));
            CacheBreakpointContext cacheBreakpointContext =
                    upstreamCacheService.buildCacheBreakpointContext(request, policy, model, interfaceType);
            context.setCacheBreakpointContext(cacheBreakpointContext);
        }
        return false;
    }

    private static boolean isCacheSupported(Model model) {
        Features features = model.getFeatures();
        if (features == null) {
            return false;
        }
        return Boolean.TRUE.equals(features.getCacheSupported()) || Boolean.TRUE.equals(features.getAutoCachingSupported());
    }
}
