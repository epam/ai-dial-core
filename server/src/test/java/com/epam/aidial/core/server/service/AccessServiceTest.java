package com.epam.aidial.core.server.service;

import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.data.ApiKeyData;
import com.epam.aidial.core.server.data.ResourceTypes;
import com.epam.aidial.core.server.security.AccessService;
import com.epam.aidial.core.storage.data.ResourceAccessType;
import com.epam.aidial.core.storage.resource.ResourceDescriptor;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class AccessServiceTest {

    @Test
    public void testGetAppResourceAccess_RootFolder() {
        ProxyContext context = mock(ProxyContext.class);
        ApiKeyData apiKeyData = new ApiKeyData();
        apiKeyData.setPerRequestKey("key");
        when(context.getApiKeyData()).thenReturn(apiKeyData);
        when(context.getUserSub()).thenReturn("user");
        when(context.getSourceDeployment()).thenReturn("source");
        ResourceDescriptor descriptor = new ResourceDescriptor(ResourceTypes.FILE, null, List.of(), "bucket", "Users/user/", true);

        Map<ResourceDescriptor, Set<ResourceAccessType>> result = AccessService.getAppResourceAccess(Set.of(descriptor), context);

        assertTrue(result.isEmpty());
    }

    @Test
    public void testGetAppResourceAccess_Folder() {
        ProxyContext context = mock(ProxyContext.class);
        ApiKeyData apiKeyData = new ApiKeyData();
        apiKeyData.setPerRequestKey("key");
        when(context.getApiKeyData()).thenReturn(apiKeyData);
        when(context.getUserSub()).thenReturn("user");
        when(context.getSourceDeployment()).thenReturn("source");
        ResourceDescriptor descriptor = new ResourceDescriptor(ResourceTypes.FILE, null, List.of("folder"), "bucket", "Users/user/", true);

        Map<ResourceDescriptor, Set<ResourceAccessType>> result = AccessService.getAppResourceAccess(Set.of(descriptor), context);

        assertTrue(result.isEmpty());
    }

    @Test
    public void testGetAppResourceAccess_File() {
        ProxyContext context = mock(ProxyContext.class);
        ApiKeyData apiKeyData = new ApiKeyData();
        apiKeyData.setPerRequestKey("key");
        when(context.getApiKeyData()).thenReturn(apiKeyData);
        when(context.getUserSub()).thenReturn("user");
        when(context.getSourceDeployment()).thenReturn("source");
        ResourceDescriptor descriptor = new ResourceDescriptor(ResourceTypes.FILE, "file.json", List.of(), "bucket", "Users/user/", false);

        Map<ResourceDescriptor, Set<ResourceAccessType>> result = AccessService.getAppResourceAccess(Set.of(descriptor), context);

        assertTrue(result.isEmpty());
    }

    @Test
    public void testGetAppResourceAccess_AppDataFile() {
        ProxyContext context = mock(ProxyContext.class);
        ApiKeyData apiKeyData = new ApiKeyData();
        apiKeyData.setPerRequestKey("key");
        when(context.getApiKeyData()).thenReturn(apiKeyData);
        when(context.getUserSub()).thenReturn("user");
        when(context.getSourceDeployment()).thenReturn("app");
        ResourceDescriptor descriptor = new ResourceDescriptor(ResourceTypes.FILE, "file.json", List.of("appdata", "app"), "bucket", "Users/user/", false);

        Map<ResourceDescriptor, Set<ResourceAccessType>> result = AccessService.getAppResourceAccess(Set.of(descriptor), context);

        assertTrue(result.containsKey(descriptor));
        assertEquals(ResourceAccessType.ALL, result.get(descriptor));
    }
}
