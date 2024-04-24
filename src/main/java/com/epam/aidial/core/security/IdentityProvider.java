package com.epam.aidial.core.security;

import com.auth0.jwk.Jwk;
import com.auth0.jwk.JwkException;
import com.auth0.jwk.JwkProvider;
import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.interfaces.Claim;
import com.auth0.jwt.interfaces.DecodedJWT;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import lombok.extern.slf4j.Slf4j;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPublicKey;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.regex.Pattern;

import static java.util.Collections.EMPTY_LIST;

@Slf4j
public class IdentityProvider {

    // path to the claim of user roles in JWT
    private final String[] rolePath;

    private final JwkProvider jwkProvider;

    // in memory cache store results obtained from JWK provider
    private final ConcurrentHashMap<String, Future<JwkResult>> cache = new ConcurrentHashMap<>();

    // the name of the claim in JWT to extract user email
    private final String loggingKey;
    // random salt is used to digest user email
    private final String loggingSalt;

    private final MessageDigest sha256Digest;

    // the flag determines if user email should be obfuscated
    private final boolean obfuscateUserEmail;

    private final Vertx vertx;

    // the duration is how many milliseconds success JWK result should be stored in the cache
    private final long positiveCacheExpirationMs;

    // the duration is how many milliseconds failed JWK result should be stored in the cache
    private final long negativeCacheExpirationMs;

    // the pattern is used to match if the given JWT can be verified by the current provider
    private final Pattern issuerPattern;

    // the flag disables JWT verification
    private final boolean disableJwtVerification;

    public IdentityProvider(JsonObject settings, Vertx vertx, Function<String, JwkProvider> jwkProviderSupplier) {
        if (settings == null) {
            throw new IllegalArgumentException("Identity provider settings are missed");
        }
        this.vertx = vertx;
        positiveCacheExpirationMs = settings.getLong("positiveCacheExpirationMs", TimeUnit.MINUTES.toMillis(10));
        negativeCacheExpirationMs = settings.getLong("negativeCacheExpirationMs", TimeUnit.SECONDS.toMillis(10));

        disableJwtVerification = settings.getBoolean("disableJwtVerification", false);
        if (disableJwtVerification) {
            jwkProvider = null;
        } else {
            String jwksUrl = Objects.requireNonNull(settings.getString("jwksUrl"), "jwksUrl is missed");
            jwkProvider = jwkProviderSupplier.apply(jwksUrl);
        }

        rolePath = Objects.requireNonNull(settings.getString("rolePath"), "rolePath is missed").split("\\.");

        loggingKey = settings.getString("loggingKey");
        if (loggingKey != null) {
            loggingSalt = Objects.requireNonNull(settings.getString("loggingSalt"), "loggingSalt is missed");
        } else {
            loggingSalt = null;
        }

        try {
            sha256Digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalArgumentException(e);
        }
        obfuscateUserEmail = settings.getBoolean("obfuscateUserEmail", true);

        String issuerPatternStr = settings.getString("issuerPattern");
        if (issuerPatternStr != null) {
            issuerPattern = Pattern.compile(issuerPatternStr);
        } else {
            issuerPattern = null;
        }

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
        if (rolePath.length == 1) {
            List<String> roles = token.getClaim(rolePath[0]).asList(String.class);
            return roles == null ? EMPTY_LIST : roles;
        }
        Map<String, Object> claim = token.getClaim(rolePath[0]).asMap();
        if (claim == null) {
            return EMPTY_LIST;
        }
        for (int i = 1; i < rolePath.length; i++) {
            Object next = claim.get(rolePath[i]);
            if (next == null) {
                return EMPTY_LIST;
            }
            if (i == rolePath.length - 1) {
                if (next instanceof List) {
                    return (List<String>) next;
                }
            } else {
                if (next instanceof Map) {
                    claim = (Map<String, Object>) next;
                } else {
                    return EMPTY_LIST;
                }
            }
        }
        return EMPTY_LIST;
    }

    public static DecodedJWT decodeJwtToken(String encodedToken) {
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

    private Future<DecodedJWT> verifyJwt(DecodedJWT jwt) {
        String kid = jwt.getKeyId();
        Future<JwkResult> future = getJwk(kid);
        return future.map(jwkResult -> verifyJwt(jwt, jwkResult));
    }

    private DecodedJWT verifyJwt(DecodedJWT jwt, JwkResult jwkResult) {
        Exception error = jwkResult.error();
        if (error != null) {
            throw new RuntimeException(error);
        }
        Jwk jwk = jwkResult.jwk();
        try {
            return JWT.require(Algorithm.RSA256((RSAPublicKey) jwk.getPublicKey(), null)).build().verify(jwt);
        } catch (JwkException e) {
            throw new RuntimeException(e);
        }
    }

    private static String extractUserSub(DecodedJWT decodedJwt) {
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

    /**
     * Extracts user claims from decoded JWT. Currently only strings or list of strings/primitives supported.
     * If any other type provided - claim value will not be extracted, see IdentityProviderTest.testExtractClaims_13()
     *
     * @param decodedJwt - decoded JWT
     * @return map of extracted user claims
     */
    private Map<String, List<String>> extractUserClaims(DecodedJWT decodedJwt) {
        Map<String, List<String>> userClaims = new HashMap<>();
        for (Map.Entry<String, Claim> entry : decodedJwt.getClaims().entrySet()) {
            String claimName = entry.getKey();
            Claim claimValue = entry.getValue();
            if (claimValue.asString() != null) {
                userClaims.put(claimName, List.of(claimValue.asString()));
            } else if (claimValue.asList(String.class) != null) {
                userClaims.put(claimName, claimValue.asList(String.class));
            } else {
                // if claim value doesn't match supported type - add claim with empty value
                userClaims.put(claimName, List.of());
            }
        }

        return userClaims;
    }

    Future<ExtractedClaims> extractClaims(DecodedJWT decodedJwt) {
        if (decodedJwt == null) {
            return Future.failedFuture(new IllegalArgumentException("decoded JWT must not be null"));
        }
        if (disableJwtVerification) {
            return Future.succeededFuture(from(decodedJwt));
        }
        return verifyJwt(decodedJwt).map(this::from);
    }

    private ExtractedClaims from(DecodedJWT jwt) {
        return new ExtractedClaims(extractUserSub(jwt), extractUserRoles(jwt), extractUserHash(jwt), extractUserClaims(jwt));
    }

    boolean match(DecodedJWT jwt) {
        if (issuerPattern == null) {
            return false;
        }
        String issuer = jwt.getIssuer();
        return issuerPattern.matcher(issuer).matches();
    }

    private record JwkResult(Jwk jwk, Exception error, long expirationTime) {
    }
}