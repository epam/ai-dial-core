package com.epam.aidial.core.server.log;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import lombok.Builder;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import javax.annotation.Nullable;

/**
 * What each analytics log entry is allowed to carry, from the static {@code analytics} settings.
 *
 * @param headersBlacklist never null; empty means block nothing.
 * @param headersAllowlist null means the allowlist is disabled, i.e. collect all non-blocked headers.
 */
@Slf4j
@Builder
public record AnalyticsSettings(boolean collectClaims, boolean collectEmail, boolean collectHeaders,
                                List<Pattern> headersBlacklist, @Nullable List<Pattern> headersAllowlist) {

    public AnalyticsSettings {
        headersBlacklist = headersBlacklist == null ? List.of() : headersBlacklist;
    }

    public static AnalyticsSettings from(JsonObject settings) {
        return AnalyticsSettings.builder()
                .collectClaims(settings.getBoolean("collectClaims", false))
                .collectEmail(settings.getBoolean("collectEmail", false))
                .collectHeaders(settings.getBoolean("collectHeaders", false))
                // default is defined in the bundled aidial.settings.json and always merged in
                .headersBlacklist(parseHeaderPatterns(settings.getJsonArray("headersBlacklist")))
                .headersAllowlist(parseHeaderPatterns(settings.getJsonArray("headersAllowlist")))
                .build();
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
