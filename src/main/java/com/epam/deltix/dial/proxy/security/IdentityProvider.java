package com.epam.deltix.dial.proxy.security;

import com.auth0.jwk.GuavaCachedJwkProvider;
import com.auth0.jwk.Jwk;
import com.auth0.jwk.JwkException;
import com.auth0.jwk.UrlJwkProvider;
import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.interfaces.DecodedJWT;
import io.vertx.core.json.JsonObject;

import java.net.MalformedURLException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPublicKey;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

public class IdentityProvider {

    public static final ExtractedClaims CLAIMS_WITH_EMPTY_ROLES = new ExtractedClaims(Collections.emptyList(), null);

    private final String appName;

    private final GuavaCachedJwkProvider jwkProvider;

    private final String loggingKey;
    private final String loggingSalt;

    final MessageDigest sha256Digest;

    public IdentityProvider(JsonObject settings) {
        if (settings == null) {
            throw new IllegalArgumentException("Identity provider settings are missed");
        }
        int cacheSize = settings.getInteger("cacheSize", 10);
        long cacheExpiration = settings.getLong("cacheExpiration", 10L);
        TimeUnit cacheExpirationUnit = TimeUnit.valueOf(settings.getString("cacheExpirationUnit", TimeUnit.MINUTES.name()));
        String jwksUrl = Objects.requireNonNull(settings.getString("jwksUrl"), "jwksUrl is missed");
        appName = Objects.requireNonNull(settings.getString("appName"), "appName is missed");

        loggingKey = settings.getString("loggingKey");
        if (loggingKey != null) {
            loggingSalt = Objects.requireNonNull(settings.getString("loggingSalt"), "loggingSalt is missed");
        } else {
            loggingSalt = null;
        }

        try {
            jwkProvider = new GuavaCachedJwkProvider(new UrlJwkProvider(new URL(jwksUrl)), cacheSize, cacheExpiration, cacheExpirationUnit);
        } catch (MalformedURLException e) {
            throw new IllegalArgumentException(e);
        }

        try {
            sha256Digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
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

    private DecodedJWT decodeJwtToken(String encodedToken) {
        return JWT.decode(encodedToken);
    }

    private DecodedJWT decodeAndVerifyJwtToken(String encodedToken) throws JwkException {
        DecodedJWT jwt = decodeJwtToken(encodedToken);
        Jwk jwk = jwkProvider.get(jwt.getKeyId());
        return JWT.require(Algorithm.RSA256((RSAPublicKey) jwk.getPublicKey(), null)).build().verify(encodedToken);
    }

    private String extractUserHash(final DecodedJWT decodedJWT) {
        final String keyClaim = decodedJWT.getClaim(loggingKey).asString();
        if (keyClaim != null) {
            final String keyClaimWithSalt = loggingSalt + keyClaim;
            final byte[] hash = sha256Digest.digest(keyClaimWithSalt.getBytes(StandardCharsets.UTF_8));

            final StringBuilder hashString = new StringBuilder();
            for (final byte b : hash) {
                hashString.append(String.format("%02x", b));
            }

            return hashString.toString();
        }

        return null;
    }

    public ExtractedClaims extractClaims(final String authHeader,
                                         final boolean isJwtMustBeVerified) throws JwkException  {
        if (authHeader == null) {
            return null;
        }
        // Take the 1st authorization parameter from the header value:
        // Authorization: <auth-scheme> <authorization-parameters>
        final String encodedToken = authHeader.split(" ")[1];
        return extractClaimsFromEncodedToken(encodedToken, isJwtMustBeVerified);
    }

    public ExtractedClaims extractClaimsFromEncodedToken(final String encodedToken,
                                                         final boolean isJwtMustBeVerified) throws JwkException {
        if (encodedToken == null) {
            return null;
        }
        final DecodedJWT decodedJWT = isJwtMustBeVerified ? decodeAndVerifyJwtToken(encodedToken)
                : decodeJwtToken(encodedToken);

        return new ExtractedClaims(extractUserRoles(decodedJWT), extractUserHash(decodedJWT));
    }
}