package com.epam.aidial.core.server.service;

import com.epam.aidial.core.config.Model;
import com.epam.aidial.core.config.ResourceAccessType;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.data.ApiKeyData;
import com.epam.aidial.core.server.data.permission.PerRequestSharedData;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

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

        // lent grant without a per-request key must not count
        Map<String, PerRequestSharedData> grants = new HashMap<>();
        grants.put("gpt-4", new PerRequestSharedData(ResourceAccessType.READ_ONLY, true));
        apiKeyData.setPerRequestSharedResources(grants);
        assertFalse(DeploymentService.isLent(context, target), "a lending without a per-request key");

        apiKeyData.setPerRequestKey("key");
        assertTrue(DeploymentService.isLent(context, target));
        assertFalse(DeploymentService.isLent(context, other));

        // clearing the grant removes the lent status
        apiKeyData.setPerRequestSharedResources(new HashMap<>());
        assertFalse(DeploymentService.isLent(context, target));
    }

    @Test
    void regularReadGrantDoesNotConferLentStatus() {
        ProxyContext context = mock(ProxyContext.class);
        ApiKeyData apiKeyData = new ApiKeyData();
        when(context.getApiKeyData()).thenReturn(apiKeyData);
        Model target = new Model();
        target.setName("gpt-4");

        apiKeyData.setPerRequestKey("key");
        // regular READ grant (lent=false, e.g. from resource dependencies)
        apiKeyData.getPerRequestSharedResources().put("gpt-4",
                new PerRequestSharedData(ResourceAccessType.READ_ONLY));
        assertFalse(DeploymentService.isLent(context, target), "plain READ must not bypass accessibleByPerRequestKey");
    }
}
