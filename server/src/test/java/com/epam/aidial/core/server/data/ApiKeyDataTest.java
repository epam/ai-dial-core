package com.epam.aidial.core.server.data;

import com.epam.aidial.core.config.Alias;
import com.epam.aidial.core.config.Config;
import com.epam.aidial.core.config.Deployment;
import com.epam.aidial.core.config.Interceptor;
import com.epam.aidial.core.config.Model;
import com.epam.aidial.core.config.ResourceAccessType;
import com.epam.aidial.core.config.ResourceDependency;
import com.epam.aidial.core.server.Proxy;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.alias.AliasPass;
import com.epam.aidial.core.server.data.permission.PerRequestSharedData;
import com.epam.aidial.core.server.util.ProxyUtil;
import io.vertx.core.MultiMap;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

class ApiKeyDataTest {

    private static final String RESOURCE_TARGET = "applications/bucket/my%20app";
    private static final String MODEL_URL = "models/platform/gpt-4";

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
    void aliasPassGrantsReadOnTheResourceTarget() {
        ApiKeyData outgoing = new ApiKeyData();
        ApiKeyData.initFromContext(outgoing, context(perRequestKey(), aliasPass("res-alias", RESOURCE_TARGET), null));

        assertEquals(Map.of(RESOURCE_TARGET, ResourceAccessType.READ_ONLY), permissions(outgoing));
        assertTrue(outgoing.getAttachedDeployments().isEmpty(), "the grant is in perRequestSharedResources only");
        assertEquals("res-alias", outgoing.getAliasName());
    }

    @Test
    void aliasPassGrantsReadOnThePlatformUrlOfConfigTarget() {
        ApiKeyData outgoing = new ApiKeyData();
        ApiKeyData.initFromContext(outgoing, context(perRequestKey(), aliasPass("model-alias", MODEL_URL), null));

        assertEquals(Map.of(MODEL_URL, ResourceAccessType.READ_ONLY), permissions(outgoing));
    }

    @Test
    void grantIsKeptWhenPlainShareOfTheSameUrlIsSharedOn() {
        ApiKeyData incoming = perRequestKey();
        // the caller shared the target url to the alias through the per-request permission API
        Map<String, PerRequestSharedData> shared = new HashMap<>();
        shared.put(RESOURCE_TARGET, new PerRequestSharedData(Set.of(ResourceAccessType.WRITE)));
        incoming.getPerRequestReceivers().put("res-alias", shared);

        ApiKeyData outgoing = new ApiKeyData();
        ApiKeyData.initFromContext(outgoing, context(incoming, aliasPass("res-alias", RESOURCE_TARGET), null));

        assertEquals(Set.of(ResourceAccessType.READ, ResourceAccessType.WRITE), permissions(outgoing).get(RESOURCE_TARGET));
        assertEquals(Set.of(ResourceAccessType.WRITE), shared.get(RESOURCE_TARGET).permissions(), "the shared entry is not changed");
    }

    @Test
    void interceptorOfTheTargetGetsTheGrantAndTheAliasName() {
        ApiKeyData incoming = grantedKey(RESOURCE_TARGET);
        Interceptor interceptor = new Interceptor();
        interceptor.setName("interceptor");

        // chat completions name the chain's deployment by its encoded url, deployments POST by the decoded one
        for (String initialDeployment : List.of(RESOURCE_TARGET, "applications/bucket/my app")) {
            ApiKeyData outgoing = new ApiKeyData();
            ApiKeyData.initFromContext(outgoing, context(incoming, interceptor, initialDeployment));
            assertEquals(Map.of(RESOURCE_TARGET, ResourceAccessType.READ_ONLY), permissions(outgoing), initialDeployment);
            assertEquals("assistant", outgoing.getAliasName(), initialDeployment);
        }

        ApiKeyData unrelated = new ApiKeyData();
        ApiKeyData.initFromContext(unrelated, context(incoming, interceptor, "applications/bucket/other"));
        assertTrue(unrelated.getPerRequestSharedResources().isEmpty(), "the grant covers the target only");
        assertNull(unrelated.getAliasName());
    }

