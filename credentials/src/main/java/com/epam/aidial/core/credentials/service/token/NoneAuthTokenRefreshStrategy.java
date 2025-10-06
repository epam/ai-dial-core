package com.epam.aidial.core.credentials.service.token;

import com.epam.aidial.core.credentials.data.credentials.ResourceCredentials;

public class NoneAuthTokenRefreshStrategy implements TokenRefreshStrategy {

    @Override
    public boolean hasUnexpiredToken(ResourceCredentials credentials) {
        return true;
    }

    @Override
    public boolean requiresTokenRefresh(ResourceCredentials credentials) {
        return false;
    }
}
