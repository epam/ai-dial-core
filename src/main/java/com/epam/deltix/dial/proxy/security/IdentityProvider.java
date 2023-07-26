package com.epam.deltix.dial.proxy.security;

import com.auth0.jwk.GuavaCachedJwkProvider;
import com.auth0.jwk.Jwk;
import com.auth0.jwk.JwkException;
import com.auth0.jwk.UrlJwkProvider;
import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.interfaces.DecodedJWT;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.impl.future.SucceededFuture;
import io.vertx.core.json.JsonObject;

import java.net.MalformedURLException;
import java.net.URL;
import java.security.interfaces.RSAPublicKey;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

public class IdentityProvider {

    private final String appName;

    private final GuavaCachedJwkProvider jwkProvider;

    public IdentityProvider(JsonObject settings) {
        if (settings == null) {
            throw new IllegalArgumentException("Identity provider settings are missed");
        }
        int cacheSize = settings.getInteger("cacheSize", 10);
        long cacheExpiration = settings.getLong("cacheExpiration", 10L);
        TimeUnit cacheExpirationUnit = TimeUnit.valueOf(settings.getString("cacheExpirationUnit", TimeUnit.MINUTES.name()));
        String jwksUrl = Objects.requireNonNull(settings.getString("jwksUrl"), "jwksUrl is missed");
        appName = Objects.requireNonNull(settings.getString("appName"), "appName is missed");
        try {
            jwkProvider = new GuavaCachedJwkProvider(new UrlJwkProvider(new URL(jwksUrl)), cacheSize, cacheExpiration, cacheExpirationUnit);
        } catch (MalformedURLException e) {
            throw new IllegalArgumentException(e);
        }
    }

    @SuppressWarnings("unchecked")
    private List<String> extractUserRoles(DecodedJWT token) {
        var resourceAccess = token.getClaim("resource_access").asMap();
        if (resourceAccess == null) {
            return Collections.emptyList();
        }
        Map<String, Object> app = (Map<String, Object>) resourceAccess.get(appName);
        if (app == null) {
            return Collections.emptyList();
        }
        List<String> roles = (List<String>) app.get("roles");
        return roles == null ? Collections.emptyList() : roles;
    }

    private DecodedJWT decodeAndVerifyJwtToken(String encodedToken) throws JwkException {
        DecodedJWT jwt = JWT.decode(encodedToken);
        Jwk jwk = jwkProvider.get(jwt.getKeyId());
        return JWT.require(Algorithm.RSA256((RSAPublicKey) jwk.getPublicKey(), null)).build().verify(encodedToken);
    }

    public List<String> extractUserRolesFromAuthHeader(String authHeader) throws JwkException {
        // Take the 1st authorization parameter from the header value:
        // Authorization: <auth-scheme> <authorization-parameters>
        String encodedToken = authHeader.split(" ")[1];
        return extractUserRolesFromEncodedToken(encodedToken);
    }

    private List<String> extractUserRolesFromEncodedToken(String encodedToken) throws JwkException {
        if (encodedToken == null) {
            return null;
        }
        DecodedJWT decodedJWT = decodeAndVerifyJwtToken(encodedToken);
        return extractUserRoles(decodedJWT);
    }
}