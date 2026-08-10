package com.epam.aidial.core.server.security;

import com.auth0.jwk.Jwk;
import com.auth0.jwk.JwkException;
import com.auth0.jwk.JwkProvider;
import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.interfaces.Claim;
import com.auth0.jwt.interfaces.DecodedJWT;
import com.auth0.jwt.interfaces.Verification;
import com.epam.aidial.core.config.AuthenticationType;
import com.epam.aidial.core.config.ResourceAuthSettings;
import com.epam.aidial.core.server.util.ProxyUtil;
import com.epam.aidial.core.server.vertx.AsyncTaskExecutor;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientOptions;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.RequestOptions;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.http.HttpHeaders;
import org.slf4j.event.Level;

import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPublicKey;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static java.util.Collections.EMPTY_LIST;

@Slf4j
public class IdentityProvider {

    public static final String USER_SUB = "sub";
    public static final String USER_OID = "oid";
    public static final String USER_EMAIL = "email";

    // path(s) to the claim of user roles in JWT
    private final List<String[]> rolePaths = new ArrayList<>();

    // path to the claim containing Project identity
    private final String[] projectPath;

    // Delimiter to split the roles if they are set as a single String
    private final String rolesDelimiter;

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

    private final AsyncTaskExecutor taskExecutor;

    private final HttpClient client;
    private final HttpClientOptions clientOptions;

    // the duration is how many milliseconds success JWK result should be stored in the cache
    private final long positiveCacheExpirationMs;

    // the duration is how many milliseconds failed JWK result should be stored in the cache
    private final long negativeCacheExpirationMs;

    // the pattern is used to match if the given JWT can be verified by the current provider
    private Pattern issuerPattern;

    // the flag disables JWT verification
    private final boolean disableJwtVerification;

    private final GetUserRoleFn getUserRoleFn;

    private final String audience;

    /**
     * OAuth client this provider uses to obtain offline credentials on a user's behalf — a client-side view of
     * the provider that the validation settings above do not carry. Null when the provider offers no offline
     * credentials, which is the default.
     */
    @Getter
    private final ResourceAuthSettings offlineClient;

    /**
     * The path to the claim to extract user display name
     */
    private final String[] userDisplayName;

    /**
     * The path to the claim to extract user ID
     */
    private final String[] userIdPath;

    /**
     * Claim paths to log for debugging purposes
     */
    private final Map<String, String[]> claimPathsToLog;

    /**
     * The log level for claim logging. Defaults to DEBUG.
     */
    private final Level claimsLogLevel;

    IdentityProvider(JsonObject settings, Vertx vertx, AsyncTaskExecutor taskExecutor, HttpClient client,
                            Function<String, JwkProvider> jwkProviderSupplier, GetUserRoleFunctionFactory factory,
                            String claimsLogLevel) {
        this(settings, vertx, taskExecutor, client, new HttpClientOptions(), jwkProviderSupplier, factory, claimsLogLevel);
    }

