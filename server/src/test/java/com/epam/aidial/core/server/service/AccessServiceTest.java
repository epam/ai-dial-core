package com.epam.aidial.core.server.service;

import com.epam.aidial.core.config.Application;
import com.epam.aidial.core.config.Config;
import com.epam.aidial.core.config.Deployment;
import com.epam.aidial.core.config.ResourceAccessType;
import com.epam.aidial.core.server.Proxy;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.data.ApiKeyData;
import com.epam.aidial.core.server.data.ResourceTypes;
import com.epam.aidial.core.server.security.AccessService;
import com.epam.aidial.core.server.security.EncryptionService;
import com.epam.aidial.core.server.util.BucketBuilder;
import com.epam.aidial.core.storage.data.ResourceFolderMetadata;
import com.epam.aidial.core.storage.resource.ResourceDescriptor;
import com.epam.aidial.core.storage.service.ResourceService;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
public class AccessServiceTest {

    @Mock
    private EncryptionService encryptionService;

    @Mock
    private RuleService ruleService;

    @Mock
    private ShareService shareService;

    @Mock
    private ProxyContext context;

    @Test
    public void testFilterForbidden_PublicFolderWithHiddenOneItem_AdminAccess() {
        ProxyContext context = mock(ProxyContext.class);
        ApiKeyData apiKeyData = new ApiKeyData();
        when(context.getApiKeyData()).thenReturn(apiKeyData);

        ResourceDescriptor folderDescriptor = new ResourceDescriptor(
                ResourceTypes.FILE,
                "public/",
                List.of(),
                "public",
                "public/",
                true
        );

        ResourceDescriptor hiddenItem = new ResourceDescriptor(
                ResourceTypes.FILE,
                ".hidden_file.txt",
                List.of("public"),
                "public",
                "public/.hidden_file.txt",
                false
        );

        ResourceDescriptor visibleItem = new ResourceDescriptor(
                ResourceTypes.FILE,
                "visible_file.txt",
                List.of("public"),
                "public",
                "public/visible_file.txt",
                false
        );

        ResourceFolderMetadata folderMetadata = new ResourceFolderMetadata(folderDescriptor);
        folderMetadata.setItems(
                List.of(
                        new ResourceFolderMetadata().setPermissions(Set.of(ResourceAccessType.READ)).setUrl(hiddenItem.getUrl()),
                        new ResourceFolderMetadata().setPermissions(Set.of(ResourceAccessType.READ)).setUrl(visibleItem.getUrl())
                )
        );

        JsonObject settings = new JsonObject().put("admin", new JsonObject().put("rules", List.of()));
        ApplicationSchemaService applicationSchemaService = mock(ApplicationSchemaService.class);
        AccessService service = new AccessService(encryptionService, shareService, ruleService, applicationSchemaService, settings);
        service.filterForbidden(context, folderDescriptor, folderMetadata);

        assertEquals(2, folderMetadata.getItems().size());
    }

    @Test
    public void testFilterForbidden_PublicFolderWithHiddenOneItem_NoAdminAccess() {
        ProxyContext context = mock(ProxyContext.class);
        ApiKeyData apiKeyData = new ApiKeyData();
        when(context.getApiKeyData()).thenReturn(apiKeyData);

        ResourceDescriptor folderDescriptor = new ResourceDescriptor(
                ResourceTypes.FILE,
                "public/",
                List.of(),
                "public",
                "public/",
                true
        );

        ResourceDescriptor hiddenItem = new ResourceDescriptor(
                ResourceTypes.FILE,
                ".hidden_file.txt",
                List.of("public"),
                "public",
                "public/.hidden_file.txt",
                false
        );

        ResourceDescriptor visibleItem = new ResourceDescriptor(
                ResourceTypes.FILE,
                "visible_file.txt",
                List.of("public"),
                "public",
                "public/visible_file.txt",
                false
        );

        ResourceFolderMetadata folderMetadata = new ResourceFolderMetadata(folderDescriptor);
        folderMetadata.setItems(
                List.of(
                        new ResourceFolderMetadata().setPermissions(Set.of(ResourceAccessType.READ)).setUrl(hiddenItem.getUrl()),
                        new ResourceFolderMetadata().setPermissions(Set.of(ResourceAccessType.READ)).setUrl(visibleItem.getUrl())
                )
        );

        JsonObject settings = new JsonObject().put("admin", new JsonObject().put("rules", List.of(Map.of("source", "roles", "function", "EQUAL", "targets", List.of("admin")))));
        ApplicationSchemaService applicationSchemaService = mock(ApplicationSchemaService.class);
        AccessService service = new AccessService(encryptionService, shareService, ruleService, applicationSchemaService, settings);
        service.filterForbidden(context, folderDescriptor, folderMetadata);

        assertEquals(1, folderMetadata.getItems().size());
    }

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
    void shouldFilterPublishedSystemResourcesFromPublicAccess() {
        ProxyContext context = mock(ProxyContext.class);
        ApiKeyData apiKeyData = new ApiKeyData();
        apiKeyData.setPerRequestKey(null);
        when(context.getApiKeyData()).thenReturn(apiKeyData);
        when(context.getUserSub()).thenReturn("user");
        when(context.getSourceDeployment()).thenReturn("source");

        JsonObject settings = new JsonObject().put("admin", new JsonObject().put("rules", List.of(Map.of("source", "roles", "function", "EQUAL", "targets", List.of("admin")))));
        ApplicationSchemaService applicationSchemaService = mock(ApplicationSchemaService.class);
        var accessService = new AccessService(encryptionService, shareService, ruleService, applicationSchemaService, settings);

        ResourceDescriptor hiddenResource = new ResourceDescriptor(
                ResourceTypes.FILE,
                "file.json",
                List.of(".quick_app_name_0.0.1"),
                "bucket",
                "public/",
                false
        );
        ResourceDescriptor normalResource = new ResourceDescriptor(
                ResourceTypes.FILE,
                "normal_file.json",
                List.of("Documents"),
                "bucket",
                "public/",
                false
        );
        Set<ResourceDescriptor> resources = Set.of(hiddenResource, normalResource);
        Set<ResourceDescriptor> normalResources = Set.of(normalResource);

        when(ruleService.getAllowedPublicResources(context, normalResources))
                .thenReturn(normalResources);

        Map<ResourceDescriptor, Set<ResourceAccessType>> userPermissions =
                accessService.lookupPermissions(resources, context);

        assertFalse(userPermissions.get(hiddenResource).contains(ResourceAccessType.WRITE));
        assertFalse(userPermissions.get(hiddenResource).contains(ResourceAccessType.READ));
        assertFalse(userPermissions.get(normalResource).contains(ResourceAccessType.WRITE));
        assertTrue(userPermissions.get(normalResource).contains(ResourceAccessType.READ));
    }

