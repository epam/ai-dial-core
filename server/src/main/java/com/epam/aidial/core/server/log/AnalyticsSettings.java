package com.epam.aidial.core.server.log;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import lombok.Builder;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import javax.annotation.Nullable;

/**
 * What each analytics log entry is allowed to carry, from the static {@code analytics} settings.
 *
 * @param collectAllClaims true when the claim allowlist contains {@value #ALL_CLAIMS}, i.e. every claim is collected.
 * @param claimsAllowlist  claim path as configured, mapped to its segments; never null, empty means collect none.
 *                         Ordered, so log entries list claims in the order the operator declared them.
 * @param headersBlacklist never null; empty means block nothing.
 * @param headersAllowlist null means the allowlist is disabled, i.e. collect all non-blocked headers.
 */
@Slf4j
@Builder
public record AnalyticsSettings(boolean collectClaims, boolean collectAllClaims, Map<String, String[]> claimsAllowlist,
                                boolean collectHeaders, List<Pattern> headersBlacklist,
                                @Nullable List<Pattern> headersAllowlist) {

    private static final String ALL_CLAIMS = "*";

    public AnalyticsSettings {
        claimsAllowlist = claimsAllowlist == null ? Map.of() : claimsAllowlist;
        headersBlacklist = headersBlacklist == null ? List.of() : headersBlacklist;
    }

    /**
     * Whether the {@code claims} object is written at all — either flag alone is enough, and each contributes
     * only its own members.
     */
    public boolean claimsEnabled() {
        return collectClaims || collectAllClaims || !claimsAllowlist.isEmpty();
    }

    public static AnalyticsSettings from(JsonObject settings) {
        List<String> claimPaths = parseClaimPaths(settings.getJsonArray("claimsAllowlist"));
        return AnalyticsSettings.builder()
                .collectClaims(settings.getBoolean("collectClaims", false))
                .collectAllClaims(claimPaths.contains(ALL_CLAIMS))
                .claimsAllowlist(toClaimPathSegments(claimPaths))
                .collectHeaders(settings.getBoolean("collectHeaders", false))
                // default is defined in the bundled aidial.settings.json and always merged in
                .headersBlacklist(parseHeaderPatterns(settings.getJsonArray("headersBlacklist")))
                .headersAllowlist(parseHeaderPatterns(settings.getJsonArray("headersAllowlist")))
                .build();
    }

    private static List<String> parseClaimPaths(JsonArray value) {
        List<String> paths = new ArrayList<>();
        if (value == null) {
            return paths;
        }
        for (Object item : value) {
            if (item instanceof String s && !s.isBlank()) {
                paths.add(s.trim());
            }
        }
        return paths;
    }

    private static Map<String, String[]> toClaimPathSegments(List<String> claimPaths) {
        Map<String, String[]> allowlist = new LinkedHashMap<>();
        for (String path : claimPaths) {
            if (!ALL_CLAIMS.equals(path)) {
                allowlist.put(path, path.split("\\."));
            }
        }
        return allowlist;
    }

    private static List<Pattern> parseHeaderPatterns(JsonArray value) {
        if (value == null) {
            return null;
        }
        List<Pattern> patterns = new ArrayList<>();
        for (Object item : value) {
            if (!(item instanceof String s) || s.isBlank()) {
                continue;
            }
            try {
                patterns.add(Pattern.compile(s.trim(), Pattern.CASE_INSENSITIVE));
            } catch (PatternSyntaxException e) {
                log.warn("Ignoring invalid analytics header pattern '{}': {}", s, e.getMessage());
            }
        }
        return patterns;
    }
}
