package com.epam.aidial.core.server.data;

import com.epam.aidial.core.server.Proxy;
import com.epam.aidial.core.server.ProxyContext;
import io.vertx.core.MultiMap;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ApiKeyDataTest {

    @Test
    void collectHeadersCaseInsensitive() {
        ApiKeyData apiKeyData = new ApiKeyData();
        MultiMap header = MultiMap.caseInsensitiveMultiMap()
                .add(Proxy.HEADER_CONVERSATION_ID.toLowerCase(), Proxy.HEADER_CONVERSATION_ID)
                .add(Proxy.HEADER_JOB_TITLE.toUpperCase(), Proxy.HEADER_JOB_TITLE);
        ProxyContext context = mock(ProxyContext.class, Mockito.RETURNS_DEEP_STUBS);
        when(context.getApiKeyData().getHttpHeaders()).thenReturn(Map.of());
        when(context.getRequest().headers()).thenReturn(header);

        ApiKeyData.initFromContext(apiKeyData, context);

        Map<String, String> httpHeaders = apiKeyData.getHttpHeaders();
        assertEquals(2, httpHeaders.size());
    }

    @Test
    void initFromContextAppendsDeploymentToCallerExecutionPath() {
        ApiKeyData callerKey = perRequestKey(List.of("orchestrator"));
        ProxyContext context = contextCalledBy(callerKey, "tool");
        ApiKeyData apiKeyData = new ApiKeyData();

        ApiKeyData.initFromContext(apiKeyData, context);

        assertEquals(List.of("orchestrator", "tool"), apiKeyData.getExecutionPath());
        assertEquals(List.of("orchestrator"), callerKey.getExecutionPath());
    }

    @Test
    void initFromContextStartsExecutionPathWhenCallerKeyHasNone() {
        ApiKeyData callerKey = perRequestKey(null);
        ProxyContext context = contextCalledBy(callerKey, "tool");
        ApiKeyData apiKeyData = new ApiKeyData();

        ApiKeyData.initFromContext(apiKeyData, context);

        assertEquals(List.of("tool"), apiKeyData.getExecutionPath());
    }

    @Test
    void createFromContextReturnsKeyDataWithExecutionPath() {
        ProxyContext context = contextCalledBy(perRequestKey(List.of("orchestrator")), "tool");

        ApiKeyData apiKeyData = ApiKeyData.createFromContext(context);

        assertEquals(List.of("orchestrator", "tool"), apiKeyData.getExecutionPath());
        assertEquals("tool", apiKeyData.getSourceDeployment());
    }

    private static ApiKeyData perRequestKey(List<String> executionPath) {
        ApiKeyData callerKey = new ApiKeyData();
        callerKey.setPerRequestKey("caller-key");
        callerKey.setExecutionPath(executionPath);
        return callerKey;
    }

    private static ProxyContext contextCalledBy(ApiKeyData callerKey, String deploymentName) {
        ProxyContext context = mock(ProxyContext.class, Mockito.RETURNS_DEEP_STUBS);
        when(context.getApiKeyData()).thenReturn(callerKey);
        when(context.getRequest().headers()).thenReturn(MultiMap.caseInsensitiveMultiMap());
        when(context.getDeployment().getName()).thenReturn(deploymentName);
        return context;
    }
}
