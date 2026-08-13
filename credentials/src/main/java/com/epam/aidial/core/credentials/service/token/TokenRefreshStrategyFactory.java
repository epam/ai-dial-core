package com.epam.aidial.core.credentials.service.token;

import com.epam.aidial.core.config.AuthenticationType;
import com.epam.aidial.core.credentials.util.TimeProvider;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public class TokenRefreshStrategyFactory {

    private final TimeProvider timeProvider;

    public TokenRefreshStrategy getTokenValidatorStrategy(AuthenticationType type) {
        if (type == AuthenticationType.OAUTH) {
            return new OauthTokenRefreshStrategy(timeProvider);
        } else if (type == AuthenticationType.API_KEY) {
            return new ApiKeyRefreshStrategy();
        } else if (type == AuthenticationType.NONE || type == AuthenticationType.DIAL_NATIVE) {
            // DIAL_NATIVE records hold no token — an admin's approval neither expires nor refreshes.
            return new NoneAuthTokenRefreshStrategy();
        }
        throw new IllegalArgumentException("Unsupported authentication type: " + type);
    }
}