    @Test
    void shouldAllowPublishedSystemResourcesForApplicationContext() {
        ProxyContext context = mock(ProxyContext.class);
        ApiKeyData apiKeyData = new ApiKeyData();
        when(context.getApiKeyData()).thenReturn(apiKeyData);
        when(context.getProject()).thenReturn("TEST-PROJECT");

        JsonObject settings = new JsonObject().put("admin", new JsonObject().put("rules", List.of()));
        ApplicationSchemaService applicationSchemaService = mock(ApplicationSchemaService.class);
        var accessService = new AccessService(encryptionService, shareService, ruleService, applicationSchemaService, settings);

        ResourceDescriptor hiddenResource = new ResourceDescriptor(
                ResourceTypes.FILE,
                "file.json",
                List.of(".quick_app_name_0.0.1"),
                "bucket",
                "public/",
                false
        );

        Set<ResourceDescriptor> resources = Set.of(hiddenResource);

        Map<ResourceDescriptor, Set<ResourceAccessType>> appPermissions =
                accessService.lookupPermissions(resources, context);

        assertTrue(appPermissions.get(hiddenResource).contains(ResourceAccessType.WRITE));
        assertTrue(appPermissions.get(hiddenResource).contains(ResourceAccessType.READ));
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
        when(context.getApiKeyData()).thenReturn(new ApiKeyData());
        ApplicationSchemaService applicationSchemaService = mock(ApplicationSchemaService.class);
        AccessService service = new AccessService(encryptionService, shareService, ruleService,
                applicationSchemaService,
                new JsonObject("""
                {
                 "admin": {
                    "rules": [{"source": "roles", "function": "EQUAL", "targets": ["admin"]}]
                 }
                }
                """));
        assertTrue(service.canCreateCodeApps(context));
    }

    @Test
    public void testCanCreateCodeApps_WhenCreateCodeAppRolesEmpty() {
        when(context.getApiKeyData()).thenReturn(new ApiKeyData());
        when(context.getUserRoles()).thenReturn(List.of("role1"));
        ApplicationSchemaService applicationSchemaService = mock(ApplicationSchemaService.class);
        AccessService service = new AccessService(encryptionService, shareService, ruleService,
                applicationSchemaService,
                new JsonObject("""
                {
                 "admin": {
                    "rules": [{"source": "roles", "function": "EQUAL", "targets": ["admin"]}]
                 },
                 "createCodeAppRoles": []
                }
                """));
        assertFalse(service.canCreateCodeApps(context));
    }

    @Test
    public void testCanCreateCodeApps_WhenCreateCodeAppRolesNotEmpty_Forbidden() {
        when(context.getApiKeyData()).thenReturn(new ApiKeyData());
        when(context.getUserRoles()).thenReturn(List.of("role1"));
        ApplicationSchemaService applicationSchemaService = mock(ApplicationSchemaService.class);
        AccessService service = new AccessService(encryptionService, shareService, ruleService,
                applicationSchemaService,
                new JsonObject("""
                {
                 "admin": {
                    "rules": [{"source": "roles", "function": "EQUAL", "targets": ["admin"]}]
                 },
                 "createCodeAppRoles": ["admin"]
                }
                """));
        assertFalse(service.canCreateCodeApps(context));
    }

