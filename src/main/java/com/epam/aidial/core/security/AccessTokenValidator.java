package com.epam.aidial.core.security;

import com.auth0.jwk.UrlJwkProvider;
import com.auth0.jwt.interfaces.DecodedJWT;
import com.google.common.annotations.VisibleForTesting;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;

import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public class AccessTokenValidator {

    private final List<IdentityProvider> providers = new ArrayList<>();

    public AccessTokenValidator(JsonObject idpConfig, Vertx vertx) {
        int size = idpConfig.size();
        if (size < 1) {
            throw new IllegalArgumentException("At least one identity provider is required");
        }
        for (String idpKey : idpConfig.fieldNames()) {
            providers.add(new IdentityProvider(idpConfig.getJsonObject(idpKey), vertx, jwksUrl -> {
                try {
                    return new UrlJwkProvider(new URL(jwksUrl));
                } catch (MalformedURLException e) {
                    throw new IllegalArgumentException(e);
                }
            }));
        }
    }

    public Future<ExtractedClaims> extractClaims(String authHeader) {
        try {
            if (authHeader == null) {
                return Future.succeededFuture();
            }
            String encodedToken = Objects.requireNonNull(extractTokenFromHeader(authHeader), "Can't extract access token from header");
            DecodedJWT jwt = IdentityProvider.decodeJwtToken(encodedToken);
            if (providers.size() == 1) {
                return providers.get(0).extractClaims(jwt);
            }
            for (IdentityProvider idp : providers) {
                if (idp.match(jwt)) {
                    return idp.extractClaims(jwt);
                }
            }
            return Future.failedFuture(new IllegalArgumentException("Unknown Identity Provider"));
        } catch (Throwable e) {
            return Future.failedFuture(e);
        }
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
}
