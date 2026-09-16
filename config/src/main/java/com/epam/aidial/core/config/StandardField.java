package com.epam.aidial.core.config;

import java.util.Arrays;
import java.util.Optional;

/**
 * Fixed vocabulary of fields a pricing decision-tree {@link Condition} can address by bare name
 * (as opposed to a {@code $}-prefixed JSON Path expression). Resolution of these fields against a
 * specific upstream response shape is a runtime concern (see the server module); this enum only
 * carries the vocabulary itself and each field's known type, which is enough to reject an ordering
 * operator against a string-typed field at config load time.
 */
public enum StandardField {

    CACHED_READ_TOKENS("cachedReadTokens", true),
    CACHED_WRITE_TOKENS("cachedWriteTokens", true),
    PROMPT_TOKENS("promptTokens", true),
    SERVICE_TIER("serviceTier", false),
    TTL("ttl", false);

    private final String fieldName;
    private final boolean numeric;

    StandardField(String fieldName, boolean numeric) {
        this.fieldName = fieldName;
        this.numeric = numeric;
    }

    public static Optional<StandardField> fromFieldName(String fieldName) {
        return Arrays.stream(values()).filter(field -> field.fieldName.equals(fieldName)).findFirst();
    }

    public boolean isNumeric() {
        return numeric;
    }

    public boolean isString() {
        return !numeric;
    }
}
