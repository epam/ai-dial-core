package com.epam.aidial.core.credentials.service;

import com.epam.aidial.core.config.ResourceAuthSettings;
import com.epam.aidial.core.config.ResourceSignInRequest;
import com.epam.aidial.core.credentials.data.credentials.RefreshTokenRequest;
import com.epam.aidial.core.credentials.data.credentials.TokenRequest;
import com.epam.aidial.core.credentials.data.credentials.TokenResponse;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@AllArgsConstructor
@Slf4j
public class TokenService {

    private final ResourceAuthorizationClient resourceAuthorizationClient;

    public TokenResponse getToken(String resourceId,
                                  ResourceAuthSettings resourceAuthSettings,
                                  ResourceSignInRequest resourceSignInRequest) {
        log.debug("Start Resource {} token retrieval", resourceId);
        TokenRequest tokenRequest = TokenRequest.builder()
                .clientId(resourceAuthSettings.getClientId())
                .clientSecret(resourceAuthSettings.getClientSecret())
                .code(resourceSignInRequest.getCode())
                // TODO: do we need to support different?
                .grantType("authorization_code")
                .codeVerifier(resourceAuthSettings.getCodeVerifier())
                .redirectUri(resourceAuthSettings.getRedirectUri())
                .build();

        TokenResponse tokenResponse = resourceAuthorizationClient.executePost(
                resourceAuthSettings.getTokenEndpoint(),
                tokenRequest.buildFormData(),
                "application/x-www-form-urlencoded",
                TokenResponse.class);
        log.debug("Finished Resource {} token retrieval", resourceId);
        return tokenResponse;
    }

    public TokenResponse getToken(String resourceId,
                                  ResourceAuthSettings resourceAuthSettings,
                                  String refreshToken) {
        log.debug("Start Resource {} reresh token retrieval", resourceId);
        RefreshTokenRequest tokenRequest = RefreshTokenRequest.builder()
                .clientId(resourceAuthSettings.getClientId())
                .clientSecret(resourceAuthSettings.getClientSecret())
                .grantType("refresh_token")
                .refreshToken(refreshToken)
                .build();

        TokenResponse tokenResponse = resourceAuthorizationClient.executePost(
                resourceAuthSettings.getTokenEndpoint(),
                tokenRequest.buildFormData(),
                "application/x-www-form-urlencoded",
                TokenResponse.class);
        log.debug("Finished Resource {} refresh token retrieval", resourceId);
        return tokenResponse;
    }
}
