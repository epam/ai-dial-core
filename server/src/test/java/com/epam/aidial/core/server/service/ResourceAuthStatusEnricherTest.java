package com.epam.aidial.core.server.service;

import com.epam.aidial.core.config.AuthenticationType;
import com.epam.aidial.core.config.Config;
import com.epam.aidial.core.config.ResourceAuthSettings;
import com.epam.aidial.core.config.ToolSet;
import com.epam.aidial.core.credentials.data.credentials.BucketInfo;
import com.epam.aidial.core.credentials.data.credentials.CredentialsLocator;
import com.epam.aidial.core.credentials.service.ResourceAuthSettingsService;
import com.epam.aidial.core.server.Proxy;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.data.ApiKeyData;
import com.epam.aidial.core.server.security.EncryptionService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ResourceAuthStatusEnricherTest {

    @Mock
    private ProxyContext context;
    @Mock
    private Proxy proxy;
    @Mock
    private EncryptionService encryptionService;
    @Mock
    private ResourceAuthSettingsService resourceAuthSettingsService;

    @Test
    void testEnrichToolSetResolvesLocatorFromDecodedId() {
        // Given
        String toolSetId = "toolsets/test-toolset";
        ToolSet toolSet = new ToolSet();
        toolSet.setName(toolSetId);
        toolSet.setAuthSettings(ResourceAuthSettings.builder()
                .authenticationType(AuthenticationType.OAUTH)
                .clientId("clientId")
                .clientSecret("clientSecret")
                .build());

        when(context.getProxy()).thenReturn(proxy);
        when(proxy.getEncryptionService()).thenReturn(encryptionService);
        when(context.getConfig()).thenReturn(mock(Config.class));
        when(context.getApiKeyData()).thenReturn(mock(ApiKeyData.class));
        when(context.getUserId()).thenReturn("user-123");
        when(encryptionService.encrypt("Users/user-123/")).thenReturn("encrypted-user-123");

        // When
        new ResourceAuthStatusEnricher(context, resourceAuthSettingsService).enrichToolSet(toolSetId, toolSet);

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
    void testEnrichToolSetDoesNotMutateClientSecret() {
        // Given
        String toolSetId = "toolsets/test-toolset";
        ToolSet toolSet = new ToolSet();
        toolSet.setName(toolSetId);
        toolSet.setAuthSettings(ResourceAuthSettings.builder()
                .authenticationType(AuthenticationType.OAUTH)
                .clientId("clientId")
                .clientSecret("clientSecret")
                .codeVerifier("codeVerifier")
                .build());

        when(context.getProxy()).thenReturn(proxy);
        when(proxy.getEncryptionService()).thenReturn(encryptionService);
        when(context.getConfig()).thenReturn(mock(Config.class));
        when(context.getApiKeyData()).thenReturn(mock(ApiKeyData.class));
        when(context.getUserId()).thenReturn("user-123");
        when(encryptionService.encrypt("Users/user-123/")).thenReturn("encrypted-user-123");

        // When - enrich multiple times (simulating multiple API requests)
        ResourceAuthStatusEnricher enricher = new ResourceAuthStatusEnricher(context, resourceAuthSettingsService);
        enricher.enrichToolSet(toolSetId, toolSet);
        enricher.enrichToolSet(toolSetId, toolSet);

        // Then - clientSecret and codeVerifier must be preserved after multiple calls
        assertEquals("clientSecret", toolSet.getAuthSettings().getClientSecret());
        assertEquals("codeVerifier", toolSet.getAuthSettings().getCodeVerifier());
    }

}
