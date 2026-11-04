package com.epam.aidial.core.server.security;

import com.epam.aidial.core.config.Application;
import com.epam.aidial.core.config.Deployment;
import com.epam.aidial.core.config.ResourceAccessType;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.data.ApiKeyData;
import com.epam.aidial.core.server.data.AutoSharedData;
import com.epam.aidial.core.server.data.permission.PerRequestSharedData;
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

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
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

    private void chainedHop() {
        ApiKeyData chained = new ApiKeyData();
        chained.setPerRequestKey("prk");
        chained.setSourceDeployment("app1");
        lenient().when(context.getApiKeyData()).thenReturn(chained);
        lenient().when(context.getSourceDeployment()).thenReturn("app1");
        when(context.getUserId()).thenReturn("user-sub");
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

    // ---- lookupInitiatorPermissions ----

    @Test
    public void testLookupInitiatorPermissions_ResolvesOwnBucketAtAnyChainDepth() {
        // on a chained hop the human's own bucket grants ALL
        ResourceDescriptor resource = new ResourceDescriptor(ResourceTypes.FILE, "file.json", List.of(), "bucket", "Users/user-sub/", false);
        ApplicationSchemaService applicationSchemaService = mock(ApplicationSchemaService.class);
        chainedHop();
        AccessService accessService = accessService(applicationSchemaService);

        assertEquals(ResourceAccessType.ALL, accessService.lookupInitiatorPermissions(Set.of(resource), context).get(resource));
        // control: the general chain asks for the key holder's sandbox and finds nothing here
        assertTrue(accessService.lookupPermissions(Set.of(resource), context).get(resource).isEmpty());
    }

    @Test
    public void testLookupInitiatorPermissions_ExcludesTheCallingAppsOwnSandbox() {
        // on a chained hop the calling app's own sandbox (Keys/app1/) is not the human's bucket
        ResourceDescriptor resource = new ResourceDescriptor(ResourceTypes.FILE, "file.json", List.of(), "bucket", "Keys/app1/", false);
        ApplicationSchemaService applicationSchemaService = mock(ApplicationSchemaService.class);
        chainedHop();
        AccessService accessService = accessService(applicationSchemaService);

        assertTrue(accessService.lookupInitiatorPermissions(Set.of(resource), context).get(resource).isEmpty());
        // control: in the general chain the key holder owns its sandbox
        assertEquals(ResourceAccessType.ALL, accessService.lookupPermissions(Set.of(resource), context).get(resource));
    }

    @Test
    public void testLookupInitiatorPermissions_ExcludesTheCallingKeysOwnGrants() {
        // grants held by the calling key (per-request, attached file, attached folder) must not satisfy the reach check
        ResourceDescriptor perRequest = new ResourceDescriptor(ResourceTypes.FILE, "secret.json", List.of(), "bucket", "Users/other-user/", false);
        ResourceDescriptor attachedFile = new ResourceDescriptor(ResourceTypes.FILE, "attached.json", List.of(), "bucket", "Users/other-user/", false);
        ResourceDescriptor inAttachedFolder = new ResourceDescriptor(ResourceTypes.FILE, "inside.json", List.of("shared-folder"), "bucket", "Users/other-user/", false);
        Set<ResourceDescriptor> resources = Set.of(perRequest, attachedFile, inAttachedFolder);
        ApplicationSchemaService applicationSchemaService = mock(ApplicationSchemaService.class);
        ApiKeyData apiKeyData = new ApiKeyData();
        apiKeyData.setPerRequestKey("prk");
        apiKeyData.setSourceDeployment("app1");
        apiKeyData.getPerRequestSharedResources().put(perRequest.getUrl(),
                new PerRequestSharedData(new HashSet<>(Set.of(ResourceAccessType.READ, ResourceAccessType.WRITE))));
        apiKeyData.getAttachedFiles().put(attachedFile.getUrl(), new AutoSharedData(ResourceAccessType.READ_ONLY));
        apiKeyData.getAttachedFolders().put("files/bucket/shared-folder/", new AutoSharedData(ResourceAccessType.READ_ONLY));
        lenient().when(context.getApiKeyData()).thenReturn(apiKeyData);
        when(context.getUserId()).thenReturn("user-sub");
        AccessService accessService = accessService(applicationSchemaService);

        Map<ResourceDescriptor, Set<ResourceAccessType>> generalChain = accessService.lookupPermissions(resources, context);
        Map<ResourceDescriptor, Set<ResourceAccessType>> initiator =
                accessService.lookupInitiatorPermissions(resources, context);

        for (ResourceDescriptor resource : resources) {
            assertFalse(generalChain.get(resource).isEmpty(), () -> "the general chain must see the key's grant on " + resource.getUrl());
            assertTrue(initiator.get(resource).isEmpty(), () -> "no key-held grant may count for " + resource.getUrl());
        }
    }

    @Test
    public void testLookupInitiatorPermissions_GrantsWhatIsSharedWithTheUser() {
        // on a chained hop a resource shared with the human resolves with the shared rights (asked for the human's bucket)
        ResourceDescriptor resource = new ResourceDescriptor(ResourceTypes.FILE, "report.json", List.of(), "bucket", "Users/alice/", false);
        ApplicationSchemaService applicationSchemaService = mock(ApplicationSchemaService.class);
        chainedHop();
        when(shareService.getPermissions(any(), eq("Users/user-sub/"), any()))
                .thenReturn(Map.of(resource, Set.of(ResourceAccessType.READ, ResourceAccessType.WRITE)));
        AccessService accessService = accessService(applicationSchemaService);

        assertEquals(Set.of(ResourceAccessType.READ, ResourceAccessType.WRITE),
                accessService.lookupInitiatorPermissions(Set.of(resource), context).get(resource));
    }

    @Test
    public void testLookupInitiatorPermissions_GrantsOnlyReadOnPublicResources() {
        // public resources allowed by the rules resolve READ only; others nothing — a WRITE dependency on public never grants
        ResourceDescriptor allowed = new ResourceDescriptor(ResourceTypes.FILE, "doc.json", List.of("open"), "public", "public/", false);
        ResourceDescriptor restricted = new ResourceDescriptor(ResourceTypes.FILE, "doc.json", List.of("finance"), "public", "public/", false);
        ApplicationSchemaService applicationSchemaService = mock(ApplicationSchemaService.class);
        chainedHop();
        when(ruleService.getAllowedPublicResources(eq(context), any())).thenReturn(Set.of(allowed));
        AccessService accessService = accessService(applicationSchemaService);

        Map<ResourceDescriptor, Set<ResourceAccessType>> result =
                accessService.lookupInitiatorPermissions(Set.of(allowed, restricted), context);

        assertEquals(ResourceAccessType.READ_ONLY, result.get(allowed));
        assertTrue(result.get(restricted).isEmpty());
    }

    @Test
    public void testLookupPermissions_PerRequestGrantsUnionAcrossCoveringEntries() {
        // rights from every covering entry count, not only the first one the map yields
        ResourceDescriptor inNestedFolder = new ResourceDescriptor(ResourceTypes.FILE, "r.json", List.of("docs", "out"), "bucket", "Users/other-user/", false);
        ResourceDescriptor exactAndFolder = new ResourceDescriptor(ResourceTypes.FILE, "x.json", List.of("docs"), "bucket", "Users/other-user/", false);
        ApplicationSchemaService applicationSchemaService = mock(ApplicationSchemaService.class);
        ApiKeyData apiKeyData = new ApiKeyData();
        apiKeyData.setPerRequestKey("prk");
        apiKeyData.setSourceDeployment("app1");
        apiKeyData.getPerRequestSharedResources().put("files/bucket/docs/", new PerRequestSharedData(new HashSet<>(Set.of(ResourceAccessType.READ))));
        apiKeyData.getPerRequestSharedResources().put("files/bucket/docs/out/", new PerRequestSharedData(new HashSet<>(Set.of(ResourceAccessType.WRITE))));
        apiKeyData.getPerRequestSharedResources().put(exactAndFolder.getUrl(), new PerRequestSharedData(new HashSet<>(Set.of(ResourceAccessType.WRITE))));
        lenient().when(context.getApiKeyData()).thenReturn(apiKeyData);
        lenient().when(context.getSourceDeployment()).thenReturn("app1");
        when(context.getUserId()).thenReturn("user-sub");
        AccessService accessService = accessService(applicationSchemaService);

        Map<ResourceDescriptor, Set<ResourceAccessType>> result = accessService.lookupPermissions(Set.of(inNestedFolder, exactAndFolder), context);

        assertEquals(Set.of(ResourceAccessType.READ, ResourceAccessType.WRITE), result.get(inNestedFolder));
        assertEquals(Set.of(ResourceAccessType.READ, ResourceAccessType.WRITE), result.get(exactAndFolder));
    }

    @Test
    public void testLookupPermissions_UnaffectedByTheNewRuleList() {
        // the general chain still grants own-bucket access via buildUserBucket
        ResourceDescriptor resource = new ResourceDescriptor(ResourceTypes.FILE, "file.json", List.of(), "bucket", "Users/user-sub/", false);
        ApplicationSchemaService applicationSchemaService = mock(ApplicationSchemaService.class);
        when(context.getApiKeyData()).thenReturn(new ApiKeyData());
        when(context.getUserId()).thenReturn("user-sub");
        AccessService accessService = accessService(applicationSchemaService);

        Map<ResourceDescriptor, Set<ResourceAccessType>> result =
                accessService.lookupPermissions(Set.of(resource), context);

        assertEquals(ResourceAccessType.ALL, result.get(resource));
    }

    @Test
    public void testLookupInitiatorPermissions_AdminStandingDoesNotCount() {
        // deliberate narrowing: an admin gets ALL on a public resource in the general chain, only public READ here
        ResourceDescriptor resource = new ResourceDescriptor(ResourceTypes.FILE, "doc.json", List.of("policies"), "public", "public/", false);
        ApplicationSchemaService applicationSchemaService = mock(ApplicationSchemaService.class);
        lenient().when(context.getApiKeyData()).thenReturn(new ApiKeyData());
        lenient().when(context.getUserId()).thenReturn("admin-sub");
        lenient().when(context.getUserRoles()).thenReturn(List.of("admin"));
        lenient().when(ruleService.getAllowedPublicResources(eq(context), any())).thenReturn(Set.of(resource));
        AccessService accessService = accessService(applicationSchemaService);

        assertEquals(ResourceAccessType.ALL, accessService.lookupPermissions(Set.of(resource), context).get(resource));
        assertEquals(ResourceAccessType.READ_ONLY,
                accessService.lookupInitiatorPermissions(Set.of(resource), context).get(resource));
    }

    @Test
    public void testLookupInitiatorPermissions_ReviewStandingDoesNotCount() {
        // deliberate narrowing: a reviewer may READ a publication under review in the general chain, not here
        ResourceDescriptor resource = new ResourceDescriptor(ResourceTypes.FILE, "draft.json", List.of(), "bucket", "Users/user-sub/publications/pub1/", false);
        ApplicationSchemaService applicationSchemaService = mock(ApplicationSchemaService.class);
        chainedHop();
        AccessService accessService = accessService(applicationSchemaService);

        assertEquals(ResourceAccessType.READ_ONLY, accessService.lookupPermissions(Set.of(resource), context).get(resource));
        assertTrue(accessService.lookupInitiatorPermissions(Set.of(resource), context).get(resource).isEmpty());
    }

    @Test
    public void testLookupInitiatorPermissions_ExcludesTheCallingAppsSelfAccess() {
        // on a chained hop the calling app may read its own definition; that must not count as the human's reach
        ResourceDescriptor resource = new ResourceDescriptor(ResourceTypes.APPLICATION, "app1", List.of(), "bucket", "Users/other-user/", false);
        ApplicationSchemaService applicationSchemaService = mock(ApplicationSchemaService.class);
        chainedHop();
        lenient().when(context.getDecodedSourceDeployment()).thenReturn(resource.getDecodedUrl());
        AccessService accessService = accessService(applicationSchemaService);

        assertEquals(ResourceAccessType.READ_ONLY, accessService.lookupPermissions(Set.of(resource), context).get(resource));
        assertTrue(accessService.lookupInitiatorPermissions(Set.of(resource), context).get(resource).isEmpty());
    }

    @Test
    public void testLookupInitiatorPermissions_GlobalReaderStandingDoesNotCount() {
        // deliberate narrowing: a global reader may READ another user's bucket in the general chain, not here
        ResourceDescriptor resource = new ResourceDescriptor(ResourceTypes.FILE, "report.json", List.of(), "bucket", "Users/alice/", false);
        ApplicationSchemaService applicationSchemaService = mock(ApplicationSchemaService.class);
        lenient().when(context.getApiKeyData()).thenReturn(new ApiKeyData());
        lenient().when(context.getUserId()).thenReturn("reader-sub");
        lenient().when(context.getUserRoles()).thenReturn(List.of("global-reader"));
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

        assertEquals(ResourceAccessType.READ_ONLY, accessService.lookupPermissions(Set.of(resource), context).get(resource));
        assertTrue(accessService.lookupInitiatorPermissions(Set.of(resource), context).get(resource).isEmpty());
    }
}