    @Test
    public void testCanCreateCodeApps_WhenCreateCodeAppRolesNotEmpty_Allowed() {
        when(context.getApiKeyData()).thenReturn(new ApiKeyData());
        when(context.getUserRoles()).thenReturn(List.of("role1", "admin"));
        ApplicationSchemaService applicationSchemaService = mock(ApplicationSchemaService.class);
        AccessService service = new AccessService(encryptionService, shareService, ruleService,
                applicationSchemaService,
                new JsonObject("""
                {
                 "admin": {
                    "rules": [{"source": "roles", "function": "EQUAL", "targets": ["admin"]}]
                 },
                 "createCodeAppRoles": ["admin"]
                }
                """));
        assertTrue(service.canCreateCodeApps(context));
    }

    @Test
    public void testCanCreateCodeApps_WhenPerRequestKey() {
        ApiKeyData apiKeyData = new ApiKeyData();
        apiKeyData.setPerRequestKey("key");
        when(context.getApiKeyData()).thenReturn(apiKeyData);
        ApplicationSchemaService applicationSchemaService = mock(ApplicationSchemaService.class);
        AccessService service = new AccessService(encryptionService, shareService, ruleService,
                applicationSchemaService,
                new JsonObject("""
                {
                 "admin": {
                    "rules": [{"source": "roles", "function": "EQUAL", "targets": ["admin"]}]
                 },
                 "createCodeAppRoles": ["admin"]
                }
                """));
        assertTrue(service.canCreateCodeApps(context));
    }

    @Test
    public void testGetOwnResourcesAccessForChainedSchemaRichApplication_ApplicationHasTypeSchemaId() {
        Application application = mock(Application.class);
        when(application.hasApplicationTypeSchemaId()).thenReturn(true);
        when(context.getDeployment()).thenReturn(application);

        String initiatorBucket = "Users/user-sub-id/";
        ResourceDescriptor resource = new ResourceDescriptor(ResourceTypes.FILE, "file.json", List.of(), "bucket", initiatorBucket, false);

        ApplicationSchemaService applicationSchemaService = mock(ApplicationSchemaService.class);

        try (MockedStatic<BucketBuilder> bucketBuilderMock = mockStatic(BucketBuilder.class)) {

            List<ResourceDescriptor> applicationFiles = List.of(resource);
            when(applicationSchemaService.getFiles(application)).thenReturn(applicationFiles);

            bucketBuilderMock.when(() -> BucketBuilder.buildInitiatorBucket(context))
                    .thenReturn(initiatorBucket);

            AccessService accessService = new AccessService(encryptionService, shareService, ruleService,
                    applicationSchemaService,
                    new JsonObject("""
                            {
                             "admin": {
                                "rules": [{"source": "roles", "function": "EQUAL", "targets": ["admin"]}]
                             },
                             "createCodeAppRoles": ["admin"]
                            }
                            """));

            Map<ResourceDescriptor, Set<ResourceAccessType>> result =
                    accessService.getOwnResourcesAccessForChainedSchemaRichApplication(Set.of(resource), context);

            assertTrue(result.containsKey(resource));
            assertEquals(ResourceAccessType.READ_ONLY, result.get(resource));
        }
    }

    @Test
    public void testGetOwnResourcesAccessForChainedSchemaRichApplication_ApplicationHasNoTypeSchemaId() {
        Application application = mock(Application.class);
        when(application.hasApplicationTypeSchemaId()).thenReturn(false);
        when(context.getDeployment()).thenReturn(application);
        ResourceDescriptor resource = new ResourceDescriptor(ResourceTypes.FILE, "file.json", List.of(), "bucket", "Users/user/", false);
        ApplicationSchemaService applicationSchemaService = mock(ApplicationSchemaService.class);
        AccessService accessService = new AccessService(encryptionService, shareService, ruleService,
                applicationSchemaService,
                new JsonObject("""
                {
                 "admin": {
                    "rules": [{"source": "roles", "function": "EQUAL", "targets": ["admin"]}]
                 },
                 "createCodeAppRoles": ["admin"]
                }
                """));
        Map<ResourceDescriptor, Set<ResourceAccessType>> result = accessService.getOwnResourcesAccessForChainedSchemaRichApplication(Set.of(resource), context);

        assertTrue(result.isEmpty());
    }

    @Test
    public void testGetOwnResourcesAccessForChainedSchemaRichApplication_NotAnApplication() {
        Deployment application = mock(Deployment.class);
        when(context.getDeployment()).thenReturn(application);
        ResourceDescriptor resource = new ResourceDescriptor(ResourceTypes.FILE, "file.json", List.of(), "bucket", "Users/user/", false);
        ApplicationSchemaService applicationSchemaService = mock(ApplicationSchemaService.class);
        AccessService accessService = new AccessService(encryptionService, shareService, ruleService,
                applicationSchemaService,
                new JsonObject("""
                {
                 "admin": {
                    "rules": [{"source": "roles", "function": "EQUAL", "targets": ["admin"]}]
                 },
                 "createCodeAppRoles": ["admin"]
                }
                """));
        Map<ResourceDescriptor, Set<ResourceAccessType>> result = accessService.getOwnResourcesAccessForChainedSchemaRichApplication(Set.of(resource), context);

        assertTrue(result.isEmpty());
    }
}
