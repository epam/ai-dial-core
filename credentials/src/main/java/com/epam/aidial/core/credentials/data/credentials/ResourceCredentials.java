package com.epam.aidial.core.credentials.data.credentials;

import com.epam.aidial.core.config.AuthenticationType;
import com.epam.aidial.core.config.CredentialsLevel;
import lombok.Builder;
import lombok.Data;

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
    private Long expiresIn;
    private String userSub;

    public boolean isTokenAlive() {
        if (!AuthenticationType.OAUTH.equals(authenticationType)) {
            throw new UnsupportedOperationException("Access token exists only for OAuth authentication type.");
        }

        return !hasRefreshToken() || isAccessTokenWithinExpiry();
    }

    public boolean needsRefresh() {
        return hasRefreshToken() && !isTokenAlive();
    }

    private boolean hasRefreshToken() {
        return refreshToken != null && expiresIn != null;
    }

    private boolean isAccessTokenWithinExpiry() {
        if (updatedAt <= 0 || expiresIn == null || expiresIn <= 0) {
            return false;
        }

        long expiryTimeInMillis = updatedAt + expiresIn * 1000;
        return expiryTimeInMillis > System.currentTimeMillis();
    }
}
