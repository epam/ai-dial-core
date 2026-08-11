package com.epam.aidial.core.server.function;

import com.epam.aidial.core.config.Features;
import com.epam.aidial.core.config.InterfaceType;
import com.epam.aidial.core.config.Model;
import com.epam.aidial.core.server.Proxy;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.data.cache.CacheBreakpointContext;
import com.epam.aidial.core.server.data.cache.CachePolicy;
import com.epam.aidial.core.server.function.request.ChatCompletionRequest;
import com.epam.aidial.core.server.function.request.RequestObject;
import com.epam.aidial.core.server.util.ProxyUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Answers;
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

    @Test
    public void testApply_WhenCacheSupported() {
        BuildUpstreamCacheFn fn = new BuildUpstreamCacheFn(proxy, context, InterfaceType.OPENAI_CHAT_COMPLETIONS);
        Model model = new Model();
        Features features = new Features();
        features.setCacheSupported(true);
        model.setFeatures(features);
        when(context.getDeployment()).thenReturn(model);
        when(context.getRequestHeader(eq(Proxy.HEADER_CACHE_POLICY))).thenReturn("cache-priority");
        RequestObject request = new ChatCompletionRequest(ProxyUtil.MAPPER.createObjectNode());

        Boolean res = fn.apply(request);

        assertFalse(res);
        verify(proxy.getUpstreamCacheService()).buildCacheBreakpointContext(
                eq(request), eq(CachePolicy.CACHE_PRIORITY), eq(model), eq(InterfaceType.OPENAI_CHAT_COMPLETIONS));
        verify(context).setCacheBreakpointContext(any(CacheBreakpointContext.class));
    }

    @Test
    public void testApply_WhenAutoCachingSupported() {
        BuildUpstreamCacheFn fn = new BuildUpstreamCacheFn(proxy, context, InterfaceType.ANTHROPIC_MESSAGES);
        Model model = new Model();
        Features features = new Features();
        features.setAutoCachingSupported(true);
        model.setFeatures(features);
        when(context.getDeployment()).thenReturn(model);
        when(context.getRequestHeader(eq(Proxy.HEADER_CACHE_POLICY))).thenReturn("cache-priority");
        RequestObject request = new ChatCompletionRequest(ProxyUtil.MAPPER.createObjectNode());

        Boolean res = fn.apply(request);

        assertFalse(res);
        verify(proxy.getUpstreamCacheService()).buildCacheBreakpointContext(
                eq(request), eq(CachePolicy.CACHE_PRIORITY), eq(model), eq(InterfaceType.ANTHROPIC_MESSAGES));
        verify(context).setCacheBreakpointContext(any(CacheBreakpointContext.class));
    }
}
