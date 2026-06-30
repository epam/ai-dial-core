package com.epam.aidial.core.credentials.service;

import com.epam.aidial.core.config.AuthenticationType;
import com.epam.aidial.core.config.CredentialsLevel;
import com.epam.aidial.core.config.ResourceAuthSettings;
import com.epam.aidial.core.config.ResourceAuthStatus;
import com.epam.aidial.core.config.ToolSet;
import com.epam.aidial.core.credentials.data.credentials.BucketInfo;
import com.epam.aidial.core.credentials.data.credentials.CredentialsLocator;
import com.epam.aidial.core.credentials.data.credentials.ResourceCredentials;
import com.epam.aidial.core.credentials.data.registration.ClientRegistration;
import com.epam.aidial.core.credentials.service.registration.ResourceRegistrationService;
import com.epam.aidial.core.credentials.service.token.OauthTokenRefreshStrategy;
import com.epam.aidial.core.credentials.service.token.TokenRefreshStrategyFactory;
import com.epam.aidial.core.credentials.validation.ApiKeyAuthSettingsValidator;
import com.epam.aidial.core.credentials.validation.AuthSettingsValidatorFactory;
import com.epam.aidial.core.credentials.validation.OauthAuthSettingsValidator;
import com.nimbusds.oauth2.sdk.pkce.CodeChallenge;
import com.nimbusds.oauth2.sdk.pkce.CodeChallengeMethod;
import com.nimbusds.oauth2.sdk.pkce.CodeVerifier;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ResourceAuthSettingsServiceTest {

    private static final String USER_1 = "user1";
    private static final String USER_2 = "user2";
    private static final String RESOURCE_NAME = "TestToolSet";
    private static final String RESOURCE_ENDPOINT = "https://example.com";

    @Mock
    private ResourceCredentialsService resourceCredentialsService;

    @Mock
    private ResourceRegistrationService resourceRegistrationService;

    @Mock
    private TokenRefreshStrategyFactory tokenRefreshStrategyFactory;

    @Mock
    private OauthTokenRefreshStrategy oauthTokenRefreshStrategy;

    @Mock
    private AuthSettingsValidatorFactory validatorFactory;

    @Mock
    private OauthAuthSettingsValidator oauthAuthSettingsValidator;

    @Mock
    private ApiKeyAuthSettingsValidator apiKeyAuthSettingsValidator;

    @InjectMocks
    private ResourceAuthSettingsService resourceAuthSettingsService;

    private static Stream<Arguments> provideSetResourceAuthStatusesTestCases() {
        return Stream.of(
                Arguments.of(
                        createUserLevelResourceCredentials(USER_1),
                        true,
                        null,
                        false,
                        ResourceAuthStatus.SIGNED_IN,
                        ResourceAuthStatus.SIGNED_OUT),

                Arguments.of(
                        createUserLevelResourceCredentials(USER_1),
                        false,
                        null,
                        false,
                        ResourceAuthStatus.SIGNED_OUT,
                        ResourceAuthStatus.SIGNED_OUT),

                Arguments.of(
                        null,
                        false,
                        createGlobalLevelResourceCredentials(),
                        true,
                        ResourceAuthStatus.SIGNED_OUT,
                        ResourceAuthStatus.SIGNED_IN),

                Arguments.of(
                        null,
                        false,
                        createGlobalLevelResourceCredentials(),
                        false,
                        ResourceAuthStatus.SIGNED_OUT,
                        ResourceAuthStatus.SIGNED_OUT),

                Arguments.of(
                        createUserLevelResourceCredentials(USER_2),
                        true,
                        null,
                        false,
                        ResourceAuthStatus.SIGNED_OUT,
                        ResourceAuthStatus.SIGNED_OUT),

                Arguments.of(
                        null,
                        false,
                        null,
                        false,
                        ResourceAuthStatus.SIGNED_OUT,
                        ResourceAuthStatus.SIGNED_OUT)
        );
    }

    @ParameterizedTest
    @MethodSource("provideSetResourceAuthStatusesTestCases")
    void testSetResourceAuthStatuses(ResourceCredentials userCredentials,
                                     boolean hasUnexpiredUserCreds,
                                     ResourceCredentials globalCredentials,
                                     boolean hasUnexpiredGlobalCreds,
                                     ResourceAuthStatus expectedUserLevelStatus,
                                     ResourceAuthStatus expectedGlobalLevelStatus) {
        // Given
        ToolSet toolSet = createOauthToolSet();
        CredentialsLocator credentialsLocator = createCredentialsLocator();
        when(resourceCredentialsService.getAllResourceCredentials(credentialsLocator))
                .thenReturn(
                        Stream.of(userCredentials, globalCredentials)
                                .filter(Objects::nonNull)
                                .toList());

        if (userCredentials != null) {
            when(tokenRefreshStrategyFactory.getTokenValidatorStrategy(AuthenticationType.OAUTH))
                    .thenReturn(oauthTokenRefreshStrategy);
            when(oauthTokenRefreshStrategy.hasUnexpiredToken(userCredentials))
                    .thenReturn(hasUnexpiredUserCreds);
        }

        if (globalCredentials != null) {
            when(tokenRefreshStrategyFactory.getTokenValidatorStrategy(AuthenticationType.OAUTH))
                    .thenReturn(oauthTokenRefreshStrategy);
            when(oauthTokenRefreshStrategy.hasUnexpiredToken(globalCredentials))
                    .thenReturn(hasUnexpiredGlobalCreds);
        }

        // When
        resourceAuthSettingsService.setResourceAuthStatuses(credentialsLocator, toolSet.getAuthSettings(), USER_1);

        // Then
        assertEquals(expectedUserLevelStatus, toolSet.getAuthSettings().getUserLevelAuthStatus());
        assertEquals(expectedGlobalLevelStatus, toolSet.getAuthSettings().getGlobalAuthStatus());
    }

    @Test
    void testProcessResourceAuthSettings_ForwardPerRequestKeyAndAuthType() {
        // Given
        ToolSet newToolSet = createApiKeyToolSet("apiKeyHeader");
        newToolSet.setForwardPerRequestKey(true);

        // When
        Assertions.assertThrows(IllegalArgumentException.class, () -> resourceAuthSettingsService.processResourceAuthSettings(newToolSet, null));
    }

    @Test
    void testProcessResourceAuthSettings_NewOauthToolSetWithDynamicRegistration() {
        // Given
        ToolSet newToolSet = createOauthToolSet(null, null);
        ClientRegistration mockRegistration = createClientRegistration();

        when(validatorFactory.getValidator(AuthenticationType.OAUTH)).thenReturn(oauthAuthSettingsValidator);
        when(resourceRegistrationService.register(eq(RESOURCE_NAME), eq(RESOURCE_ENDPOINT), any(), eq(true)))
                .thenReturn(mockRegistration);

        // When
        resourceAuthSettingsService.processResourceAuthSettings(newToolSet, null);

        // Then
        verify(oauthAuthSettingsValidator, times(1)).validate(
                any(ResourceAuthSettings.class), eq(ResourceAuthSettingsChangeMode.CREATE_DYNAMIC_CLIENT));
        verify(resourceRegistrationService, times(1)).register(
                eq(RESOURCE_NAME), eq(RESOURCE_ENDPOINT), any(ResourceAuthSettings.class), eq(true));

        assertEquals("newClientId", newToolSet.getAuthSettings().getClientId());
        assertEquals("newClientSecret", newToolSet.getAuthSettings().getClientSecret());
        assertEquals("authEndpoint", newToolSet.getAuthSettings().getAuthorizationEndpoint());
        assertEquals("redirectUri", newToolSet.getAuthSettings().getRedirectUri());
        assertEquals(List.of("read", "write"), newToolSet.getAuthSettings().getScopesSupported());
    }

    @Test
    void testProcessResourceAuthSettings_NewOauthToolSetWithStaticRegistration() {
        // Given
        ToolSet newToolSet = createOauthToolSet("newClientId", "newClientSecret");
        ClientRegistration mockRegistration = createClientRegistration();

        when(validatorFactory.getValidator(AuthenticationType.OAUTH)).thenReturn(oauthAuthSettingsValidator);
        when(resourceRegistrationService.register(eq(RESOURCE_NAME), eq(RESOURCE_ENDPOINT), any(), eq(false)))
                .thenReturn(mockRegistration);

        // When
        resourceAuthSettingsService.processResourceAuthSettings(newToolSet, null);

        // Then
        verify(oauthAuthSettingsValidator, times(1)).validate(
                any(ResourceAuthSettings.class), eq(ResourceAuthSettingsChangeMode.CREATE_STATIC_CLIENT));
        verify(resourceRegistrationService, times(1)).register(
                eq(RESOURCE_NAME), eq(RESOURCE_ENDPOINT), any(ResourceAuthSettings.class), eq(false));

        assertEquals("newClientId", newToolSet.getAuthSettings().getClientId());
        assertEquals("newClientSecret", newToolSet.getAuthSettings().getClientSecret());
        assertEquals("authEndpoint", newToolSet.getAuthSettings().getAuthorizationEndpoint());
        assertEquals("redirectUri", newToolSet.getAuthSettings().getRedirectUri());
        assertEquals(List.of("read", "write"), newToolSet.getAuthSettings().getScopesSupported());
    }

    @Test
    void testProcessResourceAuthSettings_NewApiKeyToolSet() {
        // Given
        ToolSet newToolSet = createApiKeyToolSet("apiKeyHeader");

        // When
        when(validatorFactory.getValidator(AuthenticationType.API_KEY)).thenReturn(apiKeyAuthSettingsValidator);

        resourceAuthSettingsService.processResourceAuthSettings(newToolSet, null);

        // Then
        verify(apiKeyAuthSettingsValidator, times(1)).validate(
                any(ResourceAuthSettings.class), eq(ResourceAuthSettingsChangeMode.NO_CLIENT_CHANGES));
        verifyNoInteractions(resourceRegistrationService);
        assertEquals("apiKeyHeader", newToolSet.getAuthSettings().getApiKeyHeader());
    }

    @Test
    void testProcessResourceAuthSettings_OauthUpdate_CodeChallengeMethodUnchanged_PreservesPkce() {
        // Given
        ToolSet updatedToolSet = createOauthToolSetWithPkce("clientId", "clientSecret",
                CodeChallengeMethod.S256.getValue(), "origChallenge", "origVerifier");
        ToolSet existingToolSet = createOauthToolSetWithPkce("clientId", "clientSecret",
                CodeChallengeMethod.S256.getValue(), "origChallenge", "origVerifier");

        when(validatorFactory.getValidator(AuthenticationType.OAUTH)).thenReturn(oauthAuthSettingsValidator);

        // When
        resourceAuthSettingsService.processResourceAuthSettings(updatedToolSet, existingToolSet);

        // Then
        verify(oauthAuthSettingsValidator, times(1)).validate(
                any(ResourceAuthSettings.class), eq(ResourceAuthSettingsChangeMode.NO_CLIENT_CHANGES));
        verifyNoInteractions(resourceRegistrationService);

        ResourceAuthSettings result = updatedToolSet.getAuthSettings();
        assertEquals(CodeChallengeMethod.S256.getValue(), result.getCodeChallengeMethod());
        assertEquals("origChallenge", result.getCodeChallenge());
        assertEquals("origVerifier", result.getCodeVerifier());
    }

    @Test
    void testProcessResourceAuthSettings_OauthUpdate_CodeChallengeMethodNull_PreservesPkce() {
        // Given
        ToolSet updatedToolSet = createOauthToolSetWithPkce("clientId", "clientSecret",
                null, null, null);
        ToolSet existingToolSet = createOauthToolSetWithPkce("clientId", "clientSecret",
                CodeChallengeMethod.S256.getValue(), "origChallenge", "origVerifier");

        when(validatorFactory.getValidator(AuthenticationType.OAUTH)).thenReturn(oauthAuthSettingsValidator);

        // When
        resourceAuthSettingsService.processResourceAuthSettings(updatedToolSet, existingToolSet);

        // Then
        verify(oauthAuthSettingsValidator, times(1)).validate(
                any(ResourceAuthSettings.class), eq(ResourceAuthSettingsChangeMode.NO_CLIENT_CHANGES));
        verifyNoInteractions(resourceRegistrationService);

        ResourceAuthSettings result = updatedToolSet.getAuthSettings();
        assertEquals(CodeChallengeMethod.S256.getValue(), result.getCodeChallengeMethod());
        assertEquals("origChallenge", result.getCodeChallenge());
        assertEquals("origVerifier", result.getCodeVerifier());
    }

    @Test
    void testProcessResourceAuthSettings_OauthUpdate_CodeChallengeMethodChanged_RegeneratesPkce() {
        // Given
        CodeVerifier originalVerifier = new CodeVerifier();
        String originalChallenge = CodeChallenge.compute(CodeChallengeMethod.S256, originalVerifier).getValue();
        ToolSet updatedToolSet = createOauthToolSetWithPkce("clientId", "clientSecret",
                CodeChallengeMethod.PLAIN.getValue(), originalChallenge, originalVerifier.getValue());
        ToolSet existingToolSet = createOauthToolSetWithPkce("clientId", "clientSecret",
                CodeChallengeMethod.S256.getValue(), originalChallenge, originalVerifier.getValue());

        when(validatorFactory.getValidator(AuthenticationType.OAUTH)).thenReturn(oauthAuthSettingsValidator);

        // When
        resourceAuthSettingsService.processResourceAuthSettings(updatedToolSet, existingToolSet);

        // Then
        verify(oauthAuthSettingsValidator, times(1)).validate(
                any(ResourceAuthSettings.class), eq(ResourceAuthSettingsChangeMode.NO_CLIENT_CHANGES));
        verifyNoInteractions(resourceRegistrationService);

        ResourceAuthSettings result = updatedToolSet.getAuthSettings();
        assertEquals(CodeChallengeMethod.PLAIN.getValue(), result.getCodeChallengeMethod());
        Assertions.assertNotNull(result.getCodeVerifier());
        Assertions.assertNotNull(result.getCodeChallenge());
        Assertions.assertNotEquals(originalVerifier.getValue(), result.getCodeVerifier());
        Assertions.assertNotEquals(originalChallenge, result.getCodeChallenge());
        // PLAIN method: challenge equals verifier
        assertEquals(result.getCodeVerifier(), result.getCodeChallenge());
    }

    @Test
    void testProcessResourceAuthSettings_OauthUpdate_TokenEndpointAuthMethodNull_PreservesExisting() {
        ToolSet updatedToolSet = createOauthToolSetWithTokenEndpointAuthMethod("clientId", "clientSecret", null);
        ToolSet existingToolSet = createOauthToolSetWithTokenEndpointAuthMethod("clientId", "clientSecret",
                "client_secret_basic");

        when(validatorFactory.getValidator(AuthenticationType.OAUTH)).thenReturn(oauthAuthSettingsValidator);

        resourceAuthSettingsService.processResourceAuthSettings(updatedToolSet, existingToolSet);

        verifyNoInteractions(resourceRegistrationService);
        assertEquals("client_secret_basic", updatedToolSet.getAuthSettings().getTokenEndpointAuthMethod());
    }

    @Test
    void testProcessResourceAuthSettings_OauthUpdate_TokenEndpointAuthMethodChanged_Overrides() {
        ToolSet updatedToolSet = createOauthToolSetWithTokenEndpointAuthMethod("clientId", "clientSecret", "none");
        ToolSet existingToolSet = createOauthToolSetWithTokenEndpointAuthMethod("clientId", "clientSecret",
                "client_secret_basic");

        when(validatorFactory.getValidator(AuthenticationType.OAUTH)).thenReturn(oauthAuthSettingsValidator);

        resourceAuthSettingsService.processResourceAuthSettings(updatedToolSet, existingToolSet);

        verifyNoInteractions(resourceRegistrationService);
        assertEquals("none", updatedToolSet.getAuthSettings().getTokenEndpointAuthMethod());
    }

    @Test
    void testProcessResourceAuthSettings_UpdatedApiKeyToolSet() {
        // Given
        ToolSet updatedToolSet = createApiKeyToolSet("newApiKeyHeader");
        ToolSet existingToolSet = createApiKeyToolSet("oldApiKeyHeader");

        // When
        when(validatorFactory.getValidator(AuthenticationType.API_KEY)).thenReturn(apiKeyAuthSettingsValidator);

        resourceAuthSettingsService.processResourceAuthSettings(updatedToolSet, existingToolSet);

        // Then
        verify(apiKeyAuthSettingsValidator, times(1)).validate(
                any(ResourceAuthSettings.class), eq(ResourceAuthSettingsChangeMode.NO_CLIENT_CHANGES));
        verifyNoInteractions(resourceRegistrationService);
        assertEquals("newApiKeyHeader", updatedToolSet.getAuthSettings().getApiKeyHeader());
    }

    private static Stream<Arguments> provideProcessResourceAuthSettingsTestCases() {
        return Stream.of(
                // Case 1: Unchanged OAuth settings
                Arguments.of(createOauthToolSet("sameClientId", "sameClientSecret"),
                        createOauthToolSet("sameClientId", "sameClientSecret", "sameCodeVerifier"),
                        AuthenticationType.OAUTH,
                        false,
                        ResourceAuthSettingsChangeMode.NO_CLIENT_CHANGES,
                        false),

                // Case 2: Changed OAuth settings
                Arguments.of(createOauthToolSet("newClientId", "newClientSecret"),
                        createOauthToolSet("oldClientId", "oldClientSecret", "oldCodeVerifier"),
                        AuthenticationType.OAUTH,
                        false,
                        ResourceAuthSettingsChangeMode.NO_CLIENT_CHANGES,
                        false),

                // Case 3: Changed OAuth settings with null clientSecret
                Arguments.of(createOauthToolSet("newClientId", null),
                        createOauthToolSet("oldClientId", "oldClientSecret", "oldCodeVerifier"),
                        AuthenticationType.OAUTH,
                        false,
                        ResourceAuthSettingsChangeMode.NO_CLIENT_CHANGES,
                        false),

                // Case 4: API Key -> OAuth Dynamic Registration
                Arguments.of(createOauthToolSet(null, null),
                        createApiKeyToolSet("apiKeyHeader"),
                        AuthenticationType.OAUTH,
                        false,
                        ResourceAuthSettingsChangeMode.CREATE_DYNAMIC_CLIENT,
                        true),

                // Case 5: API Key -> OAuth Static Registration
                Arguments.of(createOauthToolSet("newClientId", "newClientSecret"),
                        createApiKeyToolSet("apiKeyHeader"),
                        AuthenticationType.OAUTH,
                        false,
                        ResourceAuthSettingsChangeMode.CREATE_STATIC_CLIENT,
                        true),

                // Case 6: None -> OAuth Dynamic Registration
                Arguments.of(createOauthToolSet(null, null),
                        createNoneToolSet(),
                        AuthenticationType.OAUTH,
                        false,
                        ResourceAuthSettingsChangeMode.CREATE_DYNAMIC_CLIENT,
                        true),

                // Case 7: None -> OAuth Static Registration
                Arguments.of(createOauthToolSet("newClientId", "newClientSecret"),
                        createNoneToolSet(),
                        AuthenticationType.OAUTH,
                        false,
                        ResourceAuthSettingsChangeMode.CREATE_STATIC_CLIENT,
                        true),

                // Case 8: OAuth -> API Key
                Arguments.of(createApiKeyToolSet("newApiKeyHeader"),
                        createOauthToolSet("oldClientId", "oldClientSecret"),
                        AuthenticationType.API_KEY,
                        true,
                        ResourceAuthSettingsChangeMode.NO_CLIENT_CHANGES,
                        false),

                // Case 9: None -> API Key
                Arguments.of(createApiKeyToolSet("newApiKeyHeader"),
                        createNoneToolSet(),
                        AuthenticationType.API_KEY,
                        true,
                        ResourceAuthSettingsChangeMode.NO_CLIENT_CHANGES,
                        false),

                // Case 10: API Key -> None
                Arguments.of(createNoneToolSet(),
                        createApiKeyToolSet("apiKeyHeader"),
                        AuthenticationType.NONE,
                        true,
                        ResourceAuthSettingsChangeMode.NO_CLIENT_CHANGES,
                        false),

                // Case 11: OAuth -> None
                Arguments.of(createNoneToolSet(),
                        createOauthToolSet("oldClientId", "oldClientSecret"),
                        AuthenticationType.NONE,
                        true,
                        ResourceAuthSettingsChangeMode.NO_CLIENT_CHANGES,
                        false)
        );
    }

    @ParameterizedTest
    @MethodSource("provideProcessResourceAuthSettingsTestCases")
    void parametrizedTestProcessResourceAuthSettings(ToolSet updatedToolSet,
                                                     ToolSet existingToolSet,
                                                     AuthenticationType expectedAuthType,
                                                     boolean expectedNullCodeVerifier,
                                                     ResourceAuthSettingsChangeMode changeMode,
                                                     boolean shouldRegister) {
        // Given
        ClientRegistration mockRegistration = createClientRegistration();
        if (shouldRegister) {
            when(resourceRegistrationService.register(
                    eq(RESOURCE_NAME), eq(RESOURCE_ENDPOINT), any(), eq(changeMode == ResourceAuthSettingsChangeMode.CREATE_DYNAMIC_CLIENT)))
                    .thenReturn(mockRegistration);
        }
        when(validatorFactory.getValidator(expectedAuthType))
                .thenReturn(expectedAuthType == AuthenticationType.OAUTH ? oauthAuthSettingsValidator : apiKeyAuthSettingsValidator);

        // When
        resourceAuthSettingsService.processResourceAuthSettings(updatedToolSet, existingToolSet);

        // Then
        verify(validatorFactory.getValidator(expectedAuthType), times(1))
                .validate(any(ResourceAuthSettings.class), eq(changeMode));

        if (shouldRegister) {
            verify(resourceRegistrationService, times(1))
                    .register(eq(RESOURCE_NAME), eq(RESOURCE_ENDPOINT), any(ResourceAuthSettings.class), eq(changeMode == ResourceAuthSettingsChangeMode.CREATE_DYNAMIC_CLIENT));
        } else {
            verify(resourceRegistrationService, never()).register(any(), any(), any(), anyBoolean());
        }

        assertEquals(expectedAuthType, updatedToolSet.getAuthSettings().getAuthenticationType());
        assertEquals(expectedNullCodeVerifier, updatedToolSet.getAuthSettings().getCodeVerifier() == null);
    }

    @Test
    void testProcessResourceAuthSettings_OauthEndpointChanged_DynamicReRegistration() {
        // Given
        String newEndpoint = "https://new-example.com";
        ToolSet updatedToolSet = createOauthToolSetWithEndpoint(newEndpoint, null, null);
        ToolSet existingToolSet = createOauthToolSet("oldClientId", "oldClientSecret");
        ClientRegistration mockRegistration = createClientRegistration();

        when(validatorFactory.getValidator(AuthenticationType.OAUTH)).thenReturn(oauthAuthSettingsValidator);
        when(resourceRegistrationService.register(eq(RESOURCE_NAME), eq(newEndpoint), any(), eq(true)))
                .thenReturn(mockRegistration);

        // When
        resourceAuthSettingsService.processResourceAuthSettings(updatedToolSet, existingToolSet);

        // Then
        verify(oauthAuthSettingsValidator, times(1)).validate(
                any(ResourceAuthSettings.class), eq(ResourceAuthSettingsChangeMode.CREATE_DYNAMIC_CLIENT));
        verify(resourceRegistrationService, times(1)).register(
                eq(RESOURCE_NAME), eq(newEndpoint), any(ResourceAuthSettings.class), eq(true));
    }

    @Test
    void testProcessResourceAuthSettings_OauthEndpointChanged_StaticReRegistration() {
        // Given
        String newEndpoint = "https://new-example.com";
        ToolSet updatedToolSet = createOauthToolSetWithEndpoint(newEndpoint, "clientId", "clientSecret");
        ToolSet existingToolSet = createOauthToolSet("clientId", "clientSecret");
        ClientRegistration mockRegistration = createClientRegistration();

        when(validatorFactory.getValidator(AuthenticationType.OAUTH)).thenReturn(oauthAuthSettingsValidator);
        when(resourceRegistrationService.register(eq(RESOURCE_NAME), eq(newEndpoint), any(), eq(false)))
                .thenReturn(mockRegistration);

        // When
        resourceAuthSettingsService.processResourceAuthSettings(updatedToolSet, existingToolSet);

        // Then
        verify(oauthAuthSettingsValidator, times(1)).validate(
                any(ResourceAuthSettings.class), eq(ResourceAuthSettingsChangeMode.CREATE_STATIC_CLIENT));
        verify(resourceRegistrationService, times(1)).register(
                eq(RESOURCE_NAME), eq(newEndpoint), any(ResourceAuthSettings.class), eq(false));
    }

    // Helper methods

    private static ToolSet createOauthToolSet(String clientId,
                                              String clientSecret) {
        return createOauthToolSet(clientId, clientSecret, null);
    }

    private ToolSet createOauthToolSet() {
        ResourceAuthSettings authSettings = new ResourceAuthSettings();
        ToolSet toolSet = Mockito.mock(ToolSet.class);
        when(toolSet.getAuthSettings()).thenReturn(authSettings);
        return toolSet;
    }

    private static ToolSet createOauthToolSet(String clientId,
                                              String clientSecret,
                                              String codeVerifier) {
        ResourceAuthSettings authSettings = new ResourceAuthSettings();
        authSettings.setAuthenticationType(AuthenticationType.OAUTH);
        authSettings.setClientId(clientId);
        authSettings.setClientSecret(clientSecret);
        authSettings.setCodeVerifier(codeVerifier);
        return createToolSet(authSettings);
    }

    private static ToolSet createOauthToolSetWithEndpoint(String endpoint, String clientId, String clientSecret) {
        ResourceAuthSettings authSettings = new ResourceAuthSettings();
        authSettings.setAuthenticationType(AuthenticationType.OAUTH);
        authSettings.setClientId(clientId);
        authSettings.setClientSecret(clientSecret);
        ToolSet toolSet = new ToolSet();
        toolSet.setName(RESOURCE_NAME);
        toolSet.setEndpoint(endpoint);
        toolSet.setAuthSettings(authSettings);
        return toolSet;
    }

    private static ToolSet createOauthToolSetWithTokenEndpointAuthMethod(String clientId,
                                                                         String clientSecret,
                                                                         String tokenEndpointAuthMethod) {
        ResourceAuthSettings authSettings = new ResourceAuthSettings();
        authSettings.setAuthenticationType(AuthenticationType.OAUTH);
        authSettings.setClientId(clientId);
        authSettings.setClientSecret(clientSecret);
        authSettings.setTokenEndpointAuthMethod(tokenEndpointAuthMethod);
        return createToolSet(authSettings);
    }

    private static ToolSet createOauthToolSetWithPkce(String clientId,
                                                      String clientSecret,
                                                      String codeChallengeMethod,
                                                      String codeChallenge,
                                                      String codeVerifier) {
        ResourceAuthSettings authSettings = new ResourceAuthSettings();
        authSettings.setAuthenticationType(AuthenticationType.OAUTH);
        authSettings.setClientId(clientId);
        authSettings.setClientSecret(clientSecret);
        authSettings.setCodeChallengeMethod(codeChallengeMethod);
        authSettings.setCodeChallenge(codeChallenge);
        authSettings.setCodeVerifier(codeVerifier);
        return createToolSet(authSettings);
    }

    private static ToolSet createApiKeyToolSet(String apiKeyHeader) {
        ResourceAuthSettings authSettings = new ResourceAuthSettings();
        authSettings.setAuthenticationType(AuthenticationType.API_KEY);
        authSettings.setApiKeyHeader(apiKeyHeader);
        return createToolSet(authSettings);
    }

    private static ToolSet createNoneToolSet() {
        ResourceAuthSettings authSettings = new ResourceAuthSettings();
        authSettings.setAuthenticationType(AuthenticationType.NONE);
        return createToolSet(authSettings);
    }

    private static ToolSet createToolSet(ResourceAuthSettings authSettings) {
        ToolSet toolSet = new ToolSet();
        toolSet.setName(RESOURCE_NAME);
        toolSet.setEndpoint(RESOURCE_ENDPOINT);
        toolSet.setAuthSettings(authSettings);
        return toolSet;
    }

    private ClientRegistration createClientRegistration() {
        return ClientRegistration.builder()
                .clientId("newClientId")
                .clientSecret("newClientSecret")
                .authorizationEndpoint("authEndpoint")
                .tokenEndpoint("tokenEndpoint")
                .redirectUri("redirectUri")
                .scopesSupported(List.of("read", "write"))
                .codeChallengeMethod("S256")
                .build();
    }

    private static ResourceCredentials createGlobalLevelResourceCredentials() {
        return createResourceCredentials(CredentialsLevel.GLOBAL, AuthenticationType.OAUTH, null);
    }

    private static ResourceCredentials createUserLevelResourceCredentials(String userSub) {
        return createResourceCredentials(CredentialsLevel.USER, AuthenticationType.OAUTH, userSub);
    }

    private static ResourceCredentials createResourceCredentials(CredentialsLevel level,
                                                                 AuthenticationType authenticationType,
                                                                 String userSub) {
        ResourceCredentials credentials = Mockito.mock(ResourceCredentials.class);
        when(credentials.getCredentialsLevel()).thenReturn(level);
        when(credentials.getAuthenticationType()).thenReturn(authenticationType);
        when(credentials.getUserId()).thenReturn(userSub);
        return credentials;
    }

    private CredentialsLocator createCredentialsLocator() {
        return new CredentialsLocator("bucket-name/folder1/my-toolset", Map.of(
                CredentialsLevel.USER, new BucketInfo("bucket-name", "bucket-location/")
        ));
    }

}
