package com.epam.aidial.core.server.service;

import com.epam.aidial.core.config.AuthenticationType;
import com.epam.aidial.core.config.ResourceAuthSettings;
import com.epam.aidial.core.config.ToolSet;
import com.epam.aidial.core.credentials.data.credentials.BucketInfo;
import com.epam.aidial.core.credentials.service.ResourceAuthSettingsEncryptionService;
import com.epam.aidial.core.credentials.service.ResourceAuthSettingsService;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.data.ResourceTypes;
import com.epam.aidial.core.server.util.ProxyUtil;
import com.epam.aidial.core.storage.data.ResourceItemMetadata;
import com.epam.aidial.core.storage.resource.ResourceDescriptor;
import com.epam.aidial.core.storage.service.ResourceService;
import com.epam.aidial.core.storage.util.EtagHeader;
import org.apache.commons.lang3.tuple.Pair;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Answers.RETURNS_DEEP_STUBS;
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

    @InjectMocks
    private ToolSetService toolSetService;

    private static Stream<Arguments> provideToolSetScenariosForEnrichment() {
        return Stream.of(
                // Endpoint changed
                Arguments.of(
                        createToolSet("updatedEndpoint", AuthenticationType.OAUTH),
                        createToolSet("existingEndpoint", AuthenticationType.OAUTH),
                        true
                ),

                // Auth type changed from NONE to OAUTH
                Arguments.of(
                        createToolSet("existingEndpoint", AuthenticationType.OAUTH),
                        createToolSet("existingEndpoint", AuthenticationType.NONE),
                        true
                ),

                // Auth type changed from API_KEY to OAUTH
                Arguments.of(
                        createToolSet("existingEndpoint", AuthenticationType.OAUTH),
                        createToolSet("existingEndpoint", AuthenticationType.API_KEY),
                        true
                ),

                // New ToolSet with OAuth
                Arguments.of(
                        createToolSet("existingEndpoint", AuthenticationType.OAUTH),
                        null,
                        true
                ),

                // New ToolSet with API_KEY - should not enrich
                Arguments.of(
                        createToolSet("existingEndpoint", AuthenticationType.API_KEY),
                        null,
                        false
                ),

                // New ToolSet with NONE auth - should not enrich
                Arguments.of(
                        createToolSet("existingEndpoint", AuthenticationType.NONE),
                        null,
                        false
                ),

                // OAuth settings changed - client ID
                Arguments.of(
                        createToolSetWithModifiedOauth(settings -> settings.setClientId("newClientId")),
                        createToolSetWithModifiedOauth(settings -> settings.setClientId("oldClientId")),
                        true
                ),

                // OAuth settings changed - client secret
                Arguments.of(
                        createToolSetWithModifiedOauth(settings -> settings.setClientSecret("newSecret")),
                        createToolSetWithModifiedOauth(settings -> settings.setClientSecret("oldSecret")),
                        true
                ),

                // OAuth settings changed - authorization endpoint
                Arguments.of(
                        createToolSetWithModifiedOauth(settings -> settings.setAuthorizationEndpoint("newAuthEndpoint")),
                        createToolSetWithModifiedOauth(settings -> settings.setAuthorizationEndpoint("oldAuthEndpoint")),
                        true
                ),

                // OAuth settings changed - token endpoint
                Arguments.of(
                        createToolSetWithModifiedOauth(settings -> settings.setTokenEndpoint("newTokenEndpoint")),
                        createToolSetWithModifiedOauth(settings -> settings.setTokenEndpoint("oldTokenEndpoint")),
                        true
                ),

                // OAuth settings changed - redirect URI
                Arguments.of(
                        createToolSetWithModifiedOauth(settings -> settings.setRedirectUri("newRedirectUri")),
                        createToolSetWithModifiedOauth(settings -> settings.setRedirectUri("oldRedirectUri")),
                        true
                ),

                // OAuth settings changed - code challenge method
                Arguments.of(
                        createToolSetWithModifiedOauth(settings -> settings.setCodeChallengeMethod("S256")),
                        createToolSetWithModifiedOauth(settings -> settings.setCodeChallengeMethod("plain")),
                        true
                ),

                // OAuth settings changed - scopes supported
                Arguments.of(
                        createToolSetWithModifiedOauth(settings -> settings.setScopesSupported(List.of("read", "write", "admin"))),
                        createToolSetWithModifiedOauth(settings -> settings.setScopesSupported(List.of("read", "write"))),
                        true
                ),

                // Neither endpoint nor auth settings changed
                Arguments.of(
                        createToolSet("existingEndpoint", AuthenticationType.OAUTH),
                        createToolSet("existingEndpoint", AuthenticationType.OAUTH),
                        false
                ),

                // Auth type changed from OAUTH to API_KEY - should not enrich
                Arguments.of(
                        createToolSet("existingEndpoint", AuthenticationType.API_KEY),
                        createToolSet("existingEndpoint", AuthenticationType.OAUTH),
                        false
                ),

                // Auth type changed from OAUTH to NONE - should not enrich
                Arguments.of(
                        createToolSet("existingEndpoint", AuthenticationType.NONE),
                        createToolSet("existingEndpoint", AuthenticationType.OAUTH),
                        false
                ),

                // OAuth settings unchanged - client secret is empty
                Arguments.of(
                        createToolSetWithModifiedOauth(settings -> settings.setClientSecret(null)),
                        createToolSetWithModifiedOauth(settings -> settings.setClientSecret("oldSecret")),
                        false
                )
        );
    }

    @ParameterizedTest
    @MethodSource("provideToolSetScenariosForEnrichment")
    void testPutToolSet_WhenEnrichmentRequired(
            ToolSet updatedToolSet,
            ToolSet existingToolSet,
            boolean shouldEnrichAuthSettings
    ) {
        // Given
        String expectedOutputJson = "expectedOutputJson";

        MockedStatic<ProxyUtil> proxyUtil = Mockito.mockStatic(ProxyUtil.class);
        proxyUtil.when(() -> ProxyUtil.convertToObject(any(String.class), eq(ToolSet.class)))
                .thenReturn(existingToolSet);
        proxyUtil.when(() -> ProxyUtil.convertToString(any()))
                .thenReturn(expectedOutputJson);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Function<String, String>> lambdaCaptor = ArgumentCaptor.forClass(Function.class);

        EtagHeader etag = EtagHeader.ANY;
        String author = "author";
        ResourceDescriptor resource = mock(ResourceDescriptor.class);
        when(resource.getUrl()).thenReturn("url");
        when(resourceService.computeResource(
                eq(resource), eq(etag), eq(author), lambdaCaptor.capture())
        ).thenReturn(mock(ResourceItemMetadata.class));

        // When
        toolSetService.putToolSet(resource, etag, author, updatedToolSet);
        String result = lambdaCaptor.getValue().apply("inputJson");

        // Then
        if (shouldEnrichAuthSettings) {
            verify(resourceAuthSettingsService).enrichResourceAuthSettings(updatedToolSet.getName(), updatedToolSet.getEndpoint(), updatedToolSet.getAuthSettings());
        } else {
            verifyNoInteractions(resourceAuthSettingsService);
        }

        assertEquals(expectedOutputJson, result);
        proxyUtil.close();
    }

    @Test
    void testPutToolSet_ShouldEncryptAuthSettings() {
        String expectedOutputJson = "expectedOutputJson";
        ResourceAuthSettings resourceAuthSettings = new ResourceAuthSettings();
        resourceAuthSettings.setClientSecret("plainClientSecret");
        resourceAuthSettings.setAuthenticationType(AuthenticationType.OAUTH);
        ToolSet toolSet = createToolSet("endpoint");
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
        toolSetService.putToolSet(resource, etag, author, toolSet);
        lambdaCaptor.getValue().apply("input");

        // THEN
        verify(resourceAuthSettingsEncryptionService).encrypt(
                eq(toolSet.getName()),
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
        ToolSet toolSet = createToolSet("endpoint");
        toolSet.setAuthSettings(resourceAuthSettings);

        MockedStatic<ProxyUtil> proxyUtil = Mockito.mockStatic(ProxyUtil.class);
        proxyUtil.when(() -> ProxyUtil.convertToObject(any(String.class), eq(ToolSet.class)))
                .thenReturn(toolSet);
        doAnswer(answer -> {
            ResourceAuthSettings authSettings = answer.getArgument(2);
            authSettings.setClientSecret("plainClientSecret");
            return null;
        }).when(resourceAuthSettingsEncryptionService).decrypt(any(), any(), any());

        ResourceItemMetadata metadata = mock(ResourceItemMetadata.class);
        ResourceDescriptor resource = mock(ResourceDescriptor.class);
        when(resource.getUrl()).thenReturn("url");
        when(resource.isFolder()).thenReturn(false);
        when(resource.getType()).thenReturn(ResourceTypes.TOOL_SET);
        when(resource.getBucketName()).thenReturn("bucket");
        when(resource.getBucketLocation()).thenReturn("location");
        when(resourceService.getResourceWithMetadata(resource, EtagHeader.ANY))
                .thenReturn(Pair.of(metadata, "json"));

        ProxyContext context = mock(ProxyContext.class, RETURNS_DEEP_STUBS);
        when(context.getUserSub()).thenReturn("userSub");

        // When
        Pair<ResourceItemMetadata, ToolSet> result =
                toolSetService.getToolSet(context, resource, EtagHeader.ANY);

        // Then
        verify(resourceAuthSettingsEncryptionService).decrypt(
                eq(toolSet.getName()),
                eq(new BucketInfo("bucket", "location")),
                eq(toolSet.getAuthSettings())
        );
        assertNotNull(result);
        assertNotNull(result.getValue());
        ToolSet actualToolSet = result.getValue();
        assertNotNull(actualToolSet.getAuthSettings());
        assertEquals("plainClientSecret", actualToolSet.getAuthSettings().getClientSecret());

        proxyUtil.close();
    }

    private static Stream<Arguments> provideCodeChallengeVerifierScenarios() {
        return Stream.of(
                Arguments.of((Consumer<ResourceAuthSettings>) settings -> settings.setCodeChallenge("codeChallenge")),
                Arguments.of((Consumer<ResourceAuthSettings>) settings -> settings.setCodeVerifier("codeVerifier"))
        );
    }

    @ParameterizedTest
    @MethodSource("provideCodeChallengeVerifierScenarios")
    void testPutToolSet_ShouldThrowException_WhenCodeChallengeOrVerifierSet(Consumer<ResourceAuthSettings> modifier) {
        // Given
        ToolSet newToolSet = createToolSetWithModifiedOauth(modifier);

        MockedStatic<ProxyUtil> proxyUtil = Mockito.mockStatic(ProxyUtil.class);
        proxyUtil.when(() -> ProxyUtil.convertToObject(any(String.class), eq(ToolSet.class)))
                .thenReturn(null);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Function<String, String>> lambdaCaptor = ArgumentCaptor.forClass(Function.class);

        EtagHeader etag = EtagHeader.ANY;
        String author = "author";
        ResourceDescriptor resource = mock(ResourceDescriptor.class);
        when(resource.getUrl()).thenReturn("url");
        when(resourceService.computeResource(
                eq(resource), eq(etag), eq(author), lambdaCaptor.capture())
        ).thenReturn(mock(ResourceItemMetadata.class));

        // When & Then
        toolSetService.putToolSet(resource, etag, author, newToolSet);

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> lambdaCaptor.getValue().apply("inputJson")
        );

        assertEquals("Code challenge/Code verifier can't be set by client", exception.getMessage());
        proxyUtil.close();
    }

    private static ToolSet createToolSet(String endpoint, AuthenticationType authenticationType) {
        ToolSet toolSet = createToolSet(endpoint);

        ResourceAuthSettings resourceAuthSettings = new ResourceAuthSettings();
        resourceAuthSettings.setAuthenticationType(authenticationType);
        toolSet.setAuthSettings(resourceAuthSettings);

        return toolSet;
    }

    private static ToolSet createToolSet(String endpoint) {
        ToolSet toolSet = new ToolSet();
        toolSet.setEndpoint(endpoint);
        return toolSet;
    }

    private static ToolSet createToolSetWithModifiedOauth(Consumer<ResourceAuthSettings> modifier) {
        ToolSet toolSet = new ToolSet();
        toolSet.setEndpoint("endpoint");

        ResourceAuthSettings resourceAuthSettings = createDefaultOauthSettings();
        modifier.accept(resourceAuthSettings);
        toolSet.setAuthSettings(resourceAuthSettings);

        return toolSet;
    }

    private static ResourceAuthSettings createDefaultOauthSettings() {
        return ResourceAuthSettings.builder()
                .authenticationType(AuthenticationType.OAUTH)
                .clientId("defaultClientId")
                .clientSecret("defaultClientSecret")
                .authorizationEndpoint("defaultAuthEndpoint")
                .tokenEndpoint("defaultTokenEndpoint")
                .redirectUri("defaultRedirectUri")
                .codeChallengeMethod("PKCE")
                .scopesSupported(List.of("read", "write"))
                .build();
    }
}
