package com.epam.aidial.core.storage.resource;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class LegacyStorageLayoutTest {

    private final StorageLayout layout = LegacyStorageLayout.INSTANCE;

    @Test
    public void testLocationPrefixKeptVerbatim() {
        assertEquals("Users/u1/", layout.resolveLocationPrefix("Users/u1/"));
        assertEquals("public/", layout.resolveLocationPrefix("public/"));
        assertEquals("platform/", layout.resolveLocationPrefix("platform/"));
        assertEquals("Keys/proj/", layout.resolveLocationPrefix("Keys/proj/"));
    }

    @Test
    public void testTypeFolderKeptVerbatim() {
        for (ResourceTypes type : ResourceTypes.values()) {
            assertEquals(type.group(), layout.resolveTypeFolder(type.group()));
        }
    }
}
