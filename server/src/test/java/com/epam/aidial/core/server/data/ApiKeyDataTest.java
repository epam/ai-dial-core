package com.epam.aidial.core.server.data;

import com.epam.aidial.core.config.Deployment;
import com.epam.aidial.core.config.Model;
import com.epam.aidial.core.config.ResourceAccessType;
import com.epam.aidial.core.server.Proxy;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.alias.AliasPass;
import com.epam.aidial.core.server.data.permission.PerRequestSharedData;
import io.vertx.core.MultiMap;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

class ApiKeyDataTest {

    private static final String RESOURCE_TARGET = "applications/bucket/my%20app";

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
    void aliasTargetSharedByTheCallerStaysLent() {
        ApiKeyData incoming = perRequestKey();
        // the caller shared the target url to the alias through the per-request permissions API
        incoming.getPerRequestReceivers().put("res-alias", sharing(RESOURCE_TARGET, ResourceAccessType.ALL));
        Deployment pass = mock(Deployment.class, withSettings().extraInterfaces(AliasPass.class));
        when(pass.getName()).thenReturn("res-alias");
        when(((AliasPass) pass).getTargetName()).thenReturn(RESOURCE_TARGET);

        ApiKeyData outgoing = new ApiKeyData();
        ApiKeyData.initFromContext(outgoing, context(incoming, pass, null));

        PerRequestSharedData grant = outgoing.getPerRequestSharedResources().get(RESOURCE_TARGET);
        assertTrue(grant.lent(), "the shared entry must not replace the lent grant");
        assertEquals(ResourceAccessType.ALL, grant.permissions(), "the lent grant must not drop the shared rights");
    }

    @Test
    void interceptorOfAResourceTargetCarriesTheLending() {
        ApiKeyData incoming = perRequestKey();
        ApiKeyData.bakeLentGrant(incoming.getPerRequestSharedResources(), RESOURCE_TARGET);
        // a plain grant on the same url shared to the interceptor must not replace the carried lent grant
        incoming.getPerRequestReceivers().put("interceptor", sharing(RESOURCE_TARGET, ResourceAccessType.READ_ONLY));
        Model interceptor = new Model();
        interceptor.setName("interceptor");

        // the initial deployment is the decoded url, the lent grant is keyed by the encoded one
        ApiKeyData outgoing = new ApiKeyData();
        ApiKeyData.initFromContext(outgoing, context(incoming, interceptor, "applications/bucket/my app"));
        assertTrue(ApiKeyData.hasLentGrant(outgoing.getPerRequestSharedResources(), RESOURCE_TARGET));

        ApiKeyData unrelated = new ApiKeyData();
        ApiKeyData.initFromContext(unrelated, context(incoming, interceptor, "applications/bucket/other"));
        assertFalse(ApiKeyData.hasLentGrant(unrelated.getPerRequestSharedResources(), RESOURCE_TARGET),
                "the lending covers the target only");
    }

    private static ApiKeyData perRequestKey() {
        ApiKeyData apiKeyData = new ApiKeyData();
        apiKeyData.setPerRequestKey("prk");
        apiKeyData.setExecutionPath(List.of("caller"));
        // non-empty headers are taken over as they are, so the request is not consulted
        apiKeyData.setHttpHeaders(Map.of(Proxy.HEADER_CONVERSATION_ID, "conversation"));
        return apiKeyData;
    }

    private static Map<String, PerRequestSharedData> sharing(String url, Set<ResourceAccessType> permissions) {
        Map<String, PerRequestSharedData> resources = new HashMap<>();
        resources.put(url, new PerRequestSharedData(permissions));
        return resources;
    }

    private static ProxyContext context(ApiKeyData apiKeyData, Deployment deployment, String initialDeployment) {
        ProxyContext context = mock(ProxyContext.class);
        when(context.getApiKeyData()).thenReturn(apiKeyData);
        when(context.getDeployment()).thenReturn(deployment);
        when(context.getInitialDeployment()).thenReturn(initialDeployment);
        return context;
    }
}
