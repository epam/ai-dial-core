package com.epam.aidial.core.server.security;

import com.auth0.jwk.UrlJwkProvider;
import com.auth0.jwt.exceptions.JWTDecodeException;
import com.auth0.jwt.interfaces.DecodedJWT;
import com.epam.aidial.core.server.vertx.AsyncTaskExecutor;
import com.google.common.annotations.VisibleForTesting;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientOptions;
import io.vertx.core.json.JsonObject;
import lombok.extern.slf4j.Slf4j;

import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import javax.annotation.Nullable;

@Slf4j
public class AccessTokenValidator {

    private static final long USER_INFO_EXP_PERIOD_MS = TimeUnit.MINUTES.toMillis(1);

    private final List<IdentityProvider> providers = new ArrayList<>();

    private final ConcurrentMap<String, Future<UserInfoResult>> userInfoCache = new ConcurrentHashMap<>();

    public AccessTokenValidator(JsonObject idpConfig, Vertx vertx, AsyncTaskExecutor taskExecutor, HttpClient client,
                                String claimsLogLevel) {
        this(idpConfig, vertx, taskExecutor, client, new HttpClientOptions(), claimsLogLevel);
    }

    public AccessTokenValidator(JsonObject idpConfig, Vertx vertx, AsyncTaskExecutor taskExecutor, HttpClient client,
                                HttpClientOptions clientOptions, String claimsLogLevel) {
        int size = idpConfig.size();
        if (size < 1) {
            throw new IllegalArgumentException("At least one identity provider is required");
        }
        GetUserRoleFunctionFactory factory = new GetUserRoleFunctionFactory(client);
        for (String idpKey : idpConfig.fieldNames()) {
            providers.add(new IdentityProvider(idpConfig.getJsonObject(idpKey), vertx, taskExecutor, client, clientOptions, jwksUrl -> {
                try {
                    return new UrlJwkProvider(new URL(jwksUrl));
                } catch (MalformedURLException e) {
                    throw new IllegalArgumentException(e);
                }
            }, factory, claimsLogLevel));
        }
        vertx.setPeriodic(0, USER_INFO_EXP_PERIOD_MS, event -> evictExpiredUserInfo());
    }

    private void evictExpiredUserInfo() {
        long currentTime = System.currentTimeMillis();
        for (Map.Entry<String, Future<UserInfoResult>> entry : userInfoCache.entrySet()) {
            UserInfoResult result = entry.getValue().result();
            if (result != null && result.expirationTime() <= currentTime) {
                userInfoCache.remove(entry.getKey());
            }
        }
    }

    public Future<ExtractedClaims> extractClaims(String authHeader) {
        String accessToken = null;
        try {
            if (authHeader == null) {
                return Future.succeededFuture();
            }
            accessToken = Objects.requireNonNull(extractTokenFromHeader(authHeader), "Access token must be presented in Auth header");
            if (providers.size() == 1) {
                IdentityProvider provider =  providers.get(0);
                return extractClaims(accessToken, provider);
            }
            DecodedJWT jwt = IdentityProvider.decodeJwtToken(accessToken);
            return extractClaimsFromJwt(jwt);
        } catch (JWTDecodeException e) {
            log.debug("JWT decoding error occurred: {}. Try to extract claims from user info endpoint.", e.getMessage());
            // access token is not JWT. let's try to extract claims from user info
            return extractClaimsFromUserInfo(accessToken);
        } catch (Throwable e) {
            log.error("Error occurred on processing access token from authorization header", e);
            return Future.failedFuture(e);
        }
    }

    private Future<ExtractedClaims> extractClaims(String accessToken, IdentityProvider provider) {
        if (provider.hasUserinfoUrl()) {
            return extractClaimsFromUserInfo(accessToken, () -> createUserInfoResultFuture(accessToken, provider));
        } else {
            DecodedJWT jwt = IdentityProvider.decodeJwtToken(accessToken);
            return provider.extractClaimsFromJwt(jwt);
        }
    }

    private Future<ExtractedClaims> extractClaimsFromJwt(DecodedJWT jwt) {
        for (IdentityProvider idp : providers) {
            if (idp.match(jwt)) {
                return idp.extractClaimsFromJwt(jwt);
            }
        }
        return Future.failedFuture(new IllegalArgumentException("Unknown Identity Provider"));
    }

