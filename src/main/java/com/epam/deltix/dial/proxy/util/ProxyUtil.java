package com.epam.deltix.dial.proxy.util;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.interfaces.DecodedJWT;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import io.vertx.core.MultiMap;
import io.vertx.core.http.HttpClientResponse;
import io.vertx.core.http.HttpHeaders;
import io.vertx.core.http.HttpServerRequest;
import lombok.experimental.UtilityClass;

import java.security.interfaces.RSAPublicKey;
import java.util.Collections;
import java.util.List;
import java.util.Map;

@UtilityClass
public class ProxyUtil {

    public static final JsonMapper MAPPER = JsonMapper.builder()
            .enable(MapperFeature.ACCEPT_CASE_INSENSITIVE_ENUMS)
            .build();

    private static final MultiMap HOP_BY_HOP_HEADERS = MultiMap.caseInsensitiveMultiMap()
            .add(HttpHeaders.CONNECTION, "whatever")
            .add(HttpHeaders.KEEP_ALIVE, "whatever")
            .add(HttpHeaders.HOST, "whatever")
            .add(HttpHeaders.PROXY_AUTHENTICATE, "whatever")
            .add(HttpHeaders.PROXY_AUTHORIZATION, "whatever")
            .add("te", "whatever")
            .add("trailer", "whatever")
            .add(HttpHeaders.TRANSFER_ENCODING, "whatever")
            .add(HttpHeaders.UPGRADE, "whatever")
            .add(HttpHeaders.CONTENT_LENGTH, "whatever")
            .add(HttpHeaders.ACCEPT_ENCODING, "whatever");

    public static void copyHeaders(MultiMap from, MultiMap to) {
        for (Map.Entry<String, String> entry : from.entries()) {
            String key = entry.getKey();
            String value = entry.getValue();

            if (!HOP_BY_HOP_HEADERS.contains(key)) {
                to.add(key, value);
            }
        }
    }

    public static int contentLength(HttpServerRequest request, int defaultValue) {
        return contentLength(request.headers(), defaultValue);
    }

    public static int contentLength(HttpClientResponse request, int defaultValue) {
        MultiMap header = request.headers();
        return contentLength(header, defaultValue);
    }

    @SuppressWarnings("unchecked")
    public static List<String> extractUserRoles(DecodedJWT token) {
        var resourceAccess = token.getClaim("resource_access").asMap();
        if (resourceAccess == null) {
            return Collections.emptyList();
        }
        Map<String, Object> app = (Map<String, Object>) resourceAccess.get("openai-proxy");
        if (app == null) {
            return Collections.emptyList();
        }
        List<String> roles = (List<String>) app.get("roles");
        return roles == null ? Collections.emptyList() : roles;
    }

    public static DecodedJWT decodeAndVerifyJwtToken(String encodedToken, RSAPublicKey publicKey) {
        return JWT.require(Algorithm.RSA256(publicKey, null)).build().verify(encodedToken);
    }

    public static List<String> extractUserRolesFromAuthHeader(String authHeader, RSAPublicKey publicKey) {
        if (authHeader == null) {
            return null;
        }

        // Take the 1st authorization parameter from the header value:
        // Authorization: <auth-scheme> <authorization-parameters>
        String encodedToken = authHeader.split(" ")[1];
        return extractUserRolesFromEncodedToken(encodedToken, publicKey);
    }

    public static List<String> extractUserRolesFromEncodedToken(String encodedToken, RSAPublicKey publicKey) {
        if (encodedToken == null) {
            return null;
        }
        DecodedJWT decodedJWT = decodeAndVerifyJwtToken(encodedToken, publicKey);
        return extractUserRoles(decodedJWT);
    }

    private static int contentLength(MultiMap header, int defaultValue) {
        String text = header.get(HttpHeaders.CONTENT_LENGTH);
        if (text != null) {
            try {
                return Integer.parseInt(text);
            } catch (NumberFormatException e) {
                // ignore
            }
        }
        return defaultValue;
    }
}
