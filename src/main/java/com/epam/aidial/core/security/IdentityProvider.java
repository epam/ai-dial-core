package com.epam.aidial.core.security;

import com.auth0.jwk.Jwk;
import com.auth0.jwk.JwkException;
import com.auth0.jwk.UrlJwkProvider;
import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.interfaces.DecodedJWT;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import lombok.extern.slf4j.Slf4j;

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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

@Slf4j
public class IdentityProvider {

    public static final ExtractedClaims CLAIMS_WITH_EMPTY_ROLES = new ExtractedClaims(null, Collections.emptyList(), null);

    private final String appName;

    private final UrlJwkProvider jwkProvider;

    private final ConcurrentHashMap<String, Future<JwkResult>> cache = new ConcurrentHashMap<>();

    private final String loggingKey;
    private final String loggingSalt;

    private final MessageDigest sha256Digest;

    private final boolean obfuscateUserEmail;

    private final Vertx vertx;

    private final long positiveCacheExpirationMs;

    private final long negativeCacheExpirationMs;

    public IdentityProvider(JsonObject settings, Vertx vertx) {
        if (settings == null) {
            throw new IllegalArgumentException("Identity provider settings are missed");
        }
        this.vertx = vertx;
        positiveCacheExpirationMs = settings.getLong("positiveCacheExpirationMs", TimeUnit.MINUTES.toMillis(10));
        negativeCacheExpirationMs = settings.getLong("negativeCacheExpirationMs", TimeUnit.SECONDS.toMillis(10));
        String jwksUrl = Objects.requireNonNull(settings.getString("jwksUrl"), "jwksUrl is missed");
        appName = Objects.requireNonNull(settings.getString("appName"), "appName is missed");

        loggingKey = settings.getString("loggingKey");
        if (loggingKey != null) {
            loggingSalt = Objects.requireNonNull(settings.getString("loggingSalt"), "loggingSalt is missed");
        } else {
            loggingSalt = null;
        }

        try {
            jwkProvider = new UrlJwkProvider(new URL(jwksUrl));
        } catch (MalformedURLException e) {
            throw new IllegalArgumentException(e);
        }

        try {
            sha256Digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalArgumentException(e);
        }
        obfuscateUserEmail = settings.getBoolean("obfuscateUserEmail", true);
        long period = Math.min(negativeCacheExpirationMs, positiveCacheExpirationMs);
        vertx.setPeriodic(0, period, event -> evictExpiredJwks());
    }

    private void evictExpiredJwks() {
        long currentTime = System.currentTimeMillis();
        for (Map.Entry<String, Future<JwkResult>> entry : cache.entrySet()) {
            Future<JwkResult> future = entry.getValue();
            if (future.result() != null && future.result().expirationTime() <= currentTime) {
                cache.remove(entry.getKey());
            }
        }
    }

    @SuppressWarnings("unchecked")
    private List<String> extractUserRoles(DecodedJWT token) {
        Map<String, Object> resourceAccess = token.getClaim("resource_access").asMap();
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

    private Future<JwkResult> getJwk(String kid) {
        return cache.computeIfAbsent(kid, key -> vertx.executeBlocking(event -> {
            JwkResult jwkResult;
            long currentTime = System.currentTimeMillis();
            try {
                Jwk jwk = jwkProvider.get(key);
                jwkResult = new JwkResult(jwk, null, currentTime + positiveCacheExpirationMs);
            } catch (Exception e) {
                jwkResult = new JwkResult(null, e, currentTime + negativeCacheExpirationMs);
            }
            event.complete(jwkResult);
        }));
    }

    private Future<DecodedJWT> decodeAndVerifyJwtToken(String encodedToken) {
        DecodedJWT jwt = decodeJwtToken(encodedToken);
        String kid = jwt.getKeyId();
        Future<JwkResult> future = getJwk(kid);
        JwkResult result = future.result();
        if (result != null) {
            return Future.succeededFuture(verifyJwt(encodedToken, result));
        }
        return future.map(jwkResult -> verifyJwt(encodedToken, jwkResult));
    }

    private DecodedJWT verifyJwt(String encodedToken, JwkResult jwkResult) {
        Exception error = jwkResult.error();
        if (error != null) {
            throw new RuntimeException(error);
        }
        Jwk jwk = jwkResult.jwk();
        try {
            return JWT.require(Algorithm.RSA256((RSAPublicKey) jwk.getPublicKey(), null)).build().verify(encodedToken);
        } catch (JwkException e) {
            throw new RuntimeException(e);
        }
    }

    private String extractUserSub(DecodedJWT decodedJwt) {
        return decodedJwt.getClaim("sub").asString();
    }

    private String extractUserHash(DecodedJWT decodedJwt) {
        String keyClaim = decodedJwt.getClaim(loggingKey).asString();
        if (keyClaim != null && obfuscateUserEmail) {
            String keyClaimWithSalt = loggingSalt + keyClaim;
            byte[] hash = sha256Digest.digest(keyClaimWithSalt.getBytes(StandardCharsets.UTF_8));

            StringBuilder hashString = new StringBuilder();
            for (byte b : hash) {
                hashString.append(String.format("%02x", b));
            }

            return hashString.toString();
        }

        return keyClaim;
    }

    public Future<ExtractedClaims> extractClaims(String authHeader, boolean isJwtMustBeVerified) {
        if (authHeader == null) {
            return Future.succeededFuture();
        }
        // Take the 1st authorization parameter from the header value:
        // Authorization: <auth-scheme> <authorization-parameters>
        String encodedToken = authHeader.split(" ")[1];
        return extractClaimsFromEncodedToken(encodedToken, isJwtMustBeVerified);
    }

    public Future<ExtractedClaims> extractClaimsFromEncodedToken(String encodedToken, boolean isJwtMustBeVerified) {
        if (encodedToken == null) {
            return Future.succeededFuture();
        }
        Future<DecodedJWT> decodedJwt = isJwtMustBeVerified ? decodeAndVerifyJwtToken(encodedToken)
                : Future.succeededFuture(decodeJwtToken(encodedToken));
        return decodedJwt.map(jwt -> new ExtractedClaims(extractUserSub(jwt), extractUserRoles(jwt),
                extractUserHash(jwt)));
    }

    private record JwkResult(Jwk jwk, Exception error, long expirationTime) {
    }
}