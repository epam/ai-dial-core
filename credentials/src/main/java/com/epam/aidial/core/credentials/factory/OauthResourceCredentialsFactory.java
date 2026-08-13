package com.epam.aidial.core.credentials.factory;

import com.epam.aidial.core.config.ResourceAuthSettings;
import com.epam.aidial.core.credentials.data.credentials.ResourceCredentials;
import com.epam.aidial.core.credentials.data.credentials.ResourceSignInRequest;
import com.epam.aidial.core.credentials.data.credentials.TokenResponse;
import com.epam.aidial.core.credentials.service.TokenService;
import lombok.AllArgsConstructor;

@AllArgsConstructor
public class OauthResourceCredentialsFactory implements ResourceCredentialsFactory {

    private final TokenService tokenService;

    @Override
    public ResourceCredentials createCredentials(String resourceId,
                                                 ResourceAuthSettings authSettings,
                                                 ResourceSignInRequest signInRequest) {
        if (signInRequest.getApiKey() != null) {
            throw new IllegalArgumentException("Api key is not required when auth type is OAUTH");
        }

        TokenResponse tokenResponse = tokenService.getToken(resourceId, authSettings, signInRequest);
        long currentTime = System.currentTimeMillis();
        return ResourceCredentials.builder()
            .resourceId(resourceId)
            .credentialsLevel(signInRequest.getCredentialsLevel())
            .authenticationType(signInRequest.getAuthenticationType())
            .accessToken(tokenResponse.getAccessToken())
            .refreshToken(tokenResponse.getRefreshToken())
            .idToken(tokenResponse.getIdToken())
            .expiresInSeconds(tokenResponse.getExpiresIn())
            .createdAt(currentTime)
            .updatedAt(currentTime)
            .build();
    }
}
