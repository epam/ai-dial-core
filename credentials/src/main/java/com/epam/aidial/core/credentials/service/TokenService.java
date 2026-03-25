package com.epam.aidial.core.credentials.service;

import com.epam.aidial.core.config.ResourceAuthSettings;
import com.epam.aidial.core.credentials.data.credentials.RefreshTokenRequest;
import com.epam.aidial.core.credentials.data.credentials.ResourceSignInRequest;
import com.epam.aidial.core.credentials.data.credentials.TokenRequest;
import com.epam.aidial.core.credentials.data.credentials.TokenResponse;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;

import java.util.List;

@AllArgsConstructor
@Slf4j
public class TokenService {

    private final ResourceAuthorizationClient resourceAuthorizationClient;
    private final List<String> allowedRedirectUris;

    public TokenResponse getToken(String resourceId,
                                  ResourceAuthSettings resourceAuthSettings,
                                  ResourceSignInRequest resourceSignInRequest) {
        log.debug("Start Resource {} token retrieval", resourceId);
        String redirectUri = resolveRedirectUri(resourceAuthSettings, resourceSignInRequest);
        TokenRequest tokenRequest = TokenRequest.builder()
                .clientId(resourceAuthSettings.getClientId())
                .clientSecret(resourceAuthSettings.getClientSecret())
                .code(resourceSignInRequest.getCode())
                // TODO: do we need to support different?
                .grantType("authorization_code")
                .codeVerifier(resourceAuthSettings.getCodeVerifier())
                .redirectUri(redirectUri)
                .build();

        TokenResponse tokenResponse = doTokenCall(resourceAuthSettings.getTokenEndpoint(), tokenRequest.buildFormData());
        log.debug("Finished Resource {} token retrieval", resourceId);
        return tokenResponse;
    }

    public TokenResponse getToken(String resourceId,
                                  ResourceAuthSettings resourceAuthSettings,
                                  String refreshToken) {
        log.debug("Start Resource {} refresh token retrieval", resourceId);
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

    private String resolveRedirectUri(ResourceAuthSettings resourceAuthSettings,
                                      ResourceSignInRequest resourceSignInRequest) {
        String requestRedirectUri = resourceSignInRequest.getRedirectUri();

        if (StringUtils.isNotBlank(requestRedirectUri)) {
            List<String> effectiveAllowedUris = getEffectiveAllowedUris(resourceAuthSettings);
            if (!effectiveAllowedUris.contains(requestRedirectUri)) {
                throw new IllegalArgumentException(
                        "Provided redirect_uri is not in the list of allowed redirect URIs");
            }
            return requestRedirectUri;
        }

        // Fallback to toolset's own redirect_uri (backward compatible)
        return resourceAuthSettings.getRedirectUri();
    }

    private List<String> getEffectiveAllowedUris(ResourceAuthSettings resourceAuthSettings) {
        return RedirectUriHelper.collectAllowedRedirectUris(allowedRedirectUris, resourceAuthSettings);
    }

    private TokenResponse doTokenCall(String tokenEndpoint, String tokenRequest) {
        return resourceAuthorizationClient.executePost(
                tokenEndpoint, tokenRequest,
                "application/x-www-form-urlencoded",
                TokenResponse.class);
    }
}
