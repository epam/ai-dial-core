package com.epam.aidial.core.server.config;

import com.epam.aidial.core.config.Config;
import com.epam.aidial.core.config.Key;
import org.apache.commons.lang3.StringUtils;

import java.util.Map;
import java.util.Objects;
import javax.annotation.Nullable;

/**
 * Pure-function {@link Key} validation shared by every path that can create/update a project key:
 * single-entity {@code PUT} ({@code ConfigResourceController}), batch apply/precheck
 * ({@code ConfigApplyService}, {@code ConfigValidationService}), and the full-rebuild loader
 * ({@code ApiKeyStore}). Each method returns an error message, or {@code null} if valid.
 *
 * <p>Not covered here: {@code ApiKeyStore}'s blank-{@code key} handling (file keys back-fill it
 * from the map key after validation), and the warn-only collision checks in {@code ApiKeyStore}'s
 * rebuild and {@code MergedConfigStore}'s replica path — both algorithmically different from
 * {@link #isKeySecretChangedAndTakenByAnotherKey} and can't reject anyway. The replica path still
 * calls that method directly for its own warn-only purposes.
 */
public final class KeyValidator {

    private KeyValidator() {
    }

    /**
     * The two checks shared with {@code ApiKeyStore}'s full-rebuild loader, which validates every
     * key before {@code Key.key} is necessarily populated (see class javadoc).
     */
    public static String validateProjectAndRoles(Key key) {
        if (StringUtils.isBlank(key.getProject())) {
            return "Project key is undefined";
        }
        if (StringUtils.isBlank(key.getRole()) && (key.getRoles() == null || key.getRoles().isEmpty())) {
            return "Invalid key: at least one role must be assigned to the key " + key.getProject();
        }
        return null;
    }

    /**
     * Every blocking write path's full required-field check: {@code Key.key} plus
     * {@link #validateProjectAndRoles}.
     */
    public static String validateRequiredFields(Key key) {
        if (StringUtils.isBlank(key.getKey())) {
            return "Key.key must be provided explicitly";
        }
        return validateProjectAndRoles(key);
    }

    /**
     * Non-null iff {@code candidate}'s secret differs from {@code oldSecret} (its prior value, or
     * {@code null} on create) and is already used by a different key entity in {@code scratch}.
     * Callers must resolve both to plaintext (or to "unchanged") before calling.
     */
    public static String validateSecretNotTaken(Config scratch, String selfMapKey, Key candidate, String oldSecret) {
        if (isKeySecretChangedAndTakenByAnotherKey(scratch, selfMapKey, candidate, oldSecret)) {
            return "Key secret is already used by a different key entity";
        }
        return null;
    }

    /**
     * Returns {@code true} if {@code candidate}'s secret differs from {@code oldSecret} (its own
     * prior value, or {@code null} on create) and that new secret is already used by a different
     * key entity. Skipping the check when the secret is unchanged avoids re-flagging a legacy
     * duplicate pair on an unrelated field update. Also called directly by
     * {@code MergedConfigStore}'s replica path (warn-only there — a replicated write can't be
     * rejected after the fact).
     */
    public static boolean isKeySecretChangedAndTakenByAnotherKey(Config config, String selfMapKey,
                                                                 Key candidate, @Nullable String oldSecret) {
        return !Objects.equals(candidate.getKey(), oldSecret)
                && isKeySecretTakenByAnotherKey(config, selfMapKey, candidate);
    }

    /**
     * Returns {@code true} if {@code candidate}'s secret is already used by a different entry of
     * the folded {@code Config.keys} map — file-sourced entries keyed by their raw secret,
     * blob-sourced entries keyed by canonical id. The entry stored under {@code selfMapKey} is
     * the candidate's own and never counts as a collision. Private — every caller goes through
     * {@link #isKeySecretChangedAndTakenByAnotherKey}, which adds the unchanged-secret skip.
     */
    private static boolean isKeySecretTakenByAnotherKey(Config config, String selfMapKey, Key candidate) {
        String secret = candidate.getKey();
        if (secret == null || secret.isBlank()) {
            return false;
        }
        return config.getKeys().entrySet().stream()
                .filter(entry -> !entry.getKey().equals(selfMapKey))
                .map(Map.Entry::getValue)
                .filter(Objects::nonNull)
                .map(Key::getKey)
                .anyMatch(secret::equals);
    }
}
