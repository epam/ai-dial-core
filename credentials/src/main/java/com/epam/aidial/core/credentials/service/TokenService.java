package com.epam.aidial.core.credentials.service;

import com.epam.aidial.core.config.ResourceAuthSettings;
import com.epam.aidial.core.credentials.data.credentials.RefreshTokenRequest;
import com.epam.aidial.core.credentials.data.credentials.ResourceSignInRequest;
import com.epam.aidial.core.credentials.data.credentials.TokenEndpointAuthMethod;
import com.epam.aidial.core.credentials.data.credentials.TokenRequest;
import com.epam.aidial.core.credentials.data.credentials.TokenResponse;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

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
        TokenEndpointAuthMethod authMethod = TokenEndpointAuthMethod.resolve(
                resourceAuthSettings.getTokenEndpointAuthMethod(), resourceAuthSettings.getClientSecret());

        TokenRequest.TokenRequestBuilder builder = TokenRequest.builder()
                .code(resourceSignInRequest.getCode())
                // TODO: do we need to support different?
                .grantType("authorization_code")
                .codeVerifier(resourceAuthSettings.getCodeVerifier())
                .redirectUri(redirectUri);
        Map<String, String> headers = applyClientAuthentication(authMethod,
                resourceAuthSettings.getClientId(), resourceAuthSettings.getClientSecret(),
                builder::clientId, builder::clientSecret);
        TokenRequest tokenRequest = builder.build();

        TokenResponse tokenResponse = doTokenCall(resourceAuthSettings.getTokenEndpoint(), tokenRequest.buildFormData(), headers);
        log.debug("Finished Resource {} token retrieval", resourceId);
        return tokenResponse;
    }

    public TokenResponse getToken(String resourceId,
                                  ResourceAuthSettings resourceAuthSettings,
                                  String refreshToken) {
        log.debug("Start Resource {} refresh token retrieval", resourceId);
        TokenEndpointAuthMethod authMethod = TokenEndpointAuthMethod.resolve(
                resourceAuthSettings.getTokenEndpointAuthMethod(), resourceAuthSettings.getClientSecret());

        RefreshTokenRequest.RefreshTokenRequestBuilder builder = RefreshTokenRequest.builder()
                .grantType("refresh_token")
                .refreshToken(refreshToken);
        Map<String, String> headers = applyClientAuthentication(authMethod,
                resourceAuthSettings.getClientId(), resourceAuthSettings.getClientSecret(),
                builder::clientId, builder::clientSecret);
        RefreshTokenRequest tokenRequest = builder.build();

        TokenResponse tokenResponse = doTokenCall(resourceAuthSettings.getTokenEndpoint(), tokenRequest.buildFormData(), headers);
        log.debug("Finished Resource {} refresh token retrieval", resourceId);
        return tokenResponse;
    }

    private String resolveRedirectUri(ResourceAuthSettings resourceAuthSettings,
                                      ResourceSignInRequest resourceSignInRequest) {
        String requestRedirectUri = resourceSignInRequest.getRedirectUri();

        if (StringUtils.isNotBlank(requestRedirectUri)) {
            if (!isAllowedRedirectUri(requestRedirectUri, resourceAuthSettings)) {
                throw new IllegalArgumentException(
                        "Provided redirect_uri is not in the list of allowed redirect URIs");
            }
            return requestRedirectUri;
        }

        // Fallback to toolset's own redirect_uri (backward compatible)
        return resourceAuthSettings.getRedirectUri();
    }

    private boolean isAllowedRedirectUri(String uri, ResourceAuthSettings resourceAuthSettings) {
        return allowedRedirectUris.contains(uri)
                || uri.equals(resourceAuthSettings.getRedirectUri());
    }

    private TokenResponse doTokenCall(String tokenEndpoint, String tokenRequest, Map<String, String> extraHeaders) {
        return resourceAuthorizationClient.executePost(
                tokenEndpoint, tokenRequest,
                "application/x-www-form-urlencoded",
                extraHeaders,
                TokenResponse.class);
    }

    private static Map<String, String> applyClientAuthentication(TokenEndpointAuthMethod authMethod,
                                                                 String clientId,
                                                                 String clientSecret,
                                                                 Consumer<String> setClientId,
                                                                 Consumer<String> setClientSecret) {
        return switch (authMethod) {
            case CLIENT_SECRET_BASIC -> {
                // Include client_id in the body too (RFC 6749 §3.2.1 permits it). Some token endpoints
                // — e.g. FastMCP's OAuth Proxy — look up the client by the BODY client_id even when the
                // secret is supplied via the Basic header; omitting it yields "Missing client_id".
                setClientId.accept(clientId);
                yield Map.of("Authorization", buildBasicAuthHeader(clientId, clientSecret));
            }
            case NONE -> {
                setClientId.accept(clientId);
                yield Map.of();
            }
            case CLIENT_SECRET_POST -> {
                setClientId.accept(clientId);
                setClientSecret.accept(clientSecret);
                yield Map.of();
            }
        };
    }

    // RFC 6749 §2.3.1 specifies URL-encoding credentials before base64, but real-world
    // authorization servers — Snowflake's /oauth/token-request among them — follow plain
    // RFC 7617 HTTP Basic (Base64(client_id:client_secret), no URL-encoding). URL-encoding
    // a client_id like "abc/xyz=" turns "/" and "=" into "%2F" and "%3D"; servers that don't
    // URL-decode the header then see the wrong credentials and return invalid_client.
    private static String buildBasicAuthHeader(String clientId, String clientSecret) {
        String credentials = StringUtils.defaultString(clientId) + ":" + StringUtils.defaultString(clientSecret);
        return "Basic " + Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
    }
}
