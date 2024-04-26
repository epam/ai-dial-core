package com.epam.aidial.core.security;

import com.auth0.jwk.UrlJwkProvider;
import com.auth0.jwt.exceptions.JWTDecodeException;
import com.auth0.jwt.interfaces.DecodedJWT;
import com.google.common.annotations.VisibleForTesting;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpClient;
import io.vertx.core.json.JsonObject;

import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;

public class AccessTokenValidator {

    private static final long USER_INFO_EXP_PERIOD_MS = TimeUnit.MINUTES.toMillis(1);

    private final List<IdentityProvider> providers = new ArrayList<>();

    private final ConcurrentMap<String, Future<UserInfoResult>> userInfoCache = new ConcurrentHashMap<>();

    public AccessTokenValidator(JsonObject idpConfig, Vertx vertx, HttpClient client) {
        int size = idpConfig.size();
        if (size < 1) {
            throw new IllegalArgumentException("At least one identity provider is required");
        }
        for (String idpKey : idpConfig.fieldNames()) {
            providers.add(new IdentityProvider(idpConfig.getJsonObject(idpKey), vertx, client, jwksUrl -> {
                try {
                    return new UrlJwkProvider(new URL(jwksUrl));
                } catch (MalformedURLException e) {
                    throw new IllegalArgumentException(e);
                }
            }));
        }
        vertx.setPeriodic(0, USER_INFO_EXP_PERIOD_MS, event -> evictExpiredUserInfo());
    }

    private void evictExpiredUserInfo() {
        long currentTime = System.currentTimeMillis();
        for (Map.Entry<String, Future<UserInfoResult>> entry : userInfoCache.entrySet()) {
            Future<UserInfoResult> future = entry.getValue();
            if (future.result() != null && future.result().expirationTime() <= currentTime) {
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
            accessToken = Objects.requireNonNull(extractTokenFromHeader(authHeader), "Can't extract access token from header");
            DecodedJWT jwt = IdentityProvider.decodeJwtToken(accessToken);
            return extractClaimsFromJwt(jwt);
        } catch (JWTDecodeException e) {
            // access token is not JWT. let's try to extract claims from user info
            try {
                return extractClaimsFromUserInfo(accessToken);
            } catch (Throwable exp) {
                return Future.failedFuture(exp);
            }
        } catch (Throwable e) {
            return Future.failedFuture(e);
        }
    }

    private Future<ExtractedClaims> extractClaimsFromJwt(DecodedJWT jwt) {
        if (providers.size() == 1) {
            return providers.get(0).extractClaimsFromJwt(jwt);
        }
        for (IdentityProvider idp : providers) {
            if (idp.match(jwt)) {
                return idp.extractClaimsFromJwt(jwt);
            }
        }
        return Future.failedFuture(new IllegalArgumentException("Unknown Identity Provider"));
    }

    private Future<ExtractedClaims> extractClaimsFromUserInfo(String accessToken) {
        return userInfoCache.computeIfAbsent(accessToken, k -> {
            Promise<UserInfoResult> promise = Promise.promise();
            List<Future<ExtractedClaims>> futures = new ArrayList<>();
            for (IdentityProvider idp : providers) {
                if (idp.hasUserinfoUrl()) {
                    futures.add(idp.extractClaimsFromUserInfo(accessToken));
                }
            }
            Future.any(futures).onSuccess(compositeFuture -> {
                int size = compositeFuture.size();
                for (int i = 0; i < size; i++) {
                    if (compositeFuture.succeeded(i)) {
                        ExtractedClaims claims = compositeFuture.resultAt(i);
                        promise.complete(new UserInfoResult(claims, System.currentTimeMillis() + USER_INFO_EXP_PERIOD_MS));
                        return;
                    }
                }
                promise.fail("Idp is not found to support user info endpoint");
            }).onFailure(promise::fail);
            return promise.future();
        }).map(UserInfoResult::claims).onFailure(error -> {
            /* we don't need to keep the failed response any longer */
            userInfoCache.remove(accessToken);
        });
    }

    public static String extractTokenFromHeader(String authHeader) {
        if (authHeader == null) {
            return null;
        }
        String[] parts = authHeader.split(" ");
        if (parts.length < 2) {
            return null;
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
