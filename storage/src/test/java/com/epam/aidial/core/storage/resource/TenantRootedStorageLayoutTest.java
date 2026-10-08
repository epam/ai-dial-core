package com.epam.aidial.core.storage.resource;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class TenantRootedStorageLayoutTest {

    private final StorageLayout layout = new TenantRootedStorageLayout("acme");

    @Test
    public void testLocationPrefixIsTenantRooted() {
        assertEquals(".org/acme/.users/u1/", layout.resolveLocationPrefix("Users/u1/"));
        assertEquals(".org/acme/.keys/proj/", layout.resolveLocationPrefix("Keys/proj/"));
        assertEquals(".org/acme/", layout.resolveLocationPrefix("public/"));
        assertEquals("", layout.resolveLocationPrefix("platform/"));
    }

    @Test
    public void testTypeFolderIsReserved() {
        assertEquals(".files", layout.resolveTypeFolder("files"));
        assertEquals(".conversations", layout.resolveTypeFolder("conversations"));
    }

    @Test
    public void testUnsupportedLocationRejected() {
        assertThrows(IllegalArgumentException.class, () -> layout.resolveLocationPrefix("Unknown/u1/"));
    }

    @Test
    public void testBlankTenantRejected() {
        assertThrows(IllegalArgumentException.class, () -> new TenantRootedStorageLayout(null));
        assertThrows(IllegalArgumentException.class, () -> new TenantRootedStorageLayout(" "));
    }
}
