package com.epam.aidial.core.server.security;

import com.epam.aidial.core.config.Key;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.util.HashUtil;
import lombok.experimental.UtilityClass;
import org.apache.commons.lang3.StringUtils;

/**
 * The OBO actor gate: decides whether the caller's own identity matches an application's {@code app_identity}.
 * The caller identity is derived from how it authenticated — a DIAL key (compared as the SHA-256 hex of the key)
 * or an OAuth workload token (compared as its authorized party — the {@code azp} claim).
 */
@UtilityClass
public class AppIdentityMatcher {

    public static boolean matches(ProxyContext context, String appIdentity) {
        // Blank ⇒ OBO disabled ⇒ never matches (fail closed).
        if (StringUtils.isBlank(appIdentity)) {
            return false;
        }
        Key key = context.getKey();
        if (key != null && key.getKey() != null && appIdentity.equals(HashUtil.sha256Hex(key.getKey()))) {
            return true;
        }
        ExtractedClaims claims = context.getExtractedClaims();
        String azp = claims == null ? null : claims.authorizedParty();
        return azp != null && appIdentity.equals(azp);
    }
}
