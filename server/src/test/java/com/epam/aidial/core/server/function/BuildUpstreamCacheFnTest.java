package com.epam.aidial.core.server.function;

import com.epam.aidial.core.config.DeploymentInterface;
import com.epam.aidial.core.config.Features;
import com.epam.aidial.core.config.InterfaceMode;
import com.epam.aidial.core.config.InterfaceType;
import com.epam.aidial.core.config.Model;
import com.epam.aidial.core.server.Proxy;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.data.cache.CacheBreakpointContext;
import com.epam.aidial.core.server.data.cache.CachePolicy;
import com.epam.aidial.core.server.function.request.ChatCompletionRequest;
import com.epam.aidial.core.server.function.request.RequestObject;
import com.epam.aidial.core.server.util.ProxyUtil;
import com.epam.aidial.core.storage.http.HttpException;
import com.epam.aidial.core.storage.http.HttpStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Answers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
public class BuildUpstreamCacheFnTest {

    @Mock(answer = Answers.RETURNS_DEEP_STUBS)
    private Proxy proxy;

    @Mock
    private ProxyContext context;

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void interfaceCanEnableOrDisableCaching(boolean enabled) {
        Model model = new Model();
        Features features = new Features();
        features.setCacheSupported(!enabled);
        model.setFeatures(features);
        Features overrides = new Features();
        overrides.setCacheSupported(enabled);
        DeploymentInterface declared = new DeploymentInterface();
        declared.setFeatures(overrides);
        model.setInterfaces(Map.of(InterfaceType.ANTHROPIC_MESSAGES.getValue(), declared));
        when(context.getDeployment()).thenReturn(model);
        RequestObject request = new ChatCompletionRequest(ProxyUtil.MAPPER.createObjectNode());

        new BuildUpstreamCacheFn(proxy, context, InterfaceType.ANTHROPIC_MESSAGES).apply(request);

        verify(proxy.getUpstreamCacheService(), enabled ? times(1) : never())
                .buildCacheBreakpointContext(eq(request), any(), eq(model), eq(InterfaceType.ANTHROPIC_MESSAGES));
    }

    @ParameterizedTest
    @EnumSource(InterfaceMode.class)
    void buildsCacheKeysOnlyWhenTheInterfaceServesTheCompletion(InterfaceMode mode) {
        Model model = cacheSupportedModelWith(InterfaceType.ANTHROPIC_MESSAGES, mode);
        when(context.getDeployment()).thenReturn(model);
        RequestObject request = new ChatCompletionRequest(ProxyUtil.MAPPER.createObjectNode());

        new BuildUpstreamCacheFn(proxy, context, InterfaceType.ANTHROPIC_MESSAGES).apply(request);

        verify(proxy.getUpstreamCacheService(), mode.servesCompletion() ? times(1) : never())
                .buildCacheBreakpointContext(eq(request), any(), eq(model), eq(InterfaceType.ANTHROPIC_MESSAGES));
        verify(context, mode.servesCompletion() ? times(1) : never()).setCacheBreakpointContext(any());
    }

    @Test
    void translatedModelStillBuildsCacheKeysOnItsOutInterface() {
        Model model = cacheSupportedModelWith(InterfaceType.ANTHROPIC_MESSAGES, InterfaceMode.TRANSLATOR);
        when(context.getDeployment()).thenReturn(model);
        RequestObject request = new ChatCompletionRequest(ProxyUtil.MAPPER.createObjectNode());

        new BuildUpstreamCacheFn(proxy, context, InterfaceType.OPENAI_CHAT_COMPLETIONS).apply(request);

        verify(proxy.getUpstreamCacheService())
                .buildCacheBreakpointContext(eq(request), any(), eq(model), eq(InterfaceType.OPENAI_CHAT_COMPLETIONS));
        verify(context).setCacheBreakpointContext(any());
    }

    @Test
    void translatedInterfaceStillRejectsAnInvalidCachePolicy() {
        Model model = cacheSupportedModelWith(InterfaceType.ANTHROPIC_MESSAGES, InterfaceMode.TRANSLATOR);
        when(context.getDeployment()).thenReturn(model);
        when(context.getRequestHeader(eq(Proxy.HEADER_CACHE_POLICY))).thenReturn("foo");
        RequestObject request = new ChatCompletionRequest(ProxyUtil.MAPPER.createObjectNode());
        BuildUpstreamCacheFn fn = new BuildUpstreamCacheFn(proxy, context, InterfaceType.ANTHROPIC_MESSAGES);

        HttpException error = assertThrows(HttpException.class, () -> fn.apply(request));

        assertEquals(HttpStatus.BAD_REQUEST, error.getStatus());
        verify(proxy.getUpstreamCacheService(), never()).buildCacheBreakpointContext(any(), any(), any(), any());
    }

    private static Model cacheSupportedModelWith(InterfaceType type, InterfaceMode mode) {
        Model model = new Model();
        Features features = new Features();
        features.setCacheSupported(true);
        model.setFeatures(features);
        DeploymentInterface declared = new DeploymentInterface();
        declared.setMode(mode);
        model.setInterfaces(Map.of(type.getValue(), declared));
        return model;
    }

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
