package com.epam.aidial.core.credentials.data.credentials;

import com.epam.aidial.core.config.AuthenticationType;
import com.epam.aidial.core.config.CredentialsLevel;
import lombok.Builder;
import lombok.Data;

import java.util.concurrent.TimeUnit;

@Data
@Builder
public class ResourceCredentials {

    private String resourceId;
    private CredentialsLevel credentialsLevel;
    private AuthenticationType authenticationType;
    private String apiKeyHeader;
    private String apiKey;
    private String accessToken;
    private String refreshToken;
    private long createdAt;
    private long updatedAt;
    private Long expiresInSeconds;
    private String userSub;

    public boolean hasUnexpiredToken() {
        validateOauthAuthentication();
        return !supportsTokenRefreshFlow() || isTokenUnexpired();
    }

    public boolean requiresTokenRefresh() {
        validateOauthAuthentication();
        return supportsTokenRefreshFlow() && !isTokenUnexpired();
    }

    private void validateOauthAuthentication() {
        if (!AuthenticationType.OAUTH.equals(authenticationType)) {
            throw new UnsupportedOperationException("Access token exists only for OAuth authentication type.");
        }
    }

    private boolean supportsTokenRefreshFlow() {
        return refreshToken != null && expiresInSeconds != null;
    }

    private boolean isTokenUnexpired() {
        if (updatedAt <= 0 || expiresInSeconds == null || expiresInSeconds <= 0) {
            return false;
        }

        long expiryTimeInMillis = updatedAt + TimeUnit.SECONDS.toMillis(expiresInSeconds);
        return expiryTimeInMillis > System.currentTimeMillis();
    }
}
