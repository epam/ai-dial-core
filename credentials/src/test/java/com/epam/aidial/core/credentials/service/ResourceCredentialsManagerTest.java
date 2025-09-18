package com.epam.aidial.core.credentials.service;

import com.epam.aidial.core.config.AuthenticationType;
import com.epam.aidial.core.config.CredentialsLevel;
import com.epam.aidial.core.config.ResourceAuthSettings;
import com.epam.aidial.core.credentials.data.credentials.AuthorizationHeader;
import com.epam.aidial.core.credentials.data.credentials.CredentialsDescriptor;
import com.epam.aidial.core.credentials.data.credentials.CredentialsLocator;
import com.epam.aidial.core.credentials.data.credentials.ResourceCredentials;
import com.epam.aidial.core.credentials.data.credentials.ResourceSignOutRequest;
import com.epam.aidial.core.credentials.data.credentials.TokenResponse;
import com.epam.aidial.core.storage.exception.ResourceNotFoundException;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
public class ResourceCredentialsManagerTest {

    @InjectMocks
    private ResourceCredentialsManager resourceCredentialsManager;

    @Mock
    private ResourceCredentialsService resourceCredentialsService;

    @Mock
    private TokenService tokenService;

    private static Stream<Arguments> provideResourceCredentialsTestCases() {
        return Stream.of(
                Arguments.of(CredentialsLevel.USER, "test-user", false, false),
                Arguments.of(CredentialsLevel.USER, "wrong-user", false, true),
                Arguments.of(CredentialsLevel.GLOBAL, null, false, false),
                Arguments.of(CredentialsLevel.GLOBAL, "test-user", true, false),
                Arguments.of(CredentialsLevel.USER, null, false, true)
        );
    }

    @ParameterizedTest
    @MethodSource("provideResourceCredentialsTestCases")
    void testGetResourceCredentials(CredentialsLevel level,
                                    String userSub,
                                    boolean requiresRefresh,
                                    boolean shouldThrowException) {
        // Given
        ResourceAuthSettings authSettings = createMockAuthSettings(AuthenticationType.OAUTH);
        ResourceCredentials credentials = createMockCredentials(level, "access-token", "refresh-token",  "test-user");
        credentials.setAuthenticationType(AuthenticationType.OAUTH);

        when(resourceCredentialsService.getAllResourceCredentials(any()))
                .thenReturn(List.of(credentials));

        CredentialsLocator credentialsLocator = createMockCredentialsLocator("resource-id");

        if (requiresRefresh) {
            credentials.setExpiresInSeconds(10L);
            credentials.setUpdatedAt(1000L);
            TokenResponse response = new TokenResponse("new-access-token", "new-refresh-token", 3600L);
            when(tokenService.getToken(anyString(), any(), anyString())).thenReturn(response);
        }

        // When & Then
        if (shouldThrowException) {
            assertThrows(ResourceNotFoundException.class, () ->
                    resourceCredentialsManager.getResourceCredentials(credentialsLocator, authSettings, userSub));
        } else {
            ResourceCredentials result = resourceCredentialsManager.getResourceCredentials(credentialsLocator, authSettings, userSub);

            assertNotNull(result);
            assertEquals(level, result.getCredentialsLevel());
            if (requiresRefresh) {
                verify(resourceCredentialsService).updateAllResourceCredentials(any(), any());
                assertEquals("new-access-token", result.getAccessToken());
                assertEquals("new-refresh-token", result.getRefreshToken());
            }
        }
    }


