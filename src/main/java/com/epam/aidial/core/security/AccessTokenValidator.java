package com.epam.aidial.core.security;

import com.auth0.jwk.UrlJwkProvider;
import com.auth0.jwt.interfaces.DecodedJWT;
import com.google.common.annotations.VisibleForTesting;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonArray;

import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

public class AccessTokenValidator {

    private final List<IdentityProvider> providers = new ArrayList<>();

    public AccessTokenValidator(JsonArray idpConfig, Vertx vertx) {
        int size = idpConfig.size();
        if (size < 1) {
            throw new IllegalArgumentException("At least one identity provider is required");
        }
        for (int i = 0; i < idpConfig.size(); i++) {
            providers.add(new IdentityProvider(idpConfig.getJsonObject(i), vertx, jwksUrl -> {
                try {
                    return new UrlJwkProvider(new URL(jwksUrl));
                } catch (MalformedURLException e) {
                    throw new IllegalArgumentException(e);
                }
            }));
        }
    }

    public Future<ExtractedClaims> extractClaims(String authHeader, boolean isJwtMustBeValidated) {
        try {
            if (authHeader == null) {
                return isJwtMustBeValidated ? Future.failedFuture(new IllegalArgumentException("Token is missed")) : Future.succeededFuture();
            }
            String encodedToken = authHeader.split(" ")[1];
            DecodedJWT jwt = IdentityProvider.decodeJwtToken(encodedToken);
            for (IdentityProvider idp : providers) {
                if (idp.match(jwt)) {
                    return idp.extractClaims(jwt, isJwtMustBeValidated);
                }
            }
            return Future.failedFuture(new IllegalArgumentException("Unknown Identity Provider"));
        } catch (Throwable e) {
            return Future.failedFuture(e);
        }
    }

    @VisibleForTesting
    void setProviders(List<IdentityProvider> providers) {
        this.providers.clear();
        this.providers.addAll(providers);
    }
}
