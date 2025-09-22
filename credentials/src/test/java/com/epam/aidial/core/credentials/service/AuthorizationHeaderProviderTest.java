package com.epam.aidial.core.credentials.service;

import com.epam.aidial.core.config.AuthenticationType;
import com.epam.aidial.core.config.CredentialsLevel;
import com.epam.aidial.core.config.ResourceAuthSettings;
import com.epam.aidial.core.credentials.data.credentials.AuthorizationHeader;
import com.epam.aidial.core.credentials.data.credentials.CredentialsDescriptor;
import com.epam.aidial.core.credentials.data.credentials.CredentialsLocator;
import com.epam.aidial.core.credentials.data.credentials.ResourceCredentials;
import com.epam.aidial.core.storage.exception.ResourceNotFoundException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthorizationHeaderProviderTest {

    @Mock
    private ResourceCredentialsService resourceCredentialsService;

    @InjectMocks
    private AuthorizationHeaderProvider authorizationHeaderProvider;

    private static Stream<TestArguments> provideAuthorizationHeaderGlobalTestArgs() {
        return Stream.of(
                new TestArguments(
                        createResourceAuthSettings(AuthenticationType.OAUTH),
                        createCredentials(
                                AuthenticationType.OAUTH,
                                CredentialsLevel.GLOBAL,
                                "global-token",
                                null,
                                null,
                                "userSub"),
                        "Bearer global-token",
                        "Authorization"
                ),

                new TestArguments(
                        createResourceAuthSettings(AuthenticationType.API_KEY),
                        createCredentials(AuthenticationType.API_KEY,
                                CredentialsLevel.GLOBAL,
                                null,
                                "x-api-key",
                                "apiKeyValue",
                                "userSub"),
                        "apiKeyValue",
                        "x-api-key"
                )
        );
    }

    @ParameterizedTest
    @MethodSource("provideAuthorizationHeaderGlobalTestArgs")
    void testCreateAuthorizationHeader_GlobalCredentials(TestArguments arguments) {
        // Given
        CredentialsLocator credentialsLocator = Mockito.mock(CredentialsLocator.class);
        CredentialsDescriptor userCredentialsDescriptor = Mockito.mock(CredentialsDescriptor.class);
        CredentialsDescriptor globalCredentialsDescriptor = Mockito.mock(CredentialsDescriptor.class);

        Map<CredentialsLevel, CredentialsDescriptor> credentialsDescriptors = Map.of(
                CredentialsLevel.USER, userCredentialsDescriptor,
                CredentialsLevel.GLOBAL, globalCredentialsDescriptor);

        when(credentialsLocator.getCredentialsDescriptors()).thenReturn(credentialsDescriptors);
        ResourceAuthSettings authSettings = arguments.authSettings;

        when(resourceCredentialsService.getAndRefreshCredentials(userCredentialsDescriptor, authSettings))
                .thenReturn(null);
        when(resourceCredentialsService.getAndRefreshCredentials(globalCredentialsDescriptor, authSettings))
                .thenReturn(arguments.resourceCredentials);

        // When
        AuthorizationHeader header = authorizationHeaderProvider.createAuthorizationHeader(
                credentialsLocator, authSettings, "userSub"
        );

        // Then
        assertNotNull(header);
        assertEquals(arguments.expectedHeaderName, header.getHeaderName());
        assertEquals(arguments.expectedHeaderValue, header.getHeaderValue());
    }

    private static Stream<TestArguments> provideAuthorizationHeaderUserTestArgs() {
        return Stream.of(
                new TestArguments(
                        createResourceAuthSettings(AuthenticationType.OAUTH),
                        createCredentials(
                                AuthenticationType.OAUTH,
                                CredentialsLevel.USER,
                                "user-token",
                                null,
                                null,
                                "userSub"),
                        "Bearer user-token",
                        "Authorization"
                ),

                new TestArguments(
                        createResourceAuthSettings(AuthenticationType.API_KEY),
                        createCredentials(AuthenticationType.API_KEY,
                                CredentialsLevel.USER,
                                null,
                                "x-api-key",
                                "apiKeyValue",
                                "userSub"),
                        "apiKeyValue",
                        "x-api-key"
                )
        );
    }

    @ParameterizedTest
    @MethodSource("provideAuthorizationHeaderUserTestArgs")
    void testCreateAuthorizationHeader_UserCredentials(TestArguments arguments) {
        // Given
        CredentialsLocator credentialsLocator = Mockito.mock(CredentialsLocator.class);
        CredentialsDescriptor userCredentialsDescriptor = Mockito.mock(CredentialsDescriptor.class);
        CredentialsDescriptor globalCredentialsDescriptor = Mockito.mock(CredentialsDescriptor.class);

        Map<CredentialsLevel, CredentialsDescriptor> credentialsDescriptors = Map.of(
                CredentialsLevel.USER, userCredentialsDescriptor,
                CredentialsLevel.GLOBAL, globalCredentialsDescriptor);

        when(credentialsLocator.getCredentialsDescriptors()).thenReturn(credentialsDescriptors);
        ResourceAuthSettings authSettings = arguments.authSettings;

        when(resourceCredentialsService.getAndRefreshCredentials(userCredentialsDescriptor, authSettings))
                .thenReturn(arguments.resourceCredentials);

        // When
        AuthorizationHeader header = authorizationHeaderProvider.createAuthorizationHeader(
                credentialsLocator, authSettings, "userSub"
        );

        // Then
        assertNotNull(header);
        assertEquals(arguments.expectedHeaderName, header.getHeaderName());
        assertEquals(arguments.expectedHeaderValue, header.getHeaderValue());
    }

    @Test
    void testCreateAuthorizationHeader_CredentialsNotFound() {
        // Given
        CredentialsLocator credentialsLocator = Mockito.mock(CredentialsLocator.class);
        ResourceAuthSettings authSettings = Mockito.mock(ResourceAuthSettings.class);
        String userSub = "userSub";

        when(credentialsLocator.getResourceId()).thenReturn("testResource");
        when(authSettings.getAuthenticationType()).thenReturn(AuthenticationType.OAUTH);
        when(resourceCredentialsService.getAndRefreshCredentials(any(), any()))
                .thenReturn(null);

        // When & Then
        assertThrows(ResourceNotFoundException.class, () -> {
            authorizationHeaderProvider.createAuthorizationHeader(credentialsLocator, authSettings, userSub);
        });
    }

    @Test
    void testCreateAuthorizationHeader_NoneAuthenticationType() {
        // Given
        CredentialsLocator credentialsLocator = Mockito.mock(CredentialsLocator.class);
        ResourceAuthSettings authSettings = Mockito.mock(ResourceAuthSettings.class);
        String userSub = "userSub";

        when(authSettings.getAuthenticationType()).thenReturn(AuthenticationType.NONE);

        // When
        AuthorizationHeader header = authorizationHeaderProvider.createAuthorizationHeader(
                credentialsLocator, authSettings, userSub
        );

        // Then
        assertNull(header);
    }

    private static ResourceCredentials createCredentials(AuthenticationType authenticationType,
                                                         CredentialsLevel credentialsLevel,
                                                         String accessToken,
                                                         String apiKeyHeader,
                                                         String apiKey,
                                                         String userSub) {
        return ResourceCredentials.builder()
                .authenticationType(authenticationType)
                .credentialsLevel(credentialsLevel)
                .accessToken(accessToken)
                .apiKeyHeader(apiKeyHeader)
                .apiKey(apiKey)
                .userSub(userSub)
                .build();
    }

    private static ResourceAuthSettings createResourceAuthSettings(AuthenticationType authenticationType) {
        return ResourceAuthSettings.builder()
                .authenticationType(authenticationType)
                .build();
    }

    private record TestArguments(ResourceAuthSettings authSettings,
                                 ResourceCredentials resourceCredentials,
                                 String expectedHeaderValue,
                                 String expectedHeaderName) {
    }
}
