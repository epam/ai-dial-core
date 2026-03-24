package com.epam.aidial.core.credentials.service;

import com.epam.aidial.core.config.ResourceAuthSettings;
import com.epam.aidial.core.credentials.data.credentials.RefreshTokenRequest;
import com.epam.aidial.core.credentials.data.credentials.ResourceSignInRequest;
import com.epam.aidial.core.credentials.data.credentials.TokenRequest;
import com.epam.aidial.core.credentials.data.credentials.TokenResponse;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;

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
                .redirectUri(StringUtils.isNotBlank(resourceSignInRequest.getRedirectUri())
                        ? resourceSignInRequest.getRedirectUri()
                        : resourceAuthSettings.getRedirectUri())
                .build();

        TokenResponse tokenResponse = doTokenCall(resourceAuthSettings.getTokenEndpoint(), tokenRequest.buildFormData());
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

        TokenResponse tokenResponse = doTokenCall(resourceAuthSettings.getTokenEndpoint(), tokenRequest.buildFormData());
        log.debug("Finished Resource {} refresh token retrieval", resourceId);
        return tokenResponse;
    }

    private TokenResponse doTokenCall(String tokenEndpoint, String tokenRequest) {
        return resourceAuthorizationClient.executePost(
                tokenEndpoint, tokenRequest,
                "application/x-www-form-urlencoded",
                TokenResponse.class);
    }
}
