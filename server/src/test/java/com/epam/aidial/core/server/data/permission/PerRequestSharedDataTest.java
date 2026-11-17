package com.epam.aidial.core.server.data.permission;

import com.epam.aidial.core.config.ResourceAccessType;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PerRequestSharedDataTest {

    @Test
    void lentDefaultsToFalseForOneArgConstructor() {
        PerRequestSharedData data = new PerRequestSharedData(ResourceAccessType.READ_ONLY);
        assertFalse(data.lent());
    }

    @Test
    void mergeSetsLentWhenEitherSideIsLent() {
        PerRequestSharedData regular = new PerRequestSharedData(ResourceAccessType.READ_ONLY, false);
        PerRequestSharedData lented = new PerRequestSharedData(ResourceAccessType.READ_ONLY, true);

        assertTrue(regular.merge(lented).lent(), "lent flag must propagate when added is lent");
        assertTrue(lented.merge(regular).lent(), "lent flag must propagate when existing is lent");
        assertFalse(regular.merge(regular).lent(), "two non-lent entries must not become lent");
    }

    @Test
    void mergeUnionsPermissions() {
        PerRequestSharedData read = new PerRequestSharedData(Set.of(ResourceAccessType.READ), false);
        PerRequestSharedData write = new PerRequestSharedData(Set.of(ResourceAccessType.WRITE), false);
        PerRequestSharedData union = read.merge(write);
        assertEquals(Set.of(ResourceAccessType.READ, ResourceAccessType.WRITE), union.permissions());
    }

    @Test
    void mergeReturnsFreshObject() {
        PerRequestSharedData a = new PerRequestSharedData(ResourceAccessType.READ_ONLY, true);
        PerRequestSharedData b = new PerRequestSharedData(ResourceAccessType.READ_ONLY, false);
        PerRequestSharedData merged = a.merge(b);
        assertNotSame(a, merged);
        assertNotSame(b, merged);
    }

    @Test
    void serialisationRoundTripPreservesLent() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        PerRequestSharedData original = new PerRequestSharedData(ResourceAccessType.READ_ONLY, true);
        String json = mapper.writeValueAsString(original);
        PerRequestSharedData decoded = mapper.readValue(json, PerRequestSharedData.class);
        assertEquals(original.permissions(), decoded.permissions());
        assertTrue(decoded.lent());
    }

    @Test
    void legacyJsonWithoutLentFieldDeserializesAsNotLent() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        // JSON written by old code has no "lent" field
        String legacyJson = "{\"permissions\":[\"READ\"]}";
        PerRequestSharedData decoded = mapper.readValue(legacyJson, PerRequestSharedData.class);
        assertFalse(decoded.lent(), "legacy JSON must deserialise with lent=false");
        assertEquals(Set.of(ResourceAccessType.READ), decoded.permissions());
    }
}
