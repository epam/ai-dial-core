package com.epam.aidial.core.server.service.credentials;

import com.epam.aidial.core.config.ToolSetAuthSettings;
import com.epam.aidial.core.config.ToolSetSignInRequest;
import com.epam.aidial.core.server.data.toolset.credentials.RefreshTokenRequest;
import com.epam.aidial.core.server.data.toolset.credentials.TokenRequest;
import com.epam.aidial.core.server.data.toolset.credentials.TokenResponse;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@AllArgsConstructor
@Slf4j
public class ToolSetTokenService {

    private final ToolSetAuthorizationClient toolSetAuthorizationClient;

    public TokenResponse getToken(String toolSetName,
                                  ToolSetAuthSettings toolSetAuthSettings,
                                  ToolSetSignInRequest toolSetSignInRequest) {
        log.debug("Start ToolSet {} token retrieval", toolSetName);
        TokenRequest tokenRequest = TokenRequest.builder()
                .clientId(toolSetAuthSettings.getClientId())
                .clientSecret(toolSetAuthSettings.getClientSecret())
                .code(toolSetSignInRequest.getCode())
                // TODO: do we need to support different?
                .grantType("authorization_code")
                .codeVerifier(toolSetAuthSettings.getCodeVerifier())
                .redirectUri(toolSetAuthSettings.getRedirectUri())
                .build();

        TokenResponse tokenResponse = toolSetAuthorizationClient.executePost(
                toolSetAuthSettings.getTokenEndpoint(),
                tokenRequest.buildFormData(),
                "application/x-www-form-urlencoded",
                TokenResponse.class);
        log.debug("Finished ToolSet {} token retrieval", toolSetName);
        return tokenResponse;
    }

    public TokenResponse getToken(String toolSetName,
                                  ToolSetAuthSettings toolSetAuthSettings,
                                  String refreshToken) {
        log.debug("Start ToolSet {} reresh token retrieval", toolSetName);
        RefreshTokenRequest tokenRequest = RefreshTokenRequest.builder()
                .clientId(toolSetAuthSettings.getClientId())
                .clientSecret(toolSetAuthSettings.getClientSecret())
                .grantType("refresh_token")
                .refreshToken(refreshToken)
                .build();

        TokenResponse tokenResponse = toolSetAuthorizationClient.executePost(
                toolSetAuthSettings.getTokenEndpoint(),
                tokenRequest.buildFormData(),
                "application/x-www-form-urlencoded",
                TokenResponse.class);
        log.debug("Finished ToolSet {} refresh token retrieval", toolSetName);
        return tokenResponse;
    }
}
