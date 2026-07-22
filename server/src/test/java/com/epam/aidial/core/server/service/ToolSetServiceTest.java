package com.epam.aidial.core.server.service;

import com.epam.aidial.core.config.AuthenticationType;
import com.epam.aidial.core.config.Config;
import com.epam.aidial.core.config.ResourceAuthSettings;
import com.epam.aidial.core.config.ToolSet;
import com.epam.aidial.core.credentials.data.credentials.BucketInfo;
import com.epam.aidial.core.credentials.data.credentials.CredentialsLocator;
import com.epam.aidial.core.credentials.service.ResourceAuthSettingsEncryptionService;
import com.epam.aidial.core.credentials.service.ResourceAuthSettingsService;
import com.epam.aidial.core.server.Proxy;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.data.ApiKeyData;
import com.epam.aidial.core.server.security.EncryptionService;
import com.epam.aidial.core.server.util.ProxyUtil;
import com.epam.aidial.core.storage.data.ResourceItemMetadata;
import com.epam.aidial.core.storage.resource.ResourceDescriptor;
import com.epam.aidial.core.storage.resource.ResourceTypes;
import com.epam.aidial.core.storage.service.ResourceService;
import com.epam.aidial.core.storage.util.EtagHeader;
import org.apache.commons.lang3.tuple.Pair;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ToolSetServiceTest {

    @Mock
    private ResourceService resourceService;
    @Mock
    private ResourceAuthSettingsService resourceAuthSettingsService;
    @Mock
    private ResourceAuthSettingsEncryptionService resourceAuthSettingsEncryptionService;
    @Mock
    private EncryptionService encryptionService;
    @Mock
    private ProxyContext context;
    @Mock
    private Proxy proxy;
    @Mock
    private CatalogSchemaService catalogSchemaService;

    @InjectMocks
    private ToolSetService toolSetService;

    @Test
    void testPutToolSet_ShouldEncryptAuthSettings() {
        String expectedOutputJson = "expectedOutputJson";
        ResourceAuthSettings resourceAuthSettings = new ResourceAuthSettings();
        resourceAuthSettings.setClientSecret("plainClientSecret");
        resourceAuthSettings.setAuthenticationType(AuthenticationType.OAUTH);
        ToolSet toolSet = createToolSet();
        toolSet.setAuthSettings(resourceAuthSettings);

        MockedStatic<ProxyUtil> proxyUtil = Mockito.mockStatic(ProxyUtil.class);
        proxyUtil.when(() -> ProxyUtil.convertToObject(any(String.class), eq(ToolSet.class)))
                .thenReturn(toolSet);
        proxyUtil.when(() -> ProxyUtil.convertToString(any()))
                .thenReturn(expectedOutputJson);
        doAnswer(answer -> {
            ResourceAuthSettings authSettings = answer.getArgument(2);
            authSettings.setClientSecret("ENCRYPTED_CLIENT_SECRET");
            return null;
        }).when(resourceAuthSettingsEncryptionService).encrypt(any(), any(), any());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Function<String, String>> lambdaCaptor = ArgumentCaptor.forClass(Function.class);

        EtagHeader etag = EtagHeader.ANY;
        String author = "author";
        ResourceDescriptor resource = mock(ResourceDescriptor.class);
        when(resource.getUrl()).thenReturn("url");
        when(resource.getBucketName()).thenReturn("bucket");
        when(resource.getBucketLocation()).thenReturn("location");

        when(resourceService.computeResource(
                eq(resource), eq(etag), eq(author), lambdaCaptor.capture()))
                .thenReturn(mock(ResourceItemMetadata.class));

        // WHEN
        toolSetService.putToolSet(resource, etag, author, toolSet, false);
        lambdaCaptor.getValue().apply("input");

        // THEN
        verify(resourceAuthSettingsEncryptionService).encrypt(
                eq(resource.getUrl()),
                eq(new BucketInfo("bucket", "location")),
                eq(toolSet.getAuthSettings())
        );

        ArgumentCaptor<ToolSet> toolsetCaptor = ArgumentCaptor.forClass(ToolSet.class);
        proxyUtil.verify(() -> ProxyUtil.convertToString(toolsetCaptor.capture()));
        assertNotNull(toolsetCaptor.getValue());
        ToolSet actualToolSet = toolsetCaptor.getValue();
        assertNotNull(actualToolSet.getAuthSettings());
        assertEquals("ENCRYPTED_CLIENT_SECRET", actualToolSet.getAuthSettings().getClientSecret());

        proxyUtil.close();
    }

    @Test
    void testGetToolSet_ShouldEncryptAuthSettings() {
        // Given
        ResourceAuthSettings resourceAuthSettings = new ResourceAuthSettings();
        resourceAuthSettings.setClientSecret("ENCRYPTED_CLIENT_SECRET");
        resourceAuthSettings.setAuthenticationType(AuthenticationType.OAUTH);
        ToolSet toolSet = createToolSet();
        toolSet.setAuthSettings(resourceAuthSettings);

        MockedStatic<ProxyUtil> proxyUtil = Mockito.mockStatic(ProxyUtil.class);
        proxyUtil.when(() -> ProxyUtil.convertToObject(any(String.class), eq(ToolSet.class)))
                .thenReturn(toolSet);

        ResourceItemMetadata metadata = mock(ResourceItemMetadata.class);
        ResourceDescriptor resource = mock(ResourceDescriptor.class);
        when(resource.isFolder()).thenReturn(false);
        when(resource.getType()).thenReturn(ResourceTypes.TOOL_SET);
        when(resourceService.getResourceWithMetadata(resource, EtagHeader.ANY))
                .thenReturn(Pair.of(metadata, "json"));

        // When
        Pair<ResourceItemMetadata, ToolSet> result =
                toolSetService.getToolSet(resource, EtagHeader.ANY);

        // Then
        verifyNoInteractions(resourceAuthSettingsEncryptionService);
        assertNotNull(result);
        assertNotNull(result.getValue());
        ToolSet actualToolSet = result.getValue();
        assertNotNull(actualToolSet.getAuthSettings());
        assertEquals("ENCRYPTED_CLIENT_SECRET", actualToolSet.getAuthSettings().getClientSecret());

        proxyUtil.close();
    }

    @Test
    void testSetResourceAuthStatuses() {
        // Given
        String toolSetId = "toolsets/test-toolset";
        ToolSet toolSet = createToolSet();
        toolSet.setName(toolSetId);

        ResourceAuthSettings resourceAuthSettings = ResourceAuthSettings.builder()
                .authenticationType(AuthenticationType.OAUTH)
                .clientId("clientId")
                .clientSecret("clientSecret")
                .build();

        toolSet.setAuthSettings(resourceAuthSettings);

        when(context.getProxy()).thenReturn(proxy);
        when(proxy.getEncryptionService()).thenReturn(encryptionService);
        when(context.getConfig()).thenReturn(mock(Config.class));
        when(context.getApiKeyData()).thenReturn(mock(ApiKeyData.class));
        when(context.getUserId()).thenReturn("user-123");
        when(encryptionService.encrypt("Users/user-123/")).thenReturn("encrypted-user-123");

        // When
        toolSetService.setResourceAuthStatuses(context, toolSet, toolSetId);

        // Then
        assertNotNull(toolSet.getAuthSettings().getClientId());
        assertEquals("clientSecret", toolSet.getAuthSettings().getClientSecret());

        ArgumentCaptor<CredentialsLocator> credentialsLocatorCaptor = ArgumentCaptor.forClass(CredentialsLocator.class);
        verify(resourceAuthSettingsService).setResourceAuthStatuses(credentialsLocatorCaptor.capture(), any(), any());
        CredentialsLocator credentialsLocator = credentialsLocatorCaptor.getValue();
        assertEquals(toolSetId, credentialsLocator.getResourceId());
        assertEquals(2, credentialsLocator.getBuckets().size());
        Set<String> bucketNames = credentialsLocator.getBuckets().values().stream()
                .map(BucketInfo::name)
                .collect(Collectors.toSet());
        assertEquals(Set.of("public", "encrypted-user-123"), bucketNames);
    }

    @Test
    void testSetResourceAuthStatuses_doesNotMutateClientSecret() {
        // Given
        String toolSetId = "toolsets/test-toolset";
        ToolSet toolSet = createToolSet();
        toolSet.setName(toolSetId);

        ResourceAuthSettings resourceAuthSettings = ResourceAuthSettings.builder()
                .authenticationType(AuthenticationType.OAUTH)
                .clientId("clientId")
                .clientSecret("clientSecret")
                .codeVerifier("codeVerifier")
                .build();

        toolSet.setAuthSettings(resourceAuthSettings);

        when(context.getProxy()).thenReturn(proxy);
        when(proxy.getEncryptionService()).thenReturn(encryptionService);
        when(context.getConfig()).thenReturn(mock(Config.class));
        when(context.getApiKeyData()).thenReturn(mock(ApiKeyData.class));
        when(context.getUserId()).thenReturn("user-123");
        when(encryptionService.encrypt("Users/user-123/")).thenReturn("encrypted-user-123");

        // When - call setResourceAuthStatuses multiple times (simulating multiple API requests)
        toolSetService.setResourceAuthStatuses(context, toolSet, toolSetId);
        toolSetService.setResourceAuthStatuses(context, toolSet, toolSetId);

        // Then - clientSecret and codeVerifier must be preserved after multiple calls
        assertEquals("clientSecret", toolSet.getAuthSettings().getClientSecret());
        assertEquals("codeVerifier", toolSet.getAuthSettings().getCodeVerifier());
    }

    private static ToolSet createToolSet() {
        ToolSet toolSet = new ToolSet();
        toolSet.setEndpoint("endpoint");
        return toolSet;
    }

}
