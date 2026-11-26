package com.epam.aidial.core.server.data;

import com.epam.aidial.core.config.Deployment;
import com.epam.aidial.core.server.Proxy;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.util.ProxyUtil;
import io.vertx.core.MultiMap;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
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
    void initFromContextKeepsTheCallerKeyForItsReceivers() {
        // the key store copies the caller's permissions for these receivers once more from the Redis master
        ApiKeyData caller = new ApiKeyData();
        caller.setPerRequestKey("caller-key");
        caller.setExecutionPath(List.of("caller-app"));
        ProxyContext context = context(caller);
        when(context.getDeployment().getName()).thenReturn("app");
        when(context.getInitialDeployment()).thenReturn("initial-app");
        ApiKeyData apiKeyData = new ApiKeyData();

        ApiKeyData.initFromContext(apiKeyData, context);

        assertEquals("caller-key", apiKeyData.getCallerKey());
        assertEquals(List.of("app", "initial-app"), apiKeyData.getCallerReceivers());
    }

    @Test
    void initFromContextKeepsNoCallerKeyWithoutPermissionsToCopy() {
        ApiKeyData projectKey = new ApiKeyData();
        ProxyContext projectContext = context(projectKey);
        when(projectContext.getDeployment().getName()).thenReturn("app");
        ApiKeyData fromProjectKey = new ApiKeyData();
        ApiKeyData caller = new ApiKeyData();
        caller.setPerRequestKey("caller-key");
        caller.setExecutionPath(List.of("caller-app"));
        ProxyContext contextWithoutReceiver = context(caller);
        when(contextWithoutReceiver.getDeployment()).thenReturn(null);
        when(contextWithoutReceiver.getRoute()).thenReturn(null);
        ApiKeyData withoutReceiver = new ApiKeyData();
        // a pass served in process has the caller's key from memory
        ProxyContext childPass = context(caller);
        when(childPass.getDeployment().getName()).thenReturn("app");
        when(childPass.isChildPass()).thenReturn(true);
        ApiKeyData inChildPass = new ApiKeyData();

        ApiKeyData.initFromContext(fromProjectKey, projectContext);
        ApiKeyData.initFromContext(withoutReceiver, contextWithoutReceiver);
        ApiKeyData.initFromContext(inChildPass, childPass);

        assertNull(fromProjectKey.getCallerKey());
        assertEquals(List.of(), fromProjectKey.getCallerReceivers());
        assertNull(withoutReceiver.getCallerKey());
        assertNull(inChildPass.getCallerKey());
        assertEquals(List.of(), withoutReceiver.getCallerReceivers());
    }

    private static ProxyContext context(ApiKeyData apiKeyData) {
        ProxyContext context = mock(ProxyContext.class, Mockito.RETURNS_DEEP_STUBS);
        when(context.getApiKeyData()).thenReturn(apiKeyData);
        when(context.getDecision()).thenReturn(null);
        when(context.getKey()).thenReturn(null);
        when(context.getExtractedClaims()).thenReturn(null);
        when(context.getInitialDeployment()).thenReturn(null);
        when(context.isChildPass()).thenReturn(false);
        return context;
    }

    @Test
    void flagIsNeverStored() {
        // a Core without the field rejects a stored key that has it, during a rollout and after a rollback, so a flagged
        // key is stored as an unflagged one, and a target pass reached over HTTP reads it without the flag
        ApiKeyData key = new ApiKeyData();
        key.setMadeByAliasPass(true);

        String json = ProxyUtil.convertToString(key);

        assertFalse(json.contains("madeByAliasPass"), json);
        assertFalse(ProxyUtil.convertToObject(json, ApiKeyData.class).isMadeByAliasPass());
    }

    @Test
    void initFromContextDoesNotCopyTheFlag() {
        // the target pass hands its call on, to an interceptor in front of it or as its own callback
        ApiKeyData aliasKey = new ApiKeyData();
        aliasKey.setPerRequestKey("alias-key");
        aliasKey.setLentDeployment("m1");
        aliasKey.setMadeByAliasPass(true);
        aliasKey.setExecutionPath(List.of("alias-m1"));
        Deployment target = mock(Deployment.class);
        when(target.getName()).thenReturn("m1");
        ProxyContext context = contextOf(target, aliasKey);

        ApiKeyData key = new ApiKeyData();
        ApiKeyData.initFromContext(key, context);

        assertEquals("m1", key.getLentDeployment());
        assertFalse(key.isMadeByAliasPass());
    }

    private static ProxyContext contextOf(Deployment deployment, ApiKeyData apiKeyData) {
        ProxyContext context = mock(ProxyContext.class, Mockito.RETURNS_DEEP_STUBS);
        when(context.getApiKeyData()).thenReturn(apiKeyData);
        when(context.getRequest().headers()).thenReturn(MultiMap.caseInsensitiveMultiMap());
        when(context.getDeployment()).thenReturn(deployment);
        when(context.getDecision()).thenReturn(null);
        when(context.getInitialDeployment()).thenReturn(null);
        return context;
    }
}
