package com.epam.aidial.core.server.config;

import com.epam.aidial.core.metaschemas.CatalogMetaSchemaHolder;
import com.epam.aidial.core.metaschemas.MetaSchemaHolder;
import com.epam.aidial.core.storage.resource.ResourceTypes;
import com.fasterxml.jackson.databind.JsonNode;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.regex.Pattern;

/**
 * Mints a canonical blob name for a file-sourced app-type/catalog schema being migrated into the
 * {@code platform} bucket. Canonical id for schemas is decoupled from {@code $id}, so migration is
 * free to invent any valid, unique, deterministic name — deterministic so a dry-run preview matches
 * the real run and re-runs don't mint a second name for the same schema. Idempotency itself does not
 * depend on this name being reproduced: the caller decides "already migrated" via the {@code $id}
 * alias index, not by recomputing this name.
 */
public final class SchemaMigrationNaming {

    private static final int MAX_SLUG_LENGTH = 40;
    private static final Pattern DISALLOWED_CHARS = Pattern.compile("[^a-z0-9._-]+");
    private static final Pattern REPEATED_DASHES = Pattern.compile("-{2,}");

    private SchemaMigrationNaming() {
    }

    public static String mintName(ResourceTypes type, JsonNode body) {
        String displayNameField = switch (type) {
            case APP_TYPE_SCHEMA -> MetaSchemaHolder.APPLICATION_TYPE_DISPLAY_NAME;
            case CATALOG_SCHEMA -> CatalogMetaSchemaHolder.CATALOG_DISPLAY_NAME;
            default -> throw new IllegalArgumentException("Not a schema resource type: " + type);
        };
        String id = body.path("$id").asText(null);
        String displayName = body.path(displayNameField).asText(null);
        // The full digest (not a truncated prefix) is used so two different $id values cannot
        // collide on name — a truncated hash would only be probabilistically unique.
        String hash = sha256Hex(id);
        String slug = sanitize(displayName);
        return slug.isEmpty() ? hash : slug + "-" + hash;
    }

    private static String sanitize(String displayName) {
        if (displayName == null) {
            return "";
        }
        String lower = displayName.toLowerCase();
        String replaced = DISALLOWED_CHARS.matcher(lower).replaceAll("-");
        String collapsed = REPEATED_DASHES.matcher(replaced).replaceAll("-");
        // Without this, a display name that starts/ends with a disallowed character (e.g. a leading
        // space) leaves a leading/trailing '-' from the replacement above, producing an ugly
        // double-dash once the hash suffix is appended (e.g. "-foo-" + "-" + hash).
        String trimmed = collapsed.replaceAll("^-+|-+$", "");
        return trimmed.length() > MAX_SLUG_LENGTH ? trimmed.substring(0, MAX_SLUG_LENGTH) : trimmed;
    }

    private static String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest((value == null ? "" : value).getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
