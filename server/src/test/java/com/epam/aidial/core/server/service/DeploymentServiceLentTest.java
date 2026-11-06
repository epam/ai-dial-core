package com.epam.aidial.core.server.service;

import com.epam.aidial.core.config.Model;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.data.ApiKeyData;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DeploymentServiceLentTest {

    @Test
    void callIsLentOnlyForTheTargetOfThePerRequestKey() {
        ProxyContext context = mock(ProxyContext.class);
        ApiKeyData apiKeyData = new ApiKeyData();
        when(context.getApiKeyData()).thenReturn(apiKeyData);
        Model target = new Model();
        target.setName("gpt-4");
        Model other = new Model();
        other.setName("other");

        apiKeyData.setLentDeployment("gpt-4");
        assertFalse(DeploymentService.isLent(context, target), "a lending without a per-request key");

        apiKeyData.setPerRequestKey("key");
        assertTrue(DeploymentService.isLent(context, target));
        assertFalse(DeploymentService.isLent(context, other));

        apiKeyData.setLentDeployment(null);
        assertFalse(DeploymentService.isLent(context, target));
    }
}
