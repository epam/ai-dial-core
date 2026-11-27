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
                        .accessToken("access")
                        .refreshToken("refresh")
                        .updatedAt(now)
                        .expiresInSeconds(10L)
                        .build(), true),
                Arguments.of(ResourceCredentials.builder()
                        .authenticationType(AuthenticationType.OAUTH)
                        .accessToken("access")
                        .refreshToken(null)
                        .updatedAt(now)
                        .expiresInSeconds(10L)
                        .build(), true),

                Arguments.of(ResourceCredentials.builder()
                        .authenticationType(AuthenticationType.OAUTH)
                        .accessToken("access")
                        .refreshToken(null)
                        .expiresInSeconds(null)
                        .build(), true),
                Arguments.of(ResourceCredentials.builder()
                        .authenticationType(AuthenticationType.OAUTH)
                        .accessToken("access")
                        .refreshToken("refresh")
                        .updatedAt(now)
                        .expiresInSeconds(null)
                        .build(), true),
                // expired access token + refresh token present → SIGNED_IN (can refresh)
                Arguments.of(ResourceCredentials.builder()
                        .authenticationType(AuthenticationType.OAUTH)
                        .accessToken("access")
                        .refreshToken("refresh")
                        .updatedAt(0)
                        .expiresInSeconds(10L)
                        .build(), true),
                Arguments.of(ResourceCredentials.builder()
                        .authenticationType(AuthenticationType.OAUTH)
                        .accessToken("access")
                        .refreshToken("refresh")
                        .updatedAt(now)
                        .expiresInSeconds(0L)
                        .build(), true),
                // expired access token + no refresh token → SIGNED_OUT
                Arguments.of(ResourceCredentials.builder()
                        .authenticationType(AuthenticationType.OAUTH)
                        .accessToken("access")
                        .refreshToken(null)
                        .updatedAt(0)
                        .expiresInSeconds(10L)
                        .build(), false),
                Arguments.of(ResourceCredentials.builder()
                        .authenticationType(AuthenticationType.OAUTH)
                        .accessToken("access")
                        .refreshToken(null)
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

    static Stream<Arguments> provideRefreshMarginCases() {
        long hour = TimeUnit.HOURS.toSeconds(1);
        return Stream.of(
                Arguments.of(2 * hour, TimeUnit.SECONDS.toMillis(2 * hour) - 3_600, true),
                Arguments.of(2 * hour, TimeUnit.SECONDS.toMillis(2 * hour) - TimeUnit.MINUTES.toMillis(4), true),
                Arguments.of(2 * hour, TimeUnit.SECONDS.toMillis(2 * hour) - TimeUnit.MINUTES.toMillis(6), false),
                Arguments.of(60L, TimeUnit.SECONDS.toMillis(20), false),
                Arguments.of(60L, TimeUnit.SECONDS.toMillis(40), true)
        );
    }

    @ParameterizedTest
    @MethodSource("provideRefreshMarginCases")
    void requiresTokenRefreshBeforeExpiryTest(long expiresInSeconds, long elapsedMillis, boolean expected) {
        long now = System.currentTimeMillis();
        ResourceCredentials creds = ResourceCredentials.builder()
                .authenticationType(AuthenticationType.OAUTH)
                .accessToken("access-token")
                .refreshToken("refresh")
                .updatedAt(now)
                .expiresInSeconds(expiresInSeconds)
                .build();
        when(timeProvider.getCurrentTime()).thenReturn(now + elapsedMillis);
        assertEquals(expected, oauthTokenRefreshStrategy.requiresTokenRefresh(creds));
    }

    @Test
    void hasUnexpiredTokenIgnoresRefreshMarginTest() {
        long now = System.currentTimeMillis();
        ResourceCredentials creds = ResourceCredentials.builder()
                .authenticationType(AuthenticationType.OAUTH)
                .accessToken("access-token")
                .refreshToken(null)
                .updatedAt(now)
                .expiresInSeconds(TimeUnit.HOURS.toSeconds(2))
                .build();
        when(timeProvider.getCurrentTime()).thenReturn(now + TimeUnit.HOURS.toMillis(2) - 3_600);
        assertTrue(oauthTokenRefreshStrategy.hasUnexpiredToken(creds));
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
