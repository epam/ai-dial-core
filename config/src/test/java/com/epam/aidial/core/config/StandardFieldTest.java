package com.epam.aidial.core.config;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StandardFieldTest {

    @Test
    void fromFieldNameResolvesKnownNames() {
        assertEquals(Optional.of(StandardField.CACHED_READ_TOKENS), StandardField.fromFieldName("cachedReadTokens"));
        assertEquals(Optional.of(StandardField.CACHED_WRITE_TOKENS), StandardField.fromFieldName("cachedWriteTokens"));
        assertEquals(Optional.of(StandardField.PROMPT_TOKENS), StandardField.fromFieldName("promptTokens"));
        assertEquals(Optional.of(StandardField.SERVICE_TIER), StandardField.fromFieldName("serviceTier"));
        assertEquals(Optional.of(StandardField.TTL), StandardField.fromFieldName("ttl"));
    }

    @Test
    void fromFieldNameMissesUnknownOrJsonPathNames() {
        assertEquals(Optional.empty(), StandardField.fromFieldName("$.usage.foo"));
        assertEquals(Optional.empty(), StandardField.fromFieldName("notAStandardField"));
    }

    @Test
    void numericFieldsAreNotString() {
        assertTrue(StandardField.CACHED_READ_TOKENS.isNumeric());
        assertTrue(StandardField.CACHED_WRITE_TOKENS.isNumeric());
        assertTrue(StandardField.PROMPT_TOKENS.isNumeric());
        assertFalse(StandardField.CACHED_READ_TOKENS.isString());
    }

    @Test
    void stringFieldsAreNotNumeric() {
        assertTrue(StandardField.SERVICE_TIER.isString());
        assertTrue(StandardField.TTL.isString());
        assertFalse(StandardField.SERVICE_TIER.isNumeric());
    }
}
