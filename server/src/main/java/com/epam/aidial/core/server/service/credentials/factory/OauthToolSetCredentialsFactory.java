package com.epam.aidial.core.server.service.credentials.factory;

import com.epam.aidial.core.config.ToolSetAuthSettings;
import com.epam.aidial.core.config.ToolSetSignInRequest;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.data.toolset.credentials.TokenResponse;
import com.epam.aidial.core.server.data.toolset.credentials.ToolSetCredentials;
import com.epam.aidial.core.server.service.credentials.ToolSetTokenService;
import lombok.AllArgsConstructor;

@AllArgsConstructor
public class OauthToolSetCredentialsFactory implements ToolSetCredentialsFactory {

    private final ToolSetTokenService toolSetTokenService;

    @Override
    public ToolSetCredentials createCredentials(String toolSetName,
                                                ToolSetAuthSettings authSettings,
                                                ToolSetSignInRequest signInRequest,
                                                ProxyContext context) {
        if (signInRequest.getApiKey() != null) {
            throw new IllegalArgumentException("Api key is not required when auth type is OAUTH");
        }

        TokenResponse tokenResponse = toolSetTokenService.getToken(toolSetName, authSettings, signInRequest);
        long currentTime = System.currentTimeMillis();
        return ToolSetCredentials.builder()
            .toolSetName(toolSetName)
            .credentialsLevel(signInRequest.getCredentialsLevel())
            .authenticationType(signInRequest.getAuthenticationType())
            .accessToken(tokenResponse.getAccessToken())
            .refreshToken(tokenResponse.getRefreshToken())
            .expiresIn(tokenResponse.getExpiresIn())
            .createdAt(currentTime)
            .updatedAt(currentTime)
            .build();
    }
}
