package com.epam.aidial.core.server.util;

/**
 * Shared helper for the canonical-id ↔ short-name relationship used across config loading
 * ({@code ConfigPostProcessor}, {@code MergedConfigStore}), consent records ({@code ConsentService}),
 * and analytics logging ({@code AnalyticsLogContext}). Applies only to {@code platform}-bucket
 * canonical ids, which are always exactly {@code type/platform/name} with no further nesting
 * (enforced at the write boundary — see {@code ConfigResourceController.ENTITY_NAME_PATTERN}), so
 * the last path segment is unambiguously the short name. A bare (slash-free) name is already its
 * own short name.
 */
public final class PlatformCanonicalIdUtil {

    private PlatformCanonicalIdUtil() {
    }

    public static String lastSegment(String key) {
        int slash = key.lastIndexOf('/');
        return slash < 0 ? key : key.substring(slash + 1);
    }
}
