package com.epam.aidial.core.server.security;

import com.epam.aidial.core.config.Application;
import com.epam.aidial.core.config.Deployment;
import com.epam.aidial.core.config.ResourceAccessType;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.data.ApiKeyData;
import com.epam.aidial.core.server.service.ApplicationSchemaService;
import com.epam.aidial.core.server.service.RuleService;
import com.epam.aidial.core.server.service.ShareService;
import com.epam.aidial.core.server.util.BucketBuilder;
import com.epam.aidial.core.server.util.ProxyUtil;
import com.epam.aidial.core.storage.resource.ResourceDescriptor;
import com.epam.aidial.core.storage.resource.ResourceTypes;
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
    public void testGetAppResourceAccess_RootFolder() {
        ProxyContext context = mock(ProxyContext.class);
        ApiKeyData apiKeyData = new ApiKeyData();
        apiKeyData.setPerRequestKey("key");
        when(context.getApiKeyData()).thenReturn(apiKeyData);
        when(context.getUserId()).thenReturn("user");
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
        when(context.getUserId()).thenReturn("user");
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
        when(context.getUserId()).thenReturn("user");
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
        when(context.getUserId()).thenReturn("user");
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
        when(context.getUserId()).thenReturn("user");
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
        when(context.getUserId()).thenReturn("user");
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

    @Test
    public void testGetOwnResourcesAccessForChainedSchemaRichApplication_DeclaredDeployment() {
        Application application = mock(Application.class);
        when(application.hasApplicationTypeSchemaId()).thenReturn(true);
        when(context.getDeployment()).thenReturn(application);

        String initiatorBucket = "Users/user-sub-id/";
        ResourceDescriptor subAgent =
                new ResourceDescriptor(ResourceTypes.APPLICATION, "sub-agent__0.0.1", List.of(), "bucket", initiatorBucket, false);

        ApplicationSchemaService applicationSchemaService = mock(ApplicationSchemaService.class);
        when(applicationSchemaService.getDeployments(application)).thenReturn(List.of(subAgent));

        try (MockedStatic<BucketBuilder> bucketBuilderMock = mockStatic(BucketBuilder.class)) {
            bucketBuilderMock.when(() -> BucketBuilder.buildInitiatorBucket(context)).thenReturn(initiatorBucket);

            Map<ResourceDescriptor, Set<ResourceAccessType>> result = accessService(applicationSchemaService)
                    .getOwnResourcesAccessForChainedSchemaRichApplication(Set.of(subAgent), context);

            assertEquals(ResourceAccessType.READ_ONLY, result.get(subAgent));
        }
    }

    @Test
    public void testGetOwnResourcesAccessForChainedSchemaRichApplication_DeclaredToolSet() {
        Application application = mock(Application.class);
        when(application.hasApplicationTypeSchemaId()).thenReturn(true);
        when(context.getDeployment()).thenReturn(application);

        String initiatorBucket = "Users/user-sub-id/";
        ResourceDescriptor toolSet =
                new ResourceDescriptor(ResourceTypes.TOOL_SET, "my-tool-set", List.of(), "bucket", initiatorBucket, false);

        ApplicationSchemaService applicationSchemaService = mock(ApplicationSchemaService.class);
        when(applicationSchemaService.getDeployments(application)).thenReturn(List.of(toolSet));

        try (MockedStatic<BucketBuilder> bucketBuilderMock = mockStatic(BucketBuilder.class)) {
            bucketBuilderMock.when(() -> BucketBuilder.buildInitiatorBucket(context)).thenReturn(initiatorBucket);

            Map<ResourceDescriptor, Set<ResourceAccessType>> result = accessService(applicationSchemaService)
                    .getOwnResourcesAccessForChainedSchemaRichApplication(Set.of(toolSet), context);

            assertEquals(ResourceAccessType.READ_ONLY, result.get(toolSet));
        }
    }

    @Test
    public void testGetOwnResourcesAccessForChainedSchemaRichApplication_DeploymentNotDeclared() {
        Application application = mock(Application.class);
        when(application.hasApplicationTypeSchemaId()).thenReturn(true);
        when(context.getDeployment()).thenReturn(application);

        String initiatorBucket = "Users/user-sub-id/";
        ResourceDescriptor subAgent =
                new ResourceDescriptor(ResourceTypes.APPLICATION, "sub-agent__0.0.1", List.of(), "bucket", initiatorBucket, false);

        ApplicationSchemaService applicationSchemaService = mock(ApplicationSchemaService.class);
        when(applicationSchemaService.getDeployments(application)).thenReturn(List.of());

        try (MockedStatic<BucketBuilder> bucketBuilderMock = mockStatic(BucketBuilder.class)) {
            bucketBuilderMock.when(() -> BucketBuilder.buildInitiatorBucket(context)).thenReturn(initiatorBucket);

            Map<ResourceDescriptor, Set<ResourceAccessType>> result = accessService(applicationSchemaService)
                    .getOwnResourcesAccessForChainedSchemaRichApplication(Set.of(subAgent), context);

            assertTrue(result.isEmpty());
        }
    }

    @Test
    public void testGetOwnResourcesAccessForChainedSchemaRichApplication_DeploymentFromAnotherBucket() {
        Application application = mock(Application.class);
        when(application.hasApplicationTypeSchemaId()).thenReturn(true);
        when(context.getDeployment()).thenReturn(application);

        ResourceDescriptor subAgent =
                new ResourceDescriptor(ResourceTypes.APPLICATION, "sub-agent__0.0.1", List.of(), "bucket", "Users/another-user/", false);

        ApplicationSchemaService applicationSchemaService = mock(ApplicationSchemaService.class);

        try (MockedStatic<BucketBuilder> bucketBuilderMock = mockStatic(BucketBuilder.class)) {
            bucketBuilderMock.when(() -> BucketBuilder.buildInitiatorBucket(context)).thenReturn("Users/user-sub-id/");

            Map<ResourceDescriptor, Set<ResourceAccessType>> result = accessService(applicationSchemaService)
                    .getOwnResourcesAccessForChainedSchemaRichApplication(Set.of(subAgent), context);

            assertTrue(result.isEmpty());
        }
    }

    @Test
    public void testGetOwnResourcesAccessForChainedSchemaRichApplication_FilesAndDeploymentsTogether() {
        Application application = mock(Application.class);
        when(application.hasApplicationTypeSchemaId()).thenReturn(true);
        when(context.getDeployment()).thenReturn(application);

        String initiatorBucket = "Users/user-sub-id/";
        ResourceDescriptor file = new ResourceDescriptor(ResourceTypes.FILE, "file.json", List.of(), "bucket", initiatorBucket, false);
        ResourceDescriptor subAgent =
                new ResourceDescriptor(ResourceTypes.APPLICATION, "sub-agent__0.0.1", List.of(), "bucket", initiatorBucket, false);

        ApplicationSchemaService applicationSchemaService = mock(ApplicationSchemaService.class);
        when(applicationSchemaService.getFiles(application)).thenReturn(List.of(file));
        when(applicationSchemaService.getDeployments(application)).thenReturn(List.of(subAgent));

        try (MockedStatic<BucketBuilder> bucketBuilderMock = mockStatic(BucketBuilder.class)) {
            bucketBuilderMock.when(() -> BucketBuilder.buildInitiatorBucket(context)).thenReturn(initiatorBucket);

            Map<ResourceDescriptor, Set<ResourceAccessType>> result = accessService(applicationSchemaService)
                    .getOwnResourcesAccessForChainedSchemaRichApplication(Set.of(file, subAgent), context);

            assertEquals(ResourceAccessType.READ_ONLY, result.get(file));
            assertEquals(ResourceAccessType.READ_ONLY, result.get(subAgent));
        }
    }

    private AccessService accessService(ApplicationSchemaService applicationSchemaService) {
        return new AccessService(encryptionService, shareService, ruleService, applicationSchemaService,
                new JsonObject("""
                        {
                         "admin": {
                            "rules": [{"source": "roles", "function": "EQUAL", "targets": ["admin"]}]
                         },
                         "createCodeAppRoles": ["admin"]
                        }
                        """));
    }

    @Test
    public void testGetAdminAccess_WhenPublicResource() {
        ResourceDescriptor resource = new ResourceDescriptor(ResourceTypes.FILE, "file.json", List.of(), "bucket", "public/", false);
        ApplicationSchemaService applicationSchemaService = mock(ApplicationSchemaService.class);
        when(context.getUserRoles()).thenReturn(List.of("admin"));
        when(context.getApiKeyData()).thenReturn(new ApiKeyData());
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
        Map<ResourceDescriptor, Set<ResourceAccessType>> result = accessService.getAdminAccess(Set.of(resource), context);

        assertFalse(result.isEmpty());
        assertEquals(Map.of(resource, ResourceAccessType.ALL), result);
    }

    @Test
    public void testGetAdminAccess_WhenReviewResource() {
        String reviewLocation = "/Users/sub/publications/123/";
        ResourceDescriptor resource = new ResourceDescriptor(ResourceTypes.FILE, "file.json", List.of(), "bucket", reviewLocation, false);
        ApplicationSchemaService applicationSchemaService = mock(ApplicationSchemaService.class);
        when(context.getUserRoles()).thenReturn(List.of("admin"));
        when(context.getApiKeyData()).thenReturn(new ApiKeyData());
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
        Map<ResourceDescriptor, Set<ResourceAccessType>> result = accessService.getAdminAccess(Set.of(resource), context);

        assertFalse(result.isEmpty());
        assertEquals(Map.of(resource, ResourceAccessType.ALL), result);
    }

    @Test
    public void testGetAdminAccess_WhenPrivateResource() {
        ResourceDescriptor resource = new ResourceDescriptor(ResourceTypes.FILE, "file.json", List.of(), "bucket", "/Users/sub", false);
        ApplicationSchemaService applicationSchemaService = mock(ApplicationSchemaService.class);
        ExtractedClaims extractedClaims = new ExtractedClaims("sub", List.of("admin"), "hash",
                ProxyUtil.MAPPER.createObjectNode(), null, "userName");
        when(context.getExtractedClaims()).thenReturn(extractedClaims);
        when(context.getApiKeyData()).thenReturn(new ApiKeyData());
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
        Map<ResourceDescriptor, Set<ResourceAccessType>> result = accessService.getAdminAccess(Set.of(resource), context);

        assertTrue(result.isEmpty());
    }

    @Test
    public void testGetAdminAccess_WhenSourceFolderOfCodeApp() {
        ResourceDescriptor resource = new ResourceDescriptor(ResourceTypes.FILE, "app.py", List.of(), "bucket", "public/deployments/123/", false);
        ApplicationSchemaService applicationSchemaService = mock(ApplicationSchemaService.class);
        when(context.getUserRoles()).thenReturn(List.of("admin"));
        when(context.getApiKeyData()).thenReturn(new ApiKeyData());
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
        Map<ResourceDescriptor, Set<ResourceAccessType>> result = accessService.getAdminAccess(Set.of(resource), context);

        assertFalse(result.isEmpty());
        assertEquals(Map.of(resource, ResourceAccessType.ALL), result);
    }

    @Test
    public void testHasExplicitAdminAccess_EmptyRulesDenies() {
        ApplicationSchemaService applicationSchemaService = mock(ApplicationSchemaService.class);
        AccessService accessService = new AccessService(encryptionService, shareService, ruleService,
                applicationSchemaService,
                new JsonObject("""
                {
                 "admin": {"rules": []}
                }
                """));

        assertFalse(accessService.hasExplicitAdminAccess(context));
    }

    @Test
    public void testHasExplicitAdminAccess_ConfiguredAndMatching() {
        when(context.getApiKeyData()).thenReturn(new ApiKeyData());
        when(context.getUserRoles()).thenReturn(List.of("admin"));
        ApplicationSchemaService applicationSchemaService = mock(ApplicationSchemaService.class);
        AccessService accessService = new AccessService(encryptionService, shareService, ruleService,
                applicationSchemaService,
                new JsonObject("""
                {
                 "admin": {"rules": [{"source": "roles", "function": "EQUAL", "targets": ["admin"]}]}
                }
                """));

        assertTrue(accessService.hasExplicitAdminAccess(context));
    }

    @Test
    public void testHasExplicitAdminAccess_ConfiguredAndNonMatching() {
        when(context.getApiKeyData()).thenReturn(new ApiKeyData());
        when(context.getUserRoles()).thenReturn(List.of("user"));
        ApplicationSchemaService applicationSchemaService = mock(ApplicationSchemaService.class);
        AccessService accessService = new AccessService(encryptionService, shareService, ruleService,
                applicationSchemaService,
                new JsonObject("""
                {
                 "admin": {"rules": [{"source": "roles", "function": "EQUAL", "targets": ["admin"]}]}
                }
                """));

        assertFalse(accessService.hasExplicitAdminAccess(context));
    }

    @Test
    public void testHasExplicitAdminAccess_MissingAdminBlockDoesNotThrow() {
        ApplicationSchemaService applicationSchemaService = mock(ApplicationSchemaService.class);
        AccessService missingAdmin = new AccessService(encryptionService, shareService, ruleService,
                applicationSchemaService, new JsonObject("{}"));
        AccessService missingRules = new AccessService(encryptionService, shareService, ruleService,
                applicationSchemaService, new JsonObject("{\"admin\": {}}"));

        assertFalse(missingAdmin.hasExplicitAdminAccess(context));
        assertFalse(missingRules.hasExplicitAdminAccess(context));
    }

    @Test
    public void testGetGlobalReaderAccess_PrivateResource() {
        ResourceDescriptor resource = new ResourceDescriptor(ResourceTypes.FILE, "file.json", List.of(), "bucket", "Users/user/", false);
        ApplicationSchemaService applicationSchemaService = mock(ApplicationSchemaService.class);
        when(context.getUserRoles()).thenReturn(List.of("global-reader"));
        when(context.getApiKeyData()).thenReturn(new ApiKeyData());
        AccessService accessService = new AccessService(encryptionService, shareService, ruleService,
                applicationSchemaService,
                new JsonObject("""
                {
                 "admin": {
                    "rules": [{"source": "roles", "function": "EQUAL", "targets": ["admin"]}]
                 },
                 "globalReader": {
                    "rules": [{"source": "roles", "function": "EQUAL", "targets": ["global-reader"]}]
                 }
                }
                """));

        Map<ResourceDescriptor, Set<ResourceAccessType>> result = accessService.getGlobalReaderAccess(Set.of(resource), context);

        assertFalse(result.isEmpty());
        assertEquals(Map.of(resource, ResourceAccessType.READ_ONLY), result);
    }

    @Test
    public void testGetGlobalReaderAccess_NoRole() {
        ResourceDescriptor resource = new ResourceDescriptor(ResourceTypes.FILE, "file.json", List.of(), "bucket", "Users/user/", false);
        ApplicationSchemaService applicationSchemaService = mock(ApplicationSchemaService.class);
        when(context.getUserRoles()).thenReturn(List.of("regular-user"));
        when(context.getApiKeyData()).thenReturn(new ApiKeyData());
        AccessService accessService = new AccessService(encryptionService, shareService, ruleService,
                applicationSchemaService,
                new JsonObject("""
                {
                 "admin": {
                    "rules": [{"source": "roles", "function": "EQUAL", "targets": ["admin"]}]
                 },
                 "globalReader": {
                    "rules": [{"source": "roles", "function": "EQUAL", "targets": ["global-reader"]}]
                 }
                }
                """));

        Map<ResourceDescriptor, Set<ResourceAccessType>> result = accessService.getGlobalReaderAccess(Set.of(resource), context);

        assertTrue(result.isEmpty());
    }

    @Test
    public void testGetGlobalReaderAccess_ApplicationContextExcluded() {
        ResourceDescriptor resource = new ResourceDescriptor(ResourceTypes.FILE, "file.json", List.of(), "bucket", "Users/user/", false);
        ApplicationSchemaService applicationSchemaService = mock(ApplicationSchemaService.class);
        ApiKeyData apiKeyData = new ApiKeyData();
        apiKeyData.setPerRequestKey("per-request-key");
        when(context.getApiKeyData()).thenReturn(apiKeyData);
        AccessService accessService = new AccessService(encryptionService, shareService, ruleService,
                applicationSchemaService,
                new JsonObject("""
                {
                 "admin": {
                    "rules": [{"source": "roles", "function": "EQUAL", "targets": ["admin"]}]
                 },
                 "globalReader": {
                    "rules": [{"source": "roles", "function": "EQUAL", "targets": ["global-reader"]}]
                 }
                }
                """));

        Map<ResourceDescriptor, Set<ResourceAccessType>> result = accessService.getGlobalReaderAccess(Set.of(resource), context);

        assertTrue(result.isEmpty());
    }

    @Test
    public void testGetGlobalReaderAccess_MissingConfigReturnsEmpty() {
        ResourceDescriptor resource = new ResourceDescriptor(ResourceTypes.FILE, "file.json", List.of(), "bucket", "Users/user/", false);
        ApplicationSchemaService applicationSchemaService = mock(ApplicationSchemaService.class);
        AccessService accessService = new AccessService(encryptionService, shareService, ruleService,
                applicationSchemaService,
                new JsonObject("""
                {
                 "admin": {
                    "rules": [{"source": "roles", "function": "EQUAL", "targets": ["admin"]}]
                 }
                }
                """));

        Map<ResourceDescriptor, Set<ResourceAccessType>> result = accessService.getGlobalReaderAccess(Set.of(resource), context);

        assertTrue(result.isEmpty());
    }
}
