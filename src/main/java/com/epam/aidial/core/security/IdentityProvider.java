package com.epam.aidial.core.security;

import com.auth0.jwk.Jwk;
import com.auth0.jwk.JwkException;
import com.auth0.jwk.JwkProvider;
import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.interfaces.Claim;
import com.auth0.jwt.interfaces.DecodedJWT;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.RequestOptions;
import io.vertx.core.json.JsonObject;
import lombok.extern.slf4j.Slf4j;
import org.apache.http.HttpHeaders;

import java.net.MalformedURLException;
import java.net.URL;
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

    private JwkProvider jwkProvider;

    private URL userInfoUrl;

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

    private final HttpClient client;

    // the duration is how many milliseconds success JWK result should be stored in the cache
    private final long positiveCacheExpirationMs;

    // the duration is how many milliseconds failed JWK result should be stored in the cache
    private final long negativeCacheExpirationMs;

    // the pattern is used to match if the given JWT can be verified by the current provider
    private Pattern issuerPattern;

    // the flag disables JWT verification
    private final boolean disableJwtVerification;

    private final GetUserRoleFn getUserRoleFn;

    public IdentityProvider(JsonObject settings, Vertx vertx, HttpClient client,
                            Function<String, JwkProvider> jwkProviderSupplier, GetUserRoleFunctionFactory factory) {
        if (settings == null) {
            throw new IllegalArgumentException("Identity provider settings are missed");
        }
        this.vertx = vertx;
        this.client = client;

        positiveCacheExpirationMs = settings.getLong("positiveCacheExpirationMs", TimeUnit.MINUTES.toMillis(10));
        negativeCacheExpirationMs = settings.getLong("negativeCacheExpirationMs", TimeUnit.SECONDS.toMillis(10));

        disableJwtVerification = settings.getBoolean("disableJwtVerification", false);
        String jwksUrl = settings.getString("jwksUrl");
        String userinfoEndpoint = settings.getString("userInfoEndpoint");
        boolean supportJwt = jwksUrl != null || disableJwtVerification;
        boolean supportUserInfo = userinfoEndpoint != null;

        if ((!supportJwt && !supportUserInfo) || (supportJwt && supportUserInfo)) {
            throw new IllegalArgumentException("Either jwksUrl or userinfoEndpoint must be provided or disableJwtVerification is set to true");
        } else if (supportJwt) {
            if (jwksUrl != null) {
                jwkProvider = jwkProviderSupplier.apply(jwksUrl);
            }
            String issuerPatternStr = settings.getString("issuerPattern");
            if (issuerPatternStr != null) {
                issuerPattern = Pattern.compile(issuerPatternStr);
            }
        } else {
            try {
                userInfoUrl = new URL(userinfoEndpoint);
            } catch (MalformedURLException e) {
                throw new IllegalArgumentException(e);
            }
        }

        String rolePathStr = Objects.requireNonNull(settings.getString("rolePath"), "rolePath is missed");
        getUserRoleFn = factory.getUserRoleFn(rolePathStr);
        rolePath = rolePathStr.split("\\.");

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
    private List<String> extractUserRoles(Map<String, Object> map) {
        for (int i = 0; i < rolePath.length; i++) {
            Object next = map.get(rolePath[i]);
            if (next == null) {
                return EMPTY_LIST;
            }
            if (i == rolePath.length - 1) {
                if (next instanceof List) {
                    return (List<String>) next;
                }
            } else {
                if (next instanceof Map) {
                    map = (Map<String, Object>) next;
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
        return cache.computeIfAbsent(kid, key -> vertx.executeBlocking(() -> {
            JwkResult jwkResult;
            long currentTime = System.currentTimeMillis();
            try {
                Jwk jwk = jwkProvider.get(key);
                jwkResult = new JwkResult(jwk, null, currentTime + positiveCacheExpirationMs);
            } catch (Exception e) {
                jwkResult = new JwkResult(null, e, currentTime + negativeCacheExpirationMs);
            }
            return jwkResult;
        }, false));
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

    private static String extractUserSub(Map<String, Object> userContext) {
        return (String) userContext.get("sub");
    }

    private String extractUserHash(String keyClaim) {
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
     * Extracts user claims from user context. Currently only strings or list of strings/primitives supported.
     * If any other type provided - claim value will not be extracted, see IdentityProviderTest.testExtractClaims_13()
     *
     * @param map - user context
     * @return map of extracted user claims
     */
    @SuppressWarnings("unchecked")
    private Map<String, List<String>> extractUserClaims(Map<String, Object> map) {
        Map<String, List<String>> userClaims = new HashMap<>();
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            String claimName = entry.getKey();
            Object claimValue = entry.getValue();
            if (claimValue instanceof String stringClaimValue) {
                userClaims.put(claimName, List.of(stringClaimValue));
            } else if (claimValue instanceof List<?> list && (list.isEmpty() || list.get(0) instanceof String)) {
                userClaims.put(claimName, (List<String>) claimValue);
            } else {
                // if claim value doesn't match supported type - add claim with empty value
                userClaims.put(claimName, List.of());
            }
        }

        return userClaims;
    }

    Future<ExtractedClaims> extractClaimsFromJwt(DecodedJWT decodedJwt) {
        if (decodedJwt == null) {
            return Future.failedFuture(new IllegalArgumentException("decoded JWT must not be null"));
        }
        if (disableJwtVerification) {
            return Future.succeededFuture(from(decodedJwt));
        }
        return verifyJwt(decodedJwt).map(this::from);
    }

    Future<ExtractedClaims> extractClaimsFromUserInfo(String accessToken) {
        RequestOptions options = new RequestOptions()
                .setAbsoluteURI(userInfoUrl)
                .setMethod(HttpMethod.GET);
        Promise<ExtractedClaims> promise = Promise.promise();
        client.request(options).onFailure(promise::fail).onSuccess(request -> {
            request.putHeader(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken);
            request.send().onFailure(promise::fail).onSuccess(response -> {
                if (response.statusCode() != 200) {
                    promise.fail(String.format("Request failed with http code %d", response.statusCode()));
                    return;
                }
                response.body().map(body -> {
                    try {
                        JsonObject json = body.toJsonObject();
                        from(accessToken, json, promise);
                    } catch (Throwable e) {
                        promise.fail(e);
                    }
                    return null;
                }).onFailure(promise::fail);
            });
        });
        return promise.future();
    }

    private ExtractedClaims from(DecodedJWT jwt) {
        String userKey = jwt.getClaim(loggingKey).asString();
        Map<String, Object> map = new HashMap<>();
        for (Map.Entry<String, Claim> e : jwt.getClaims().entrySet()) {
            map.put(e.getKey(), e.getValue().as(Object.class));
        }
        return new ExtractedClaims(extractUserSub(map), extractUserRoles(map), extractUserHash(userKey), extractUserClaims(map));
    }

    private void from(String accessToken, JsonObject userInfo, Promise<ExtractedClaims> promise) {
        String userKey = loggingKey == null ? null : userInfo.getString(loggingKey);
        Map<String, Object> map = userInfo.getMap();
        if (getUserRoleFn != null) {
            getUserRoleFn.apply(accessToken, map).onFailure(promise::fail).onSuccess(roles -> {
                ExtractedClaims extractedClaims = new ExtractedClaims(extractUserSub(map), roles,
                        extractUserHash(userKey), extractUserClaims(map));
                promise.complete(extractedClaims);
            });
        } else {
            ExtractedClaims extractedClaims = new ExtractedClaims(extractUserSub(map), extractUserRoles(map),
                    extractUserHash(userKey), extractUserClaims(map));
            promise.complete(extractedClaims);
        }
    }

    boolean match(DecodedJWT jwt) {
        if (issuerPattern == null) {
            return false;
        }
        String issuer = jwt.getIssuer();
        return issuerPattern.matcher(issuer).matches();
    }

    boolean hasUserinfoUrl() {
        return userInfoUrl != null;
    }

    private record JwkResult(Jwk jwk, Exception error, long expirationTime) {
    }
}