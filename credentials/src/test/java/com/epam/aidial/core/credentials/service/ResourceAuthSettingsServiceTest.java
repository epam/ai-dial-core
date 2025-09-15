package com.epam.aidial.core.credentials.service;

import com.epam.aidial.core.config.AuthenticationType;
import com.epam.aidial.core.config.CredentialsLevel;
import com.epam.aidial.core.config.ResourceAuthSettings;
import com.epam.aidial.core.config.ResourceAuthStatus;
import com.epam.aidial.core.config.ToolSet;
import com.epam.aidial.core.credentials.data.credentials.ResourceCredentials;
import com.epam.aidial.core.credentials.data.registration.ClientRegistration;
import com.epam.aidial.core.credentials.service.registration.ResourceRegistrationService;
import com.epam.aidial.core.credentials.validation.ResourceAuthSettingsValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;

import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ResourceAuthSettingsServiceTest {

    private static final String USER_1 = "user1";
    private static final String USER_2 = "user2";

    @Mock
    private ResourceCredentialsManager resourceCredentialsManager;

    @Mock
    private ResourceAuthSettingsValidator resourceAuthSettingsValidator;

    @Mock
    private ResourceRegistrationService resourceRegistrationService;

    @InjectMocks
    private ResourceAuthSettingsService resourceAuthSettingsService;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
    }

    private static Stream<Arguments> provideSetResourceAuthStatusesTestCases() {
        return Stream.of(
                Arguments.of(
                        createUserLevelResourceCredentials(true, USER_1),
                        null,
                        ResourceAuthStatus.SIGNED_IN,
                        ResourceAuthStatus.SIGNED_OUT),

                Arguments.of(
                        createUserLevelResourceCredentials(false, USER_1),
                        null,
                        ResourceAuthStatus.SIGNED_OUT,
                        ResourceAuthStatus.SIGNED_OUT),

                Arguments.of(
                        null,
                        null,
                        ResourceAuthStatus.SIGNED_OUT,
                        ResourceAuthStatus.SIGNED_OUT),

                Arguments.of(
                        createUserLevelResourceCredentials(false, USER_1),
                        createGlobalLevelResourceCredentials(true),
                        ResourceAuthStatus.SIGNED_OUT,
                        ResourceAuthStatus.SIGNED_IN),

                Arguments.of(
                        null,
                        createGlobalLevelResourceCredentials(true),
                        ResourceAuthStatus.SIGNED_OUT,
                        ResourceAuthStatus.SIGNED_IN),

                Arguments.of(
                        createUserLevelResourceCredentials(true, USER_1),
                        createGlobalLevelResourceCredentials(true),
                        ResourceAuthStatus.SIGNED_IN,
                        ResourceAuthStatus.SIGNED_IN),

                Arguments.of(
                        createUserLevelResourceCredentials(true, USER_1),
                        createGlobalLevelResourceCredentials(false),
                        ResourceAuthStatus.SIGNED_IN,
                        ResourceAuthStatus.SIGNED_OUT),

                Arguments.of(
                        createUserLevelResourceCredentials(false, USER_1),
                        createGlobalLevelResourceCredentials(false),
                        ResourceAuthStatus.SIGNED_OUT,
                        ResourceAuthStatus.SIGNED_OUT),

                Arguments.of(
                        createUserLevelResourceCredentials(true, USER_2),
                        createGlobalLevelResourceCredentials(false),
                        ResourceAuthStatus.SIGNED_OUT,
                        ResourceAuthStatus.SIGNED_OUT)
        );
    }

    @ParameterizedTest
    @MethodSource("provideSetResourceAuthStatusesTestCases")
    void testSetResourceAuthStatuses(ResourceCredentials userCredentials,
                                     ResourceCredentials globalCredentials,
                                     ResourceAuthStatus expectedUserLevelStatus,
                                     ResourceAuthStatus expectedGlobalLevelStatus) {
        // Given
        ToolSet toolSet = createToolSet();
        when(resourceCredentialsManager.getAllResourceCredentials(toolSet.getName()))
                .thenReturn(
                        Stream.of(userCredentials, globalCredentials)
                                .filter(Objects::nonNull)
                                .toList());

        // When
        resourceAuthSettingsService.setResourceAuthStatuses(toolSet.getName(), toolSet.getAuthSettings(), USER_1);

        // Then
        assertEquals(expectedUserLevelStatus, toolSet.getAuthSettings().getUserLevelAuthStatus());
        assertEquals(expectedGlobalLevelStatus, toolSet.getAuthSettings().getGlobalAuthStatus());
    }

    @Test
    void testEnrichResourceAuthSettings_NoAuthSettings() {
        // Given
        String resourceId = "resource-id";
        String resourceEndpoint = "https://example.com";

        // When & Then
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> resourceAuthSettingsService.enrichResourceAuthSettings(resourceId, resourceEndpoint, null)
        );

        assertEquals("ResourceAuthSettings is not defined for Resource: " + resourceId, exception.getMessage());
    }

    @Test
    void testEnrichResourceAuthSettings_Validated() {
        // Given
        String resourceId = "resource-id";
        String resourceEndpoint = "https://example.com";
        ResourceAuthSettings resourceAuthSettings = new ResourceAuthSettings();
        resourceAuthSettings.setAuthenticationType(AuthenticationType.OAUTH);

        ClientRegistration mockClientRegistration = ClientRegistration.builder().build();
        when(resourceRegistrationService.register(resourceId, resourceEndpoint, resourceAuthSettings))
                .thenReturn(mockClientRegistration);

        // When
        resourceAuthSettingsService.enrichResourceAuthSettings(resourceId, resourceEndpoint, resourceAuthSettings);

        // Then
        verify(resourceAuthSettingsValidator, times(1)).validate(resourceAuthSettings);
    }

    @Test
    void testEnrichResourceAuthSettings_ApiKey() {
        // Given
        String resourceId = "resource-id";
        String resourceEndpoint = "https://example.com";
        ResourceAuthSettings resourceAuthSettings = new ResourceAuthSettings();
        resourceAuthSettings.setAuthenticationType(AuthenticationType.API_KEY);

        // When
        resourceAuthSettingsService.enrichResourceAuthSettings(resourceId, resourceEndpoint, resourceAuthSettings);

        // Then
        verifyNoInteractions(resourceRegistrationService);
        assertNull(resourceAuthSettings.getClientId());
        assertNull(resourceAuthSettings.getClientSecret());
    }

    @Test
    void testEnrichResourceAuthSettings_Success() {
        // Given
        String resourceId = "resource-id";
        String resourceEndpoint = "https://example.com";
        ResourceAuthSettings resourceAuthSettings = new ResourceAuthSettings();
        resourceAuthSettings.setAuthenticationType(AuthenticationType.OAUTH);

        ClientRegistration mockClientRegistration = ClientRegistration.builder()
                .clientId("mock-client-id")
                .clientSecret("mock-client-secret")
                .authorizationEndpoint("mock-auth-endpoint")
                .tokenEndpoint("mock-token-endpoint")
                .redirectUri("mock-redirect-uri")
                .scopesSupported(List.of("scope1", "scope2"))
                .codeChallengeMethod("S256")
                .build();

        when(resourceRegistrationService.register(resourceId, resourceEndpoint, resourceAuthSettings))
                .thenReturn(mockClientRegistration);

        // When
        resourceAuthSettingsService.enrichResourceAuthSettings(resourceId, resourceEndpoint, resourceAuthSettings);

        // Then
        verify(resourceRegistrationService, times(1)).register(resourceId, resourceEndpoint, resourceAuthSettings);
        assertEquals("mock-client-id", resourceAuthSettings.getClientId());
        assertEquals("mock-client-secret", resourceAuthSettings.getClientSecret());
        assertEquals("mock-auth-endpoint", resourceAuthSettings.getAuthorizationEndpoint());
        assertEquals("mock-token-endpoint", resourceAuthSettings.getTokenEndpoint());
        assertEquals("mock-redirect-uri", resourceAuthSettings.getRedirectUri());
        assertEquals(List.of("scope1", "scope2"), resourceAuthSettings.getScopesSupported());
    }

    @Test
    void shouldSetCodeChallengePropertiesWhenMethodIsProvided() {
        // Given
        String resourceId = "resource-id";
        String resourceEndpoint = "https://example.com";
        ResourceAuthSettings resourceAuthSettings = new ResourceAuthSettings();
        resourceAuthSettings.setAuthenticationType(AuthenticationType.OAUTH);

        ClientRegistration mockClientRegistration = ClientRegistration.builder()
                .codeChallengeMethod("S256")
                .build();

        when(resourceRegistrationService.register(resourceId, resourceEndpoint, resourceAuthSettings))
                .thenReturn(mockClientRegistration);

        // When
        resourceAuthSettingsService.enrichResourceAuthSettings(resourceId, resourceEndpoint, resourceAuthSettings);

        // Then
        assertNotNull(resourceAuthSettings.getCodeChallenge());
        assertNotNull(resourceAuthSettings.getCodeVerifier());
        assertEquals("S256", resourceAuthSettings.getCodeChallengeMethod());
    }

    private ToolSet createToolSet() {
        ResourceAuthSettings authSettings = new ResourceAuthSettings();
        ToolSet toolSet = Mockito.mock(ToolSet.class);
        when(toolSet.getName()).thenReturn("toolset-1");
        when(toolSet.getAuthSettings()).thenReturn(authSettings);
        return toolSet;
    }

    private static ResourceCredentials createGlobalLevelResourceCredentials(boolean isAlive) {
        return createResourceCredentials(CredentialsLevel.GLOBAL, AuthenticationType.OAUTH, isAlive, null);
    }

    private static ResourceCredentials createUserLevelResourceCredentials(boolean isAlive, String userSub) {
        return createResourceCredentials(CredentialsLevel.USER, AuthenticationType.OAUTH, isAlive, userSub);
    }

    private static ResourceCredentials createResourceCredentials(CredentialsLevel level,
                                                                 AuthenticationType authenticationType,
                                                                 boolean isAlive,
                                                                 String userSub) {
        ResourceCredentials credentials = Mockito.mock(ResourceCredentials.class);
        when(credentials.getCredentialsLevel()).thenReturn(level);
        when(credentials.getAuthenticationType()).thenReturn(authenticationType);
        when(credentials.hasUnexpiredToken()).thenReturn(isAlive);
        when(credentials.getUserSub()).thenReturn(userSub);
        return credentials;
    }
}
