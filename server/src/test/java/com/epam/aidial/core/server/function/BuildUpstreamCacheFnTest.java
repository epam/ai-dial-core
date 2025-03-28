package com.epam.aidial.core.server.function;

import com.epam.aidial.core.config.Features;
import com.epam.aidial.core.config.Model;
import com.epam.aidial.core.server.Proxy;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.data.cache.CacheBreakpointContext;
import com.epam.aidial.core.server.data.cache.CachePolicy;
import com.epam.aidial.core.server.util.ProxyUtil;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Answers;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
public class BuildUpstreamCacheFnTest {

    @Mock(answer = Answers.RETURNS_DEEP_STUBS)
    private Proxy proxy;

    @Mock
    private ProxyContext context;

    @InjectMocks
    private BuildUpstreamCacheFn fn;

    @Test
    public void testApply_WhenCacheSupported() {
        Model model = new Model();
        Features features = new Features();
        features.setCacheSupported(true);
        model.setFeatures(features);
        when(context.getDeployment()).thenReturn(model);
        when(context.getRequestHeader(eq(Proxy.HEADER_CACHE_POLICY))).thenReturn("cache-priority");
        ObjectNode objectNode = ProxyUtil.MAPPER.createObjectNode();

        Boolean res = fn.apply(objectNode);

        assertFalse(res);
        verify(proxy.getUpstreamCacheService()).buildCacheBreakpointContext(eq(objectNode), eq(CachePolicy.CACHE_PRIORITY), eq(model));
        verify(context).setCacheBreakpointContext(any(CacheBreakpointContext.class));
    }

    @Test
    public void testApply_WhenAutoCachingSupported() {
        Model model = new Model();
        Features features = new Features();
        features.setAutoCachingSupported(true);
        model.setFeatures(features);
        when(context.getDeployment()).thenReturn(model);
        when(context.getRequestHeader(eq(Proxy.HEADER_CACHE_POLICY))).thenReturn("cache-priority");
        ObjectNode objectNode = ProxyUtil.MAPPER.createObjectNode();

        Boolean res = fn.apply(objectNode);

        assertFalse(res);
        verify(proxy.getUpstreamCacheService()).buildCacheBreakpointContext(eq(objectNode), eq(CachePolicy.CACHE_PRIORITY), eq(model));
        verify(context).setCacheBreakpointContext(any(CacheBreakpointContext.class));
    }
}
