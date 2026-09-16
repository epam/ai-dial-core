package com.epam.aidial.core.credentials.validation;

import lombok.experimental.UtilityClass;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Value checks for a caller-supplied {@code client_secret}, on API writes only — never on secrets picked by
 * dynamic client registration, nor on config-file entries, which {@code ConfigPostProcessor} drops rather than
 * rejects. A null secret means "keep the stored one" and passes.
 *
 * <p>{@link #MIN_LENGTH} sits far below what any real authorization server issues (RFC 6749 §10.10 asks for
 * ~128 bits; issuers land at 32-64 characters), so it only catches placeholders.</p>
 */
@UtilityClass
public class ClientSecretValidation {

    public static final int MIN_LENGTH = 8;

    /**
     * Skips the checks when the secret is byte-identical to the stored one: that request re-writes an entity
     * rather than introducing a secret. Server-initiated re-puts (publication rewriting an application's links)
     * hand back exactly what is stored, and must not start failing on a value accepted before these rules.
     */
    public static void validate(String clientSecret, String storedClientSecret) {
        if (clientSecret != null && storedClientSecret != null
                && MessageDigest.isEqual(clientSecret.getBytes(StandardCharsets.UTF_8),
                        storedClientSecret.getBytes(StandardCharsets.UTF_8))) {
            return;
        }
        validate(clientSecret);
    }

    public static void validate(String clientSecret) {
        if (clientSecret == null) {
            return;
        }
        if (clientSecret.isBlank()) {
            throw new IllegalArgumentException("Field 'CLIENT_SECRET' must not be blank");
        }
        if (clientSecret.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("Field 'CLIENT_SECRET' must not contain control characters");
        }
        if (clientSecret.length() < MIN_LENGTH) {
            throw new IllegalArgumentException(
                    "Field 'CLIENT_SECRET' must be at least %d characters long".formatted(MIN_LENGTH));
        }
    }
}
