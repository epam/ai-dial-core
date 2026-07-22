package com.epam.aidial.core.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalizedValueTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void deserializesPlainString() throws Exception {
        LocalizedValue value = MAPPER.readValue("\"Claude Opus\"", LocalizedValue.class);
        assertFalse(value.isMap());
        assertEquals("Claude Opus", value.getPlainValue());
    }

    @Test
    void deserializesLocaleMap() throws Exception {
        LocalizedValue value = MAPPER.readValue("{\"en\": \"Hello\", \"de\": \"Hallo\"}", LocalizedValue.class);
        assertTrue(value.isMap());
        assertEquals(Map.of("en", "Hello", "de", "Hallo"), value.getLocaleMap());
    }

    @Test
    void serializesPlainStringAsString() throws Exception {
        String json = MAPPER.writeValueAsString(LocalizedValue.of("Hello"));
        assertEquals("\"Hello\"", json);
    }

    @Test
    void serializesSingleEntryMapAsString() throws Exception {
        String json = MAPPER.writeValueAsString(LocalizedValue.of(Map.of("en", "Hello")));
        assertEquals("\"Hello\"", json);
    }

    @Test
    void serializesMultiEntryMapAsObject() throws Exception {
        String json = MAPPER.writeValueAsString(LocalizedValue.of(Map.of("en", "Hello", "de", "Hallo")));
        LocalizedValue roundTripped = MAPPER.readValue(json, LocalizedValue.class);
        assertTrue(roundTripped.isMap());
        assertEquals(Map.of("en", "Hello", "de", "Hallo"), roundTripped.getLocaleMap());
    }

    @Test
    void normalizeCollapsesSingleDefaultLocaleMapToPlainString() {
        LocalizedValue value = LocalizedValue.of(Map.of("en", "Hello"));
        LocalizedValue normalized = value.normalize("en");
        assertFalse(normalized.isMap());
        assertEquals("Hello", normalized.getPlainValue());
    }

    @Test
    void normalizeLeavesMultiEntryMapUnchanged() {
        LocalizedValue value = LocalizedValue.of(Map.of("en", "Hello", "de", "Hallo"));
        LocalizedValue normalized = value.normalize("en");
        assertTrue(normalized.isMap());
    }

    @Test
    void resolveFallsBackToDefaultLocaleThenFirstAvailable() {
        LocalizedValue value = LocalizedValue.of(Map.of("de", "Hallo"));
        assertEquals("Hallo", value.resolve("fr", "en"));
    }

    @Test
    void ofNullReturnsNull() {
        assertNull(LocalizedValue.of((String) null));
        assertNull(LocalizedValue.of((Map<String, String>) null));
    }
}
