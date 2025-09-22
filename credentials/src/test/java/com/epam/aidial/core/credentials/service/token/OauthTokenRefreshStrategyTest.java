package com.epam.aidial.core.credentials.service.token;

import com.epam.aidial.core.config.AuthenticationType;
import com.epam.aidial.core.credentials.data.credentials.ResourceCredentials;
import com.epam.aidial.core.credentials.util.TimeProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OauthTokenRefreshStrategyTest {

    @Mock
    private TimeProvider timeProvider;

    @InjectMocks
    private OauthTokenRefreshStrategy oauthTokenRefreshStrategy;

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
        assertEquals(expected, oauthTokenRefreshStrategy.hasUnexpiredToken(creds));
    }

    @Test
    void requiresTokenRefreshParameterizedTest() {
        long now = System.currentTimeMillis();
        ResourceCredentials creds = ResourceCredentials.builder()
                .authenticationType(AuthenticationType.OAUTH)
                .accessToken("access-token")
                .refreshToken("refresh")
                .updatedAt(now)
                .expiresInSeconds(10L)
                .build();
        when(timeProvider.getCurrentTime()).thenReturn(now + TimeUnit.SECONDS.toMillis(20));
        assertTrue(oauthTokenRefreshStrategy.requiresTokenRefresh(creds));
    }

    static Stream<Arguments> provideNotRequiresTokenRefreshCases() {
        long now = System.currentTimeMillis();
        return Stream.of(
                Arguments.of(ResourceCredentials.builder()
                        .authenticationType(AuthenticationType.OAUTH)
                        .refreshToken("refresh")
                        .updatedAt(now)
                        .expiresInSeconds(10L)
                        .build()),
                Arguments.of(ResourceCredentials.builder()
                        .authenticationType(AuthenticationType.OAUTH)
                        .refreshToken(null)
                        .expiresInSeconds(60L)
                        .build()),
                Arguments.of(ResourceCredentials.builder()
                        .authenticationType(AuthenticationType.OAUTH)
                        .refreshToken("refresh")
                        .expiresInSeconds(null)
                        .build()),
                Arguments.of(ResourceCredentials.builder()
                        .authenticationType(AuthenticationType.OAUTH)
                        .refreshToken(null)
                        .expiresInSeconds(null)
                        .build())
        );
    }

    @ParameterizedTest
    @MethodSource("provideNotRequiresTokenRefreshCases")
    void requiresTokenRefreshTest_NoRefresh(ResourceCredentials creds) {
        assertFalse(oauthTokenRefreshStrategy.requiresTokenRefresh(creds));
    }
}
