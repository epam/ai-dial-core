package com.epam.aidial.core.credentials.service.token;

import com.epam.aidial.core.credentials.data.credentials.ResourceCredentials;
import com.epam.aidial.core.credentials.util.TimeProvider;
import lombok.RequiredArgsConstructor;

import java.util.concurrent.TimeUnit;

@RequiredArgsConstructor
public class OauthTokenRefreshStrategy implements TokenRefreshStrategy {

    private final TimeProvider timeProvider;

    @Override
    public boolean hasUnexpiredToken(ResourceCredentials credentials) {
        return credentials.getAccessToken() != null
                && (isTokenUnexpired(credentials.getUpdatedAt(), credentials.getExpiresInSeconds())
                    || credentials.getRefreshToken() != null);
    }

    @Override
    public boolean requiresTokenRefresh(ResourceCredentials credentials) {
        return supportsTokenRefreshFlow(credentials.getRefreshToken(), credentials.getExpiresInSeconds())
                && !isTokenUnexpired(credentials.getUpdatedAt(), credentials.getExpiresInSeconds());
    }

    private boolean supportsTokenRefreshFlow(String refreshToken,
                                             Long expiresInSeconds) {
        return refreshToken != null && expiresInSeconds != null;
    }

    private boolean isTokenUnexpired(Long updatedAt,
                                     Long expiresInSeconds) {
        if (expiresInSeconds == null) {
            return true;
        }

        if (updatedAt <= 0 || expiresInSeconds <= 0) {
            return false;
        }

        long expiryTimeInMillis = updatedAt + TimeUnit.SECONDS.toMillis(expiresInSeconds);
        return expiryTimeInMillis > timeProvider.getCurrentTime();
    }
}