    IdentityProvider(JsonObject settings, Vertx vertx, AsyncTaskExecutor taskExecutor, HttpClient client, HttpClientOptions clientOptions,
                            Function<String, JwkProvider> jwkProviderSupplier, GetUserRoleFunctionFactory factory,
                            String claimsLogLevel) {
        if (settings == null) {
            throw new IllegalArgumentException("Identity provider settings are missed");
        }
        this.claimsLogLevel = Level.valueOf(claimsLogLevel.toUpperCase());
        this.taskExecutor = taskExecutor;
        this.client = client;
        this.clientOptions = clientOptions;

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
                userInfoUrl = (new URI(userinfoEndpoint)).toURL();
            } catch (MalformedURLException | URISyntaxException e) {
                throw new IllegalArgumentException(e);
            }
        }

        Object rolePathObj = Objects.requireNonNull(settings.getValue("rolePath"), "rolePath is missed");
        List<String> rolePathList;

        if (rolePathObj instanceof String rolePathStr) {
            getUserRoleFn =  factory.getUserRoleFn(rolePathStr);
            rolePathList = List.of(rolePathStr);
        } else if (rolePathObj instanceof JsonArray rolePathArray) {
            getUserRoleFn = null;
            rolePathList = rolePathArray.stream().map(o -> (String) o).toList();
        } else {
            throw new IllegalArgumentException("rolePath should be either String or Array");
        }

        for (String rolePath : rolePathList) {
            rolePaths.add(rolePath.split("\\."));
        }

        projectPath = getClaimPath(settings, "projectPath", null);
        rolesDelimiter = settings.getString("rolesDelimiter");

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

        audience = settings.getString("audience", null);

        offlineClient = parseOfflineClient(settings.getJsonObject("offlineClient"));

        userDisplayName = getClaimPath(settings, "userDisplayName", null);

        userIdPath = getClaimPath(settings, "userIdPath", new String[]{USER_SUB});

        claimPathsToLog = getAsStringList(settings, "claimPathsToLog", List.of(USER_SUB, USER_OID, USER_EMAIL)).stream()
                        .collect(Collectors.toMap(
                                Function.identity(),
                                IdentityProvider::parseClaimPath,
                                (a, b) -> a,
                                LinkedHashMap::new));

        long period = Math.min(negativeCacheExpirationMs, positiveCacheExpirationMs);
        vertx.setPeriodic(0, period, event -> evictExpiredJwks());
    }

    private static String[] getClaimPath(JsonObject settings, String claimName, String[] defaultPath) {
        return settings.containsKey(claimName) ? parseClaimPath(settings.getString(claimName)) : defaultPath;
    }

    /**
     * Builds the offline OAuth client from settings. Modelled as {@link ResourceAuthSettings} so the existing
     * token service can perform both the code exchange and the refresh without a parallel code path.
     */
    private static ResourceAuthSettings parseOfflineClient(JsonObject offlineClient) {
        if (offlineClient == null) {
            return null;
        }
        String clientId = Objects.requireNonNull(offlineClient.getString("clientId"), "offlineClient.clientId is missed");
        String tokenEndpoint = Objects.requireNonNull(offlineClient.getString("tokenEndpoint"), "offlineClient.tokenEndpoint is missed");
        String authorizationEndpoint = Objects.requireNonNull(
                offlineClient.getString("authorizationEndpoint"), "offlineClient.authorizationEndpoint is missed");
        return ResourceAuthSettings.builder()
                .authenticationType(AuthenticationType.OAUTH)
                .clientId(clientId)
                .clientSecret(offlineClient.getString("clientSecret"))
                .authorizationEndpoint(authorizationEndpoint)
                .tokenEndpoint(tokenEndpoint)
                .scopesSupported(getAsStringList(offlineClient, "scopes", List.of("openid", "offline_access")))
                .build();
    }

    private static List<String> getAsStringList(JsonObject settings, String key, List<String> defaultValue) {
        if (!settings.containsKey(key)) {
            return defaultValue;
        }
        Object value = settings.getValue(key);
        if (value instanceof String string) {
            if (StringUtils.isBlank(string)) {
                throw new IllegalArgumentException(key + " should not contain blank values");
            }
            return List.of(string);
        }

        if (value instanceof JsonArray array) {
            List<String> result = new ArrayList<>(array.size());
            for (int i = 0; i < array.size(); i++) {
                String string = array.getString(i);
                if (StringUtils.isBlank(string)) {
                    throw new IllegalArgumentException(key + " should not contain blank values");
                }
                result.add(string);
            }
            return result;
        }

        throw new IllegalArgumentException(key + " should be either String or Array");
    }

    private static String[] parseClaimPath(String claimPath) {
        return claimPath.split("\\.");
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

    private List<String> extractUserRoles(Map<String, Object> map) {
        List<String> result = new ArrayList<>();
        for (String[] rolePath : rolePaths) {
            result.addAll(extractUserRoles(map, rolePath));
        }
        return result;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private List<String> extractUserRoles(Map<String, Object> map, String[] rolePath) {
        Object field = extractClaim(map, rolePath);

        if (field instanceof List list) {
            return list;
        }
        if (field instanceof String string) {
            return getRolesFromString(string);
        }
        return EMPTY_LIST;
    }

    private List<String> getRolesFromString(String rolesString) {
        if (rolesDelimiter == null) {
            return List.of(rolesString);
        }
        return Arrays.stream(rolesString.split(rolesDelimiter))
                .filter(s -> !s.isBlank())
                .toList();
    }

    public static DecodedJWT decodeJwtToken(String encodedToken) {
        return JWT.decode(encodedToken);
    }

    private Future<JwkResult> getJwk(String kid) {
        /* The result of vertx.executeBlocking is a future that contains Vert.x context which is valid during a request
         * execution. So, if we put that future in a cache, it will contain a context from the initial request, that
         * may be invalid for further requests. For this reason, when we retrieve the future from the cache, we must
         * extract the value and put it into another future (Promise) which holds a valid context of a current request.
         * */
        Promise<JwkResult> promise = Promise.promise();
        cache.computeIfAbsent(kid, key -> taskExecutor.submit(() -> {
            JwkResult jwkResult;
            long currentTime = System.currentTimeMillis();
            try {
                Jwk jwk = jwkProvider.get(key);
                jwkResult = new JwkResult(jwk, null, currentTime + positiveCacheExpirationMs);
            } catch (Exception e) {
                jwkResult = new JwkResult(null, e, currentTime + negativeCacheExpirationMs);
            }
            return jwkResult;
        })).onSuccess(promise::complete).onFailure(promise::fail);
        return promise.future();
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
            Verification verification = JWT.require(Algorithm.RSA256((RSAPublicKey) jwk.getPublicKey(), null));
            if (audience != null) {
                verification.withAudience(audience);
            }
            return verification.build().verify(jwt);
        } catch (JwkException e) {
            throw new RuntimeException(e);
        }
    }

    private static String extractStringClaim(Map<String, Object> claims, String[] path) {
        if (path == null) {
            return null;
        }
        Object field = extractClaim(claims, path);

        if (field instanceof String value) {
            return value;
        }
        return null;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Object extractClaim(Map<String, Object> claims, String[] claimPath) {
        for (int i = 0; i < claimPath.length - 1; i++) {
            if (claims.get(claimPath[i]) instanceof Map next) {
                claims = next;
            } else {
                return null;
            }
        }
        return claims.get(claimPath[claimPath.length - 1]);
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
    private ObjectNode extractUserClaims(Map<String, Object> map) {
        return ProxyUtil.MAPPER.valueToTree(map);
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
                .setMethod(HttpMethod.GET)
                .setConnectTimeout(clientOptions.getConnectTimeout())
                .setIdleTimeout(clientOptions.getIdleTimeout());

        Promise<ExtractedClaims> promise = Promise.promise();
        client.request(options).onFailure(promise::fail).onSuccess(request -> {
            request.putHeader(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken);
            request.send().onFailure(promise::fail).onSuccess(response -> {
                if (response.statusCode() != 200) {
                    promise.fail(String.format("UserInfo endpoint '%s' is failed with http code %d", userInfoUrl, response.statusCode()));
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
        return promise.future().onFailure(error -> log.warn("Can't extract claims from user info endpoint '{}':", userInfoUrl, error));
    }

    private ExtractedClaims from(DecodedJWT jwt) {
        String userKey = jwt.getClaim(loggingKey).asString();
        Map<String, Object> map = new HashMap<>();
        for (Map.Entry<String, Claim> e : jwt.getClaims().entrySet()) {
            map.put(e.getKey(), e.getValue().as(Object.class));
        }
        logClaims(map);
        return new ExtractedClaims(extractStringClaim(map, userIdPath), extractUserRoles(map), extractUserHash(userKey),
                extractUserClaims(map), extractStringClaim(map, projectPath), extractStringClaim(map, userDisplayName));
    }

    private void from(String accessToken, JsonObject userInfo, Promise<ExtractedClaims> promise) {
        String userKey = loggingKey == null ? null : userInfo.getString(loggingKey);
        Map<String, Object> map = userInfo.getMap();
        if (getUserRoleFn != null) {
            getUserRoleFn.apply(accessToken, map).onFailure(promise::fail).onSuccess(roles -> {
                logClaims(map);
                ExtractedClaims extractedClaims = new ExtractedClaims(extractStringClaim(map, userIdPath), roles, extractUserHash(userKey),
                        extractUserClaims(map), extractStringClaim(map, projectPath), extractStringClaim(map, userDisplayName));
                promise.complete(extractedClaims);
            });
        } else {
            logClaims(map);
            ExtractedClaims extractedClaims =
                    new ExtractedClaims(extractStringClaim(map, userIdPath), extractUserRoles(map), extractUserHash(userKey),
                            extractUserClaims(map), extractStringClaim(map, projectPath), extractStringClaim(map, userDisplayName));
            promise.complete(extractedClaims);
        }
    }

    private void logClaims(Map<String, Object> claims) {
        if (claimPathsToLog.isEmpty()) {
            return;
        }

        if (log.isEnabledForLevel(claimsLogLevel)) {
            String message = claimPathsToLog.keySet().stream()
                    .map(claim -> claim + "=" + extractClaim(claims, claimPathsToLog.get(claim)))
                    .collect(Collectors.joining(", "));
            log.atLevel(claimsLogLevel).log("User login: {}", message);
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