    @Test
    void otherDeploymentDoesNotGetTheGrantOfTheInitialDeployment() {
        // only an interceptor's hop acts for the initial deployment; another deployment's pass acts for itself
        Model other = new Model();
        other.setName("other");

        ApiKeyData outgoing = new ApiKeyData();
        ApiKeyData.initFromContext(outgoing, context(grantedKey(RESOURCE_TARGET), other, RESOURCE_TARGET));

        assertTrue(outgoing.getPerRequestSharedResources().isEmpty());
        assertNull(outgoing.getAliasName());
    }

    @Test
    void callbackOfConfigTargetGetsTheGrantAndTheAliasName() {
        ApiKeyData incoming = grantedKey(MODEL_URL);
        Model target = new Model();
        target.setName("gpt-4");

        ApiKeyData outgoing = new ApiKeyData();
        ApiKeyData.initFromContext(outgoing, context(incoming, target, null));

        assertEquals(Map.of(MODEL_URL, ResourceAccessType.READ_ONLY), permissions(outgoing));
        assertEquals("assistant", outgoing.getAliasName());
    }

    @Test
    void keyWithUnknownFieldsDecodes() {
        // a key stored by another Core version, as 0.50 with lentDeployment, must not fail to decode mid-request
        String json = """
                {"perRequestKey":"prk","lentDeployment":"gpt-4","perRequestSharedResources":{"models/platform/gpt-4":{"permissions":["READ"]}}}
                """;
        ApiKeyData decoded = ProxyUtil.convertToObject(json, ApiKeyData.class);

        assertNotNull(decoded);
        assertEquals(Map.of(MODEL_URL, ResourceAccessType.READ_ONLY), permissions(decoded));
        assertFalse(ProxyUtil.convertToString(decoded).contains("lentDeployment"));
    }

    private static Map<String, Set<ResourceAccessType>> permissions(ApiKeyData apiKeyData) {
        Map<String, Set<ResourceAccessType>> result = new HashMap<>();
        apiKeyData.getPerRequestSharedResources().forEach((url, data) -> result.put(url, data.permissions()));
        return result;
    }

    /**
     * The key an alias pass gives its target pass: the grant on {@code url} and the alias name.
     */
    private static ApiKeyData grantedKey(String url) {
        ApiKeyData apiKeyData = perRequestKey();
        apiKeyData.grant(url, ResourceAccessType.READ_ONLY);
        apiKeyData.setAliasName("assistant");
        return apiKeyData;
    }

    private static ApiKeyData perRequestKey() {
        ApiKeyData apiKeyData = new ApiKeyData();
        apiKeyData.setPerRequestKey("prk");
        apiKeyData.setExecutionPath(List.of("caller"));
        // non-empty headers are taken over as they are, so the request is not consulted
        apiKeyData.setHttpHeaders(Map.of(Proxy.HEADER_CONVERSATION_ID, "conversation"));
        return apiKeyData;
    }

    private static Deployment aliasPass(String name, String targetUrl) {
        Deployment pass = mock(Deployment.class, withSettings().extraInterfaces(AliasPass.class));
        when(pass.getName()).thenReturn(name);
        List<ResourceDependency> dependencies = List.of(new ResourceDependency().setKind(ResourceDependency.KIND)
                .setTarget(new ResourceDependency.Target().setUrl(targetUrl)).setAccess(ResourceAccessType.READ_ONLY).setRequired(true));
        when(((AliasPass) pass).getTargetDependencies()).thenReturn(dependencies);
        Alias alias = new Alias();
        alias.setName(name);
        alias.setUserRoles(Set.of("default-user"));
        when(((AliasPass) pass).getAlias()).thenReturn(alias);
        return pass;
    }

    private static ProxyContext context(ApiKeyData apiKeyData, Deployment deployment, String initialDeployment) {
        Config config = new Config();
        Model model = new Model();
        model.setName("gpt-4");
        config.setModels(Map.of("gpt-4", model));
        ProxyContext context = mock(ProxyContext.class);
        when(context.getApiKeyData()).thenReturn(apiKeyData);
        when(context.getDeployment()).thenReturn(deployment);
        when(context.getInitialDeployment()).thenReturn(initialDeployment);
        when(context.getConfig()).thenReturn(config);
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
        ApiKeyData aliasKey = perRequestKey();
        aliasKey.setMadeByAliasPass(true);

        ApiKeyData key = new ApiKeyData();
        ApiKeyData.initFromContext(key, context(aliasKey, aliasPass("res-alias", RESOURCE_TARGET), null));

        assertFalse(key.isMadeByAliasPass());
    }
}
