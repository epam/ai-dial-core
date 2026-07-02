package com.epam.aidial.core.server.security;

import com.epam.aidial.core.config.Key;
import com.epam.aidial.core.server.ProxyContext;
import lombok.experimental.UtilityClass;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * The OBO actor gate: decides whether the caller's own identity matches an application's {@code app_identity}.
 * The caller identity is derived from how it authenticated — a DIAL key (compared as the SHA-256 hex of the key)
 * or an OAuth workload token (compared as its authorized party — {@code azp}, or Azure v1 {@code appid}).
 */
@UtilityClass
public class AppIdentityMatcher {

    public static boolean matches(ProxyContext context, String appIdentity) {
        if (appIdentity == null) {
            return false;
        }
        Key key = context.getKey();
        if (key != null && key.getKey() != null && appIdentity.equals(sha256Hex(key.getKey()))) {
            return true;
        }
        ExtractedClaims claims = context.getExtractedClaims();
        String azp = claims == null ? null : claims.authorizedParty();
        return azp != null && appIdentity.equals(azp);
    }

    private static String sha256Hex(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