    @ParameterizedTest
    @CsvSource({
            "OAUTH, Authorization, Bearer test-access-token",
            "API_KEY, x-api-key, test-api-key"
    })
    void testCreateAuthorizationHeader(String authType, String expectedHeaderName, String expectedHeaderValue) {
        // Given
        AuthenticationType authenticationType = AuthenticationType.valueOf(authType);
        CredentialsLocator credentialsLocator = createMockCredentialsLocator("resource-id");

        ResourceCredentials credentials;
        if (authenticationType == AuthenticationType.OAUTH) {
            credentials = createMockCredentials(CredentialsLevel.USER, "test-access-token", null, "test-user");
            credentials.setAuthenticationType(AuthenticationType.OAUTH);
        } else {
            credentials = createMockCredentials(CredentialsLevel.USER, null, null, "test-user");
            credentials.setAuthenticationType(AuthenticationType.API_KEY);
            credentials.setApiKeyHeader("x-api-key");
            credentials.setApiKey("test-api-key");
        }

        when(resourceCredentialsService.getAllResourceCredentials(credentialsLocator))
                .thenReturn(List.of(credentials));

        ResourceAuthSettings resourceAuthSettings = createMockAuthSettings(authenticationType);

        // When
        AuthorizationHeader result = resourceCredentialsManager.createAuthorizationHeader(credentialsLocator, resourceAuthSettings, "test-user");

        // Then
        assertNotNull(result);
        assertEquals(expectedHeaderName, result.getHeaderName());
        assertEquals(expectedHeaderValue, result.getHeaderValue());
    }

    private static Stream<Arguments> provideDeleteResourceCredentialsTestCases() {
        return Stream.of(
                Arguments.of(CredentialsLevel.USER, "test-user", true, false),
                Arguments.of(CredentialsLevel.USER, "wrong-user", false, true),
                Arguments.of(CredentialsLevel.GLOBAL, null, true, false),
                Arguments.of(CredentialsLevel.GLOBAL, "test-user", true, false)
        );
    }

    @ParameterizedTest
    @MethodSource("provideDeleteResourceCredentialsTestCases")
    void testDeleteResourceCredentials(CredentialsLevel credentialLevel,
                                       String userSub,
                                       boolean validDeletion,
                                       boolean shouldThrowException) {
        // Given
        CredentialsLocator locator = createMockCredentialsLocator("resource-id");
        ResourceCredentials credentials = createMockCredentials(credentialLevel, "access-token", "refresh-token", "test-user");

        CredentialsDescriptor descriptor = mock(CredentialsDescriptor.class);
        when(locator.getCredentialsDescriptors()).thenReturn(Map.of(credentialLevel, descriptor));
        when(resourceCredentialsService.getResourceCredentials(descriptor)).thenReturn(credentials);

        ResourceSignOutRequest signOutRequest = mock(ResourceSignOutRequest.class);
        when(signOutRequest.getCredentialsLevel()).thenReturn(credentialLevel);

        if (validDeletion) {
            when(resourceCredentialsService.deleteResourceCredentials(descriptor)).thenReturn(true);
        }

        // When & Then
        if (shouldThrowException) {
            assertThrows(IllegalArgumentException.class, () ->
                    resourceCredentialsManager.deleteResourceCredentials(locator, signOutRequest, userSub));
        } else {
            boolean deleted = resourceCredentialsManager.deleteResourceCredentials(locator, signOutRequest, userSub);
            assertTrue(deleted);
        }
    }

    private ResourceAuthSettings createMockAuthSettings(AuthenticationType type) {
        ResourceAuthSettings authSettings = mock(ResourceAuthSettings.class);
        when(authSettings.getAuthenticationType()).thenReturn(type);
        return authSettings;
    }

    private ResourceCredentials createMockCredentials(CredentialsLevel level, String accessToken, String refreshToken, String userSub) {
        return ResourceCredentials.builder()
                .credentialsLevel(level)
                .accessToken(accessToken)
                .refreshToken(refreshToken)
                .userSub(userSub)
                .build();
    }

    private CredentialsLocator createMockCredentialsLocator(String resourceId) {
        CredentialsLocator locator = mock(CredentialsLocator.class);
        when(locator.getResourceId()).thenReturn(resourceId);
        return locator;
    }
}
