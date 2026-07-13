package com.epam.aidial.core.credentials.service;

import com.epam.aidial.core.config.ResourceAuthSettings;
import com.epam.aidial.core.credentials.data.credentials.RefreshTokenRequest;
import com.epam.aidial.core.credentials.data.credentials.ResourceSignInRequest;
import com.epam.aidial.core.credentials.data.credentials.TokenEndpointAuthMethod;
import com.epam.aidial.core.credentials.data.credentials.TokenRequest;
import com.epam.aidial.core.credentials.data.credentials.TokenResponse;
import com.epam.aidial.core.credentials.util.JsonMapperUtil;
import com.epam.aidial.core.storage.http.HttpException;
import com.epam.aidial.core.storage.http.HttpStatus;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.BiFunction;

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

        TokenResponse tokenResponse = fetchToken(authMethod, resourceAuthSettings, (clientId, clientSecret) ->
                TokenRequest.builder()
                        .code(resourceSignInRequest.getCode())
                        // TODO: do we need to support different?
                        .grantType("authorization_code")
                        .codeVerifier(resourceAuthSettings.getCodeVerifier())
                        .redirectUri(redirectUri)
                        .clientId(clientId)
                        .clientSecret(clientSecret)
                        .build()
                        .buildFormData());
        log.debug("Finished Resource {} token retrieval", resourceId);
        return tokenResponse;
    }

    public TokenResponse getToken(String resourceId,
                                  ResourceAuthSettings resourceAuthSettings,
                                  String refreshToken) {
        log.debug("Start Resource {} refresh token retrieval", resourceId);
        TokenEndpointAuthMethod authMethod = TokenEndpointAuthMethod.resolve(
                resourceAuthSettings.getTokenEndpointAuthMethod(), resourceAuthSettings.getClientSecret());

        TokenResponse tokenResponse = fetchToken(authMethod, resourceAuthSettings, (clientId, clientSecret) ->
                RefreshTokenRequest.builder()
                        .grantType("refresh_token")
                        .refreshToken(refreshToken)
                        .clientId(clientId)
                        .clientSecret(clientSecret)
                        .build()
                        .buildFormData());
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

    // formData receives the client_id/client_secret to place in the request BODY (null = omit).
    private TokenResponse fetchToken(TokenEndpointAuthMethod authMethod,
                                     ResourceAuthSettings settings,
                                     BiFunction<String, String, String> formData) {
        String clientId = settings.getClientId();
        String clientSecret = settings.getClientSecret();
        String tokenEndpoint = settings.getTokenEndpoint();
        return switch (authMethod) {
            case NONE -> doTokenCall(tokenEndpoint, formData.apply(clientId, null), Map.of());
            case CLIENT_SECRET_POST -> doTokenCall(tokenEndpoint, formData.apply(clientId, clientSecret), Map.of());
            case CLIENT_SECRET_BASIC -> {
                // Strict servers (e.g. Notion MCP) enforce RFC 6749 §2.3's single-auth-method rule and
                // reject a body client_id alongside the Basic header, while others (FastMCP's OAuth
                // Proxy) resolve the client by the BODY client_id and fail without it. No single request
                // shape satisfies both, so send the spec-pure request first and retry once with
                // client_id in the body when the failure looks like a client-identification error.
                Map<String, String> headers = Map.of("Authorization", buildBasicAuthHeader(clientId, clientSecret));
                try {
                    yield doTokenCall(tokenEndpoint, formData.apply(null, null), headers);
                } catch (HttpException e) {
                    if (!isClientIdentificationError(e)) {
                        throw e;
                    }
                    log.info("Basic-authenticated token request to {} failed with a client identification error, "
                            + "retrying with client_id in the body", tokenEndpoint);
                    try {
                        yield doTokenCall(tokenEndpoint, formData.apply(clientId, null), headers);
                    } catch (HttpException retryException) {
                        log.warn("Token request retry with client_id in the body failed too: {} {}",
                                retryException.getMessage(), StringUtils.defaultString(retryException.getBody()));
                        throw e;
                    }
                }
            }
        };
    }

    private TokenResponse doTokenCall(String tokenEndpoint, String tokenRequest, Map<String, String> extraHeaders) {
        return resourceAuthorizationClient.executePost(
                tokenEndpoint, tokenRequest,
                "application/x-www-form-urlencoded",
                extraHeaders,
                TokenResponse.class);
    }

    // 401 is RFC 6749 §5.2's designated invalid_client response for header-authenticated requests.
    // The description match exists for proxies that misreport a missing body client_id as
    // invalid_grant/invalid_request (e.g. "Client ID does not match the one used in the initial
    // request"); ordinary grant failures (expired code, bad scope) must not trigger a retry.
    private static boolean isClientIdentificationError(HttpException e) {
        if (e.getStatus() == HttpStatus.UNAUTHORIZED) {
            return true;
        }
        Map<?, ?> payload;
        try {
            payload = JsonMapperUtil.convertToObject(e.getBody(), Map.class);
        } catch (IllegalArgumentException parseError) {
            return false;
        }
        if (payload == null) {
            return false;
        }
        String error = String.valueOf(payload.get("error"));
        if ("invalid_client".equals(error)) {
            return true;
        }
        if (!"invalid_grant".equals(error) && !"invalid_request".equals(error)) {
            return false;
        }
        String description = String.valueOf(payload.get("error_description")).toLowerCase(Locale.ROOT);
        return description.contains("client_id") || description.contains("client id");
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
