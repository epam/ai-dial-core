package com.epam.aidial.core.credentials.service.token;

import com.epam.aidial.core.credentials.data.credentials.ResourceCredentials;
import com.epam.aidial.core.credentials.util.TimeProvider;
import lombok.RequiredArgsConstructor;

import java.util.concurrent.TimeUnit;

@RequiredArgsConstructor
public class OauthTokenRefreshStrategy implements TokenRefreshStrategy {

    /** Refresh a token this long before it expires, capped at half its lifetime. */
    static final long MAX_REFRESH_MARGIN_MILLIS = TimeUnit.MINUTES.toMillis(5);

    private final TimeProvider timeProvider;

    @Override
    public boolean hasUnexpiredToken(ResourceCredentials credentials) {
        return credentials.getAccessToken() != null
                && (isTokenUnexpired(credentials.getUpdatedAt(), credentials.getExpiresInSeconds(), 0)
                    || credentials.getRefreshToken() != null);
    }

    @Override
    public boolean requiresTokenRefresh(ResourceCredentials credentials) {
        Long expiresInSeconds = credentials.getExpiresInSeconds();
        return supportsTokenRefreshFlow(credentials.getRefreshToken(), expiresInSeconds)
                && !isTokenUnexpired(credentials.getUpdatedAt(), expiresInSeconds, resolveRefreshMarginMillis(expiresInSeconds));
    }

    private boolean supportsTokenRefreshFlow(String refreshToken,
                                             Long expiresInSeconds) {
        return refreshToken != null && expiresInSeconds != null;
    }

    private static long resolveRefreshMarginMillis(Long expiresInSeconds) {
        if (expiresInSeconds == null || expiresInSeconds <= 0) {
            return 0;
        }
        return Math.min(MAX_REFRESH_MARGIN_MILLIS, TimeUnit.SECONDS.toMillis(expiresInSeconds) / 2);
    }

    private boolean isTokenUnexpired(Long updatedAt,
                                     Long expiresInSeconds,
                                     long marginMillis) {
        if (expiresInSeconds == null) {
            return true;
        }

        if (updatedAt <= 0 || expiresInSeconds <= 0) {
            return false;
        }

        long expiryTimeInMillis = updatedAt + TimeUnit.SECONDS.toMillis(expiresInSeconds);
        return expiryTimeInMillis - marginMillis > timeProvider.getCurrentTime();
    }
}
