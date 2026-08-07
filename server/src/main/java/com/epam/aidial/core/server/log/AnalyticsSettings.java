package com.epam.aidial.core.server.log;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import javax.annotation.Nullable;

/**
 * What each analytics log entry is allowed to carry, from the static {@code analytics} settings.
 *
 * @param collectAllClaims true when the claim allowlist contains {@value #ALL_CLAIMS}, i.e. every claim is collected.
 * @param claimsAllowlist  the configured claim paths; never null, empty means collect none. Ordered and deduplicated,
 *                         so log entries list claims once, in the order the operator declared them.
 * @param headersBlacklist never null; empty means block nothing.
 * @param headersAllowlist null means the allowlist is disabled, i.e. collect all non-blocked headers.
 */
@Slf4j
public record AnalyticsSettings(boolean collectClaims, boolean collectAllClaims, List<ClaimPath> claimsAllowlist,
                                boolean collectHeaders, List<Pattern> headersBlacklist,
                                @Nullable List<Pattern> headersAllowlist) {

    private static final String ALL_CLAIMS = "*";

    /**
     * A configured claim path: the {@code name} as the operator wrote it — and as it is emitted — together with the
     * {@code segments} used to walk the claim payload.
     */
    public record ClaimPath(String name, List<String> segments) {
    }

    public AnalyticsSettings {
        claimsAllowlist = claimsAllowlist == null ? List.of() : List.copyOf(claimsAllowlist);
        headersBlacklist = headersBlacklist == null ? List.of() : List.copyOf(headersBlacklist);
        headersAllowlist = headersAllowlist == null ? null : List.copyOf(headersAllowlist);
    }

    /**
     * Whether the {@code claims} object is written at all — either setting alone is enough, and each contributes
     * only its own members.
     */
    public boolean claimsEnabled() {
        return collectClaims || collectAllClaims || !claimsAllowlist.isEmpty();
    }

    public static AnalyticsSettings from(JsonObject settings) {
        List<String> claimPaths = parseClaimPaths(settings.getJsonArray("claimsAllowlist"));
        return new AnalyticsSettings(
                settings.getBoolean("collectClaims", false),
                claimPaths.contains(ALL_CLAIMS),
                toClaimPaths(claimPaths),
                settings.getBoolean("collectHeaders", false),
                // default is defined in the bundled aidial.settings.json and always merged in
                parseHeaderPatterns(settings.getJsonArray("headersBlacklist")),
                parseHeaderPatterns(settings.getJsonArray("headersAllowlist")));
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

    private static List<ClaimPath> toClaimPaths(List<String> claimPaths) {
        List<ClaimPath> allowlist = new ArrayList<>();
        Set<String> names = new HashSet<>();
        for (String path : claimPaths) {
            if (ALL_CLAIMS.equals(path)) {
                continue;
            }
            // the negative limit keeps trailing empty segments, so "email." is rejected like ".email" and "."
            List<String> segments = List.of(path.split("\\.", -1));
            // a path such as "." carries no claim name at all and would resolve to the whole payload
            if (segments.stream().anyMatch(String::isEmpty)) {
                log.warn("Ignoring invalid analytics claim path '{}': expected a dot-separated claim name", path);
                continue;
            }
            if (names.add(path)) {
                allowlist.add(new ClaimPath(path, segments));
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
