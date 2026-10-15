package com.epam.aidial.core.server.function;

import com.epam.aidial.core.config.Features;
import com.epam.aidial.core.config.InterfaceMode;
import com.epam.aidial.core.config.InterfaceType;
import com.epam.aidial.core.config.Model;
import com.epam.aidial.core.server.Proxy;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.data.cache.CacheBreakpointContext;
import com.epam.aidial.core.server.data.cache.CachePolicy;
import com.epam.aidial.core.server.function.request.RequestObject;
import com.epam.aidial.core.server.service.UpstreamCacheService;
import com.epam.aidial.core.server.util.DeploymentEndpointUtil;

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
        // A translated interface is served by its translator, which calls Core back on the out interface: this
        // pass chooses no upstream, and the inner pass hashes its own request. Hashing the body here would be
        // work no lookup ever reads.
        if (context.getDeployment() instanceof Model model && isCacheSupported(model)
                && DeploymentEndpointUtil.resolveMode(model, interfaceType) != InterfaceMode.TRANSLATOR) {
            CachePolicy policy = CachePolicy.fromString(context.getRequestHeader(Proxy.HEADER_CACHE_POLICY));
            CacheBreakpointContext cacheBreakpointContext =
                    upstreamCacheService.buildCacheBreakpointContext(request, policy, model, interfaceType);
            context.setCacheBreakpointContext(cacheBreakpointContext);
        }
        return false;
    }

    private boolean isCacheSupported(Model model) {
        Features features = model.resolveFeatures(interfaceType);
        if (features == null) {
            return false;
        }
        return Boolean.TRUE.equals(features.getCacheSupported()) || Boolean.TRUE.equals(features.getAutoCachingSupported());
    }
}