    private Future<ExtractedClaims> extractClaimsFromUserInfo(String accessToken) {
        try {
            return extractClaimsFromUserInfo(accessToken, () -> createUserInfoResultFuture(accessToken));
        } catch (Throwable exp) {
            return Future.failedFuture(exp);
        }
    }

    private Future<ExtractedClaims> extractClaimsFromUserInfo(String accessToken, Supplier<Future<UserInfoResult>> fn) {

        return userInfoCache.computeIfAbsent(accessToken, k -> fn.get())
                .map(UserInfoResult::claims).onFailure(error -> {
                    /* we don't need to keep the failed response any longer */
                    userInfoCache.remove(accessToken);
                });
    }

    /**
     * The provider that issued the caller's token. A userinfo-only provider carries no {@code issuerPattern} and so
     * can never match here, and cannot offer offline credentials.
     *
     * @throws IllegalArgumentException when no token is presented or no provider matches.
     * @throws JWTDecodeException when the access token is opaque, so the issuer cannot be read from it.
     */
    public IdentityProvider resolveProvider(String authHeader) {
        if (providers.size() == 1) {
            return providers.get(0);
        }
        String accessToken = extractTokenFromHeader(authHeader);
        if (accessToken == null) {
            throw new IllegalArgumentException("Access token must be presented in Auth header");
        }
        return resolveProviderByIssuer(IdentityProvider.decodeJwtToken(accessToken).getIssuer());
    }

    /**
     * The provider for a recorded issuer, for refreshes where there is no caller token to match on.
     *
     * @throws IllegalArgumentException when no provider matches.
     */
    public IdentityProvider resolveProviderByIssuer(String issuer) {
        if (providers.size() == 1) {
            IdentityProvider provider = providers.get(0);
            // The only candidate, but an issuer it explicitly disclaims means the record was minted by an identity
            // provider that is no longer configured — refreshing it against this one would use the wrong client.
            if (issuer != null && provider.hasIssuerPattern() && !provider.matchesIssuer(issuer)) {
                throw new IllegalArgumentException("Unknown Identity Provider for issuer: " + issuer);
            }
            return provider;
        }
        for (IdentityProvider idp : providers) {
            if (idp.matchesIssuer(issuer)) {
                return idp;
            }
        }
        throw new IllegalArgumentException("Unknown Identity Provider for issuer: " + issuer);
    }

    private Future<UserInfoResult> createUserInfoResultFuture(String accessToken, IdentityProvider idp) {
        Promise<UserInfoResult> promise = Promise.promise();
        idp.extractClaimsFromUserInfo(accessToken).map(claims -> {
            UserInfoResult result = to(claims);
            promise.complete(result);
            return null;
        }).onFailure(promise::fail);
        return promise.future();
    }

    private Future<UserInfoResult> createUserInfoResultFuture(String accessToken) {
        Promise<UserInfoResult> promise = Promise.promise();
        List<Future<ExtractedClaims>> futures = new ArrayList<>();
        for (IdentityProvider idp : providers) {
            if (idp.hasUserinfoUrl()) {
                futures.add(idp.extractClaimsFromUserInfo(accessToken));
            }
        }
        Future.any(futures).map(compositeFuture -> {
            int size = compositeFuture.size();
            for (int i = 0; i < size; i++) {
                if (compositeFuture.succeeded(i)) {
                    ExtractedClaims claims = compositeFuture.resultAt(i);
                    promise.complete(to(claims));
                    return null;
                }
            }
            promise.fail("IdP is not found in Core settings to support user info endpoint for extracting user claims from access token.");
            return null;
        }).onFailure(promise::fail);
        return promise.future();
    }

    private UserInfoResult to(ExtractedClaims claims) {
        return new UserInfoResult(claims, System.currentTimeMillis() + USER_INFO_EXP_PERIOD_MS);
    }

    @Nullable
    public static String extractTokenFromHeader(String authHeader) {
        if (authHeader == null) {
            return null;
        }
        String[] parts = authHeader.split(" ");
        if (parts.length < 2) {
            throw new IllegalArgumentException(String.format("Bad Authorization header format: expected <auth-scheme> <access_token> but got %d parts", parts.length));
        }
        return parts[1];
    }

    @VisibleForTesting
    void setProviders(List<IdentityProvider> providers) {
        this.providers.clear();
        this.providers.addAll(providers);
    }

    private record UserInfoResult(ExtractedClaims claims, long expirationTime) {
    }
}
