package com.epam.aidial.core.server.service;

import com.epam.aidial.core.config.Config;
import com.epam.aidial.core.config.Model;
import com.epam.aidial.core.config.ResourceAccessType;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.data.ApiKeyData;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DeploymentServiceGrantTest {

    @Test
    void callIsGrantedByReadOnThePlatformUrlOfConfigDeployment() {
        Model target = model("gpt-4");
        Model other = model("other");
        ApiKeyData apiKeyData = new ApiKeyData();
        ProxyContext context = context(apiKeyData, target, other);

        apiKeyData.grant("models/platform/gpt-4", ResourceAccessType.READ_ONLY);
        assertFalse(DeploymentService.isGranted(context, target), "a grant without a per-request key");

        apiKeyData.setPerRequestKey("key");
        assertTrue(DeploymentService.isGranted(context, target));
        assertFalse(DeploymentService.isGranted(context, other));
    }

    @Test
    void grantMatchesTheExactUrlWithRead() {
        Model target = model("gpt-4");
        ApiKeyData apiKeyData = new ApiKeyData();
        apiKeyData.setPerRequestKey("key");
        ProxyContext context = context(apiKeyData, target);

        apiKeyData.grant("models/platform/", ResourceAccessType.READ_ONLY);
        assertFalse(DeploymentService.isGranted(context, target), "a folder grant opens no deployment");
        apiKeyData.grant("models/platform/gpt-4", Set.of(ResourceAccessType.WRITE));
        assertFalse(DeploymentService.isGranted(context, target), "only READ is the right to call");
        apiKeyData.grant("gpt-4", ResourceAccessType.READ_ONLY);
        assertFalse(DeploymentService.isGranted(context, target), "a config deployment is granted by its platform url");
    }

    @Test
    void resourceDeploymentIsGrantedByItsUrl() {
        Model target = model("applications/bucket/app");
        ApiKeyData apiKeyData = new ApiKeyData();
        apiKeyData.setPerRequestKey("key");
        apiKeyData.grant("applications/bucket/app", ResourceAccessType.READ_ONLY);

        assertTrue(DeploymentService.isGranted(context(apiKeyData), target));
        assertEquals("applications/bucket/app", DeploymentGrants.urlOf(new Config(), "applications/bucket/app"));
    }

    private static Model model(String name) {
        Model model = new Model();
        model.setName(name);
        return model;
    }

    private static ProxyContext context(ApiKeyData apiKeyData, Model... configModels) {
        Config config = new Config();
        Map<String, Model> models = new HashMap<>();
        for (Model model : configModels) {
            models.put(model.getName(), model);
        }
        config.setModels(models);
        ProxyContext context = mock(ProxyContext.class);
        when(context.getApiKeyData()).thenReturn(apiKeyData);
        when(context.getConfig()).thenReturn(config);
        return context;
    }
}
