package com.epam.aidial.core.server.service;

import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.data.ApiKeyData;
import com.epam.aidial.core.server.data.ResourceTypes;
import com.epam.aidial.core.server.security.AccessService;
import com.epam.aidial.core.server.security.EncryptionService;
import com.epam.aidial.core.storage.data.ResourceAccessType;
import com.epam.aidial.core.storage.resource.ResourceDescriptor;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
public class AccessServiceTest {

    @Mock
    private EncryptionService encryptionService;

    @Mock
    private RuleService ruleService;

    @Mock
    private ShareService shareService;


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

    @Test
    public void testGetAppResourceAccess_AppDataFolder() {
        ProxyContext context = mock(ProxyContext.class);
        ApiKeyData apiKeyData = new ApiKeyData();
        apiKeyData.setPerRequestKey("key");
        when(context.getApiKeyData()).thenReturn(apiKeyData);
        when(context.getUserSub()).thenReturn("user");
        when(context.getSourceDeployment()).thenReturn("app");
        ResourceDescriptor descriptor = new ResourceDescriptor(ResourceTypes.FILE, "app", List.of("appdata"), "bucket", "Users/user/", true);

        Map<ResourceDescriptor, Set<ResourceAccessType>> result = AccessService.getAppResourceAccess(Set.of(descriptor), context);

        assertTrue(result.containsKey(descriptor));
        assertEquals(ResourceAccessType.ALL, result.get(descriptor));
    }

    @Test
    public void testGetAppResourceAccess_DeploymentNameHasSpecialChars() {
        ProxyContext context = mock(ProxyContext.class);
        ApiKeyData apiKeyData = new ApiKeyData();
        apiKeyData.setPerRequestKey("key");
        when(context.getApiKeyData()).thenReturn(apiKeyData);
        when(context.getUserSub()).thenReturn("user");
        when(context.getSourceDeployment()).thenReturn("test app");
        ResourceDescriptor descriptor = new ResourceDescriptor(ResourceTypes.FILE, "file.json", List.of("appdata", "test app"), "bucket", "Users/user/", false);

        Map<ResourceDescriptor, Set<ResourceAccessType>> result = AccessService.getAppResourceAccess(Set.of(descriptor), context);

        assertTrue(result.containsKey(descriptor));
        assertEquals(ResourceAccessType.ALL, result.get(descriptor));
    }

    @Test
    public void testCanCreateCodeApps_WhenCreateCodeAppRolesUndefined() {
        AccessService service = new AccessService(encryptionService, shareService, ruleService, new JsonObject("""
                {
                 "admin": {
                    "rules": [{"source": "roles", "function": "EQUAL", "targets": ["admin"]}]
                 }
                }
                """));
        assertTrue(service.canCreateCodeApps(List.of("role1")));
    }

    @Test
    public void testCanCreateCodeApps_WhenCreateCodeAppRolesEmpty() {
        AccessService service = new AccessService(encryptionService, shareService, ruleService, new JsonObject("""
                {
                 "admin": {
                    "rules": [{"source": "roles", "function": "EQUAL", "targets": ["admin"]}]
                 },
                 "createCodeAppRoles": []
                }
                """));
        assertFalse(service.canCreateCodeApps(List.of("role1")));
    }

    @Test
    public void testCanCreateCodeApps_WhenCreateCodeAppRolesNotEmpty() {
        AccessService service = new AccessService(encryptionService, shareService, ruleService, new JsonObject("""
                {
                 "admin": {
                    "rules": [{"source": "roles", "function": "EQUAL", "targets": ["admin"]}]
                 },
                 "createCodeAppRoles": ["admin"]
                }
                """));
        assertFalse(service.canCreateCodeApps(List.of("role1")));
        assertTrue(service.canCreateCodeApps(List.of("role1", "admin")));
    }
}
