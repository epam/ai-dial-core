package com.epam.aidial.core.credentials.data;

import com.epam.aidial.core.config.AuthenticationType;
import com.epam.aidial.core.credentials.data.credentials.ResourceCredentials;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ResourceCredentialsTest {

    static Stream<Arguments> provideHasUnexpiredTokenCases() {
        long now = System.currentTimeMillis();
        return Stream.of(
                Arguments.of(ResourceCredentials.builder()
                        .authenticationType(AuthenticationType.OAUTH)
                        .refreshToken("refresh")
                        .updatedAt(now)
                        .expiresInSeconds(10L)
                        .build(), true),
                Arguments.of(ResourceCredentials.builder()
                        .authenticationType(AuthenticationType.OAUTH)
                        .accessToken("token")
                        .refreshToken(null)
                        .expiresInSeconds(null)
                        .build(), true),
                Arguments.of(ResourceCredentials.builder()
                        .authenticationType(AuthenticationType.OAUTH)
                        .refreshToken("refresh")
                        .updatedAt(now)
                        .expiresInSeconds(null)
                        .build(), true),
                Arguments.of(ResourceCredentials.builder()
                        .authenticationType(AuthenticationType.OAUTH)
                        .refreshToken(null)
                        .updatedAt(now)
                        .expiresInSeconds(10L)
                        .build(), true),

                Arguments.of(ResourceCredentials.builder()
                        .authenticationType(AuthenticationType.OAUTH)
                        .refreshToken("refresh")
                        .updatedAt(0)
                        .expiresInSeconds(10L)
                        .build(), false),
                Arguments.of(ResourceCredentials.builder()
                        .authenticationType(AuthenticationType.OAUTH)
                        .refreshToken("refresh")
                        .updatedAt(now)
                        .expiresInSeconds(0L)
                        .build(), false)
        );
    }

    @ParameterizedTest
    @MethodSource("provideHasUnexpiredTokenCases")
    void hasUnexpiredTokenParameterizedTest(ResourceCredentials creds, boolean expected) {
        assertEquals(expected, creds.hasUnexpiredToken());
    }

    static Stream<Arguments> provideRequiresTokenRefreshCases() {
        long now = System.currentTimeMillis();
        return Stream.of(
                Arguments.of(ResourceCredentials.builder()
                        .authenticationType(AuthenticationType.OAUTH)
                        .refreshToken("refresh")
                        .updatedAt(now - TimeUnit.SECONDS.toMillis(20))
                        .expiresInSeconds(10L)
                        .build(), true),
                Arguments.of(ResourceCredentials.builder()
                        .authenticationType(AuthenticationType.OAUTH)
                        .refreshToken("refresh")
                        .updatedAt(now)
                        .expiresInSeconds(10L)
                        .build(), false),
                Arguments.of(ResourceCredentials.builder()
                        .authenticationType(AuthenticationType.OAUTH)
                        .refreshToken(null)
                        .expiresInSeconds(60L)
                        .build(), false),
                Arguments.of(ResourceCredentials.builder()
                        .authenticationType(AuthenticationType.OAUTH)
                        .refreshToken("refresh")
                        .expiresInSeconds(null)
                        .build(), false),
                Arguments.of(ResourceCredentials.builder()
                        .authenticationType(AuthenticationType.OAUTH)
                        .refreshToken(null)
                        .expiresInSeconds(null)
                        .build(), false)
        );
    }

    @ParameterizedTest
    @MethodSource("provideRequiresTokenRefreshCases")
    void requiresTokenRefreshParameterizedTest(ResourceCredentials creds, boolean expected) {
        assertEquals(expected, creds.requiresTokenRefresh());
    }

    @ParameterizedTest
    @EnumSource(
            value = AuthenticationType.class,
            names = {"API_KEY", "NONE"}
    )
    void hasUnexpiredToken_shouldThrowUnsupportedOperationExceptionIfNotOauth(AuthenticationType type) {
        ResourceCredentials resourceCredentials = ResourceCredentials.builder()
                .authenticationType(type)
                .build();

        UnsupportedOperationException ex = assertThrows(
                UnsupportedOperationException.class,
                resourceCredentials::hasUnexpiredToken
        );
        assertEquals("Access token exists only for OAuth authentication type.", ex.getMessage());
    }

    @ParameterizedTest
    @EnumSource(
            value = AuthenticationType.class,
            names = {"API_KEY", "NONE"}
    )
    void requiresTokenRefresh_shouldThrowUnsupportedOperationExceptionIfNotOauth(AuthenticationType type) {
        ResourceCredentials resourceCredentials = ResourceCredentials.builder()
                .authenticationType(type)
                .build();

        UnsupportedOperationException ex = assertThrows(
                UnsupportedOperationException.class,
                resourceCredentials::requiresTokenRefresh
        );
        assertEquals("Access token exists only for OAuth authentication type.", ex.getMessage());
    }
}
