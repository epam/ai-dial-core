package com.epam.aidial.core.server.service;

import com.epam.aidial.core.config.AuthenticationType;
import com.epam.aidial.core.config.ResourceAuthSettings;
import com.epam.aidial.core.config.ToolSet;
import com.epam.aidial.core.credentials.data.credentials.BucketInfo;
import com.epam.aidial.core.credentials.service.ResourceAuthSettingsEncryptionService;
import com.epam.aidial.core.credentials.service.ResourceAuthSettingsService;
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

import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
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
    private CatalogSchemaService catalogSchemaService;

    @InjectMocks
    private ToolSetService toolSetService;

    private static ToolSet toolSetWithSecret(AuthenticationType type) {
        ToolSet toolSet = new ToolSet();
        toolSet.setAuthSettings(ResourceAuthSettings.builder()
                .authenticationType(type)
                .clientSecret("cipher-text")
                .codeVerifier("code-verifier")
                .build());
        return toolSet;
    }

    private static ResourceDescriptor resourceDescriptor() {
        ResourceDescriptor resource = mock(ResourceDescriptor.class);
        when(resource.getUrl()).thenReturn("url");
        when(resource.getBucketName()).thenReturn("bucket");
        when(resource.getBucketLocation()).thenReturn("location");
        return resource;
    }

    // A blob of any type can carry a clientSecret — written before the per-type validators existed, or
    // hand-seeded. The hint path decrypts it, so anything short of unconditional redaction returns plaintext.
    @Test
    void testRedactAuthSettingsStripsSecretOfNonOauthToolSet() {
        ToolSet toolSet = toolSetWithSecret(AuthenticationType.API_KEY);
        ResourceDescriptor resource = resourceDescriptor();
        doAnswer(invocation -> {
            invocation.getArgument(2, ResourceAuthSettings.class).setClientSecret("plaintext-secret");
            return null;
        }).when(resourceAuthSettingsEncryptionService).decrypt(any(), any(), any());

        toolSetService.redactAuthSettings(resource, toolSet, true);

        assertNull(toolSet.getAuthSettings().getClientSecret());
        assertNull(toolSet.getAuthSettings().getCodeVerifier());
        assertEquals("cret", toolSet.getAuthSettings().getClientSecretHint());
    }

    @Test
    void testRedactAuthSettingsWithoutManageAccessNeitherDecryptsNorHints() {
        ToolSet toolSet = toolSetWithSecret(AuthenticationType.OAUTH);
        ResourceDescriptor resource = mock(ResourceDescriptor.class);

        toolSetService.redactAuthSettings(resource, toolSet, false);

        assertNull(toolSet.getAuthSettings().getClientSecret());
        assertNull(toolSet.getAuthSettings().getClientSecretHint());
        verifyNoInteractions(resourceAuthSettingsEncryptionService);
    }

    @Test
    void testRedactAuthSettingsOmitsHintWhenSecretCannotBeDecrypted() {
        ToolSet toolSet = toolSetWithSecret(AuthenticationType.OAUTH);
        ResourceDescriptor resource = resourceDescriptor();
        doThrow(new RuntimeException("boom"))
                .when(resourceAuthSettingsEncryptionService).decrypt(any(), any(), any());

        toolSetService.redactAuthSettings(resource, toolSet, true);

        assertNull(toolSet.getAuthSettings().getClientSecret());
        assertNull(toolSet.getAuthSettings().getClientSecretHint());
    }

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

    private static ToolSet createToolSet() {
        ToolSet toolSet = new ToolSet();
        toolSet.setEndpoint("endpoint");
        return toolSet;
    }

}
