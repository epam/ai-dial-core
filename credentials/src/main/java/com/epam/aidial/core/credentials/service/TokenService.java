package com.epam.aidial.core.credentials.service;

import com.epam.aidial.core.config.ResourceAuthSettings;
import com.epam.aidial.core.credentials.data.credentials.RefreshTokenRequest;
import com.epam.aidial.core.credentials.data.credentials.ResourceSignInRequest;
import com.epam.aidial.core.credentials.data.credentials.TokenEndpointAuthMethod;
import com.epam.aidial.core.credentials.data.credentials.TokenRequest;
import com.epam.aidial.core.credentials.data.credentials.TokenResponse;
import com.epam.aidial.core.storage.http.HttpException;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;

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
        String clientId = resourceAuthSettings.getClientId();
        Map<String, String> headers = applyClientAuthentication(authMethod,
                clientId, resourceAuthSettings.getClientSecret(),
                builder::clientId, builder::clientSecret);

        TokenResponse tokenResponse = doTokenCallWithClientIdFallback(
                authMethod, clientId, resourceAuthSettings.getTokenEndpoint(), headers,
                builder.build().buildFormData(),
                () -> builder.clientId(clientId).build().buildFormData());
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
        String clientId = resourceAuthSettings.getClientId();
        Map<String, String> headers = applyClientAuthentication(authMethod,
                clientId, resourceAuthSettings.getClientSecret(),
                builder::clientId, builder::clientSecret);

        TokenResponse tokenResponse = doTokenCallWithClientIdFallback(
                authMethod, clientId, resourceAuthSettings.getTokenEndpoint(), headers,
                builder.build().buildFormData(),
                () -> builder.clientId(clientId).build().buildFormData());
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

    /**
     * Performs the token call with the standard {@code client_secret_basic} presentation (credentials in
     * the Authorization header only). If — and only if — the server rejects it specifically because the
     * body {@code client_id} is missing (e.g. FastMCP's OAuth Proxy, which looks up the client by the body
     * {@code client_id} even when the secret is in the Basic header), it retries once with {@code client_id}
     * added to the body. A wrong-credentials rejection ({@code invalid_client}) does not match and is
     * rethrown unchanged, so it is never masked by a retry.
     */
    private TokenResponse doTokenCallWithClientIdFallback(TokenEndpointAuthMethod authMethod,
                                                          String clientId,
                                                          String tokenEndpoint,
                                                          Map<String, String> headers,
                                                          String formWithoutClientId,
                                                          Supplier<String> formWithClientId) {
        try {
            return doTokenCall(tokenEndpoint, formWithoutClientId, headers);
        } catch (HttpException e) {
            if (authMethod == TokenEndpointAuthMethod.CLIENT_SECRET_BASIC
                    && StringUtils.isNotBlank(clientId)
                    && indicatesMissingClientId(e)) {
                log.info("Token endpoint rejected Basic-only client authentication as missing client_id; "
                        + "retrying once with client_id in the request body");
                return doTokenCall(tokenEndpoint, formWithClientId.get(), headers);
            }
            throw e;
        }
    }

    private static boolean indicatesMissingClientId(HttpException e) {
        String haystack = (StringUtils.defaultString(e.getMessage()) + ' ' + StringUtils.defaultString(e.getBody()))
                .toLowerCase(Locale.ROOT);
        return haystack.contains("client_id")
                && (haystack.contains("missing") || haystack.contains("required"));
    }

    private static Map<String, String> applyClientAuthentication(TokenEndpointAuthMethod authMethod,
                                                                 String clientId,
                                                                 String clientSecret,
                                                                 Consumer<String> setClientId,
                                                                 Consumer<String> setClientSecret) {
        return switch (authMethod) {
            // Standard RFC 6749 §2.3.1: client credentials go in the Basic header ONLY; client_id is NOT
            // repeated in the body. Sending it in both places makes strict servers (e.g. Snowflake's
            // /oauth/token-request) reject with invalid_client. Servers that genuinely require the body
            // client_id (e.g. FastMCP's OAuth Proxy) are handled by doTokenCallWithClientIdFallback.
            case CLIENT_SECRET_BASIC -> Map.of("Authorization", buildBasicAuthHeader(clientId, clientSecret));
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
