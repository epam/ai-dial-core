package com.epam.aidial.core.credentials.validation;

import lombok.experimental.UtilityClass;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Value checks for a caller-supplied {@code client_secret}.
 *
 * <p>Applied on API writes only — never to secrets obtained by dynamic client registration (the authorization
 * server picks those) nor to config-file entries, which {@code ConfigPostProcessor} would drop rather than
 * reject. A null secret means "keep the stored one" and passes.</p>
 *
 * <p>{@link #MIN_LENGTH} is far below what any real authorization server issues — RFC 6749 §10.10 requires
 * ~128 bits for credentials of this kind, and issuers land at 32-64 characters — so it only catches
 * placeholders. It also keeps the {@code client_secret_hint} from approaching the whole secret.</p>
 */
@UtilityClass
public class ClientSecretValidation {

    public static final int MIN_LENGTH = 8;

    /**
     * Checks {@code clientSecret} unless it is byte-identical to {@code storedClientSecret} — that request is
     * re-writing an entity rather than introducing a secret. Server-initiated re-puts (publication rewriting an
     * application's links, for one) hand back exactly what is stored, and must not start failing on a value that
     * was accepted before these rules existed.
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
