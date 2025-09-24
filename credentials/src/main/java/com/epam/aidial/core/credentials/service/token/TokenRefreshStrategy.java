package com.epam.aidial.core.credentials.service.token;

import com.epam.aidial.core.credentials.data.credentials.ResourceCredentials;

public interface TokenRefreshStrategy {
    boolean hasUnexpiredToken(ResourceCredentials credentials);

    boolean requiresTokenRefresh(ResourceCredentials credentials);
}
