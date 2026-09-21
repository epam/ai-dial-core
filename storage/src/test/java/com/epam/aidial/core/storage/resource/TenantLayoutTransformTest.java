package com.epam.aidial.core.storage.resource;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class TenantLayoutTransformTest {

    private static final String TENANT = "default-tenant";

    @Test
    public void testPlatformLocation() {
        assertEquals("", TenantLayoutTransform.toTenantLocation("platform/", TENANT));
        assertEquals("platform/", TenantLayoutTransform.toLegacyLocation("", TENANT));
    }

    @Test
    public void testPublicLocation() {
        assertEquals(".org/default-tenant/", TenantLayoutTransform.toTenantLocation("public/", TENANT));
        assertEquals("public/", TenantLayoutTransform.toLegacyLocation(".org/default-tenant/", TENANT));
    }

    @Test
    public void testUserLocation() {
        assertEquals(".org/default-tenant/.users/u1/", TenantLayoutTransform.toTenantLocation("Users/u1/", TENANT));
        assertEquals("Users/u1/", TenantLayoutTransform.toLegacyLocation(".org/default-tenant/.users/u1/", TENANT));
    }

    @Test
    public void testKeyLocation() {
        assertEquals(".org/default-tenant/.keys/EPM-RTC-GPT/", TenantLayoutTransform.toTenantLocation("Keys/EPM-RTC-GPT/", TENANT));
        assertEquals("Keys/EPM-RTC-GPT/", TenantLayoutTransform.toLegacyLocation(".org/default-tenant/.keys/EPM-RTC-GPT/", TENANT));
    }

    @Test
    public void testMultiSegmentKeyLocation() {
        String legacy = "Keys/applications/abc123/my-app/";
        String tenant = ".org/default-tenant/.keys/applications/abc123/my-app/";

        assertEquals(tenant, TenantLayoutTransform.toTenantLocation(legacy, TENANT));
        assertEquals(legacy, TenantLayoutTransform.toLegacyLocation(tenant, TENANT));
    }

    @Test
    public void testLocationRoundTrip() {
        for (String legacy : new String[] {"platform/", "public/", "Users/u1/", "Keys/proj/", "Keys/applications/abc/app/"}) {
            String tenant = TenantLayoutTransform.toTenantLocation(legacy, TENANT);
            assertEquals(legacy, TenantLayoutTransform.toLegacyLocation(tenant, TENANT));
        }
    }

    @Test
    public void testUnsupportedLegacyLocation() {
        assertThrows(IllegalArgumentException.class, () -> TenantLayoutTransform.toTenantLocation("Unknown/u1/", TENANT));
        assertThrows(IllegalArgumentException.class, () -> TenantLayoutTransform.toTenantLocation("", TENANT));
        assertThrows(IllegalArgumentException.class, () -> TenantLayoutTransform.toTenantLocation("Users/", TENANT));
        assertThrows(IllegalArgumentException.class, () -> TenantLayoutTransform.toTenantLocation("Users/u1", TENANT));
    }

    @Test
    public void testUnsupportedTenantLocation() {
        assertThrows(IllegalArgumentException.class, () -> TenantLayoutTransform.toLegacyLocation(".org/default-tenant/.other/u1/", TENANT));
        assertThrows(IllegalArgumentException.class, () -> TenantLayoutTransform.toLegacyLocation(".org/default-tenant/.users/", TENANT));
    }

    @Test
    public void testForeignTenantRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> TenantLayoutTransform.toLegacyLocation(".org/other-tenant/.users/u1/", TENANT));
    }

    @Test
    public void testEmptyTenantRejected() {
        assertThrows(IllegalArgumentException.class, () -> TenantLayoutTransform.toTenantLocation("public/", ""));
        assertThrows(IllegalArgumentException.class, () -> TenantLayoutTransform.toLegacyLocation(".org//", ""));
    }

    @Test
    public void testTypeFolder() {
        assertEquals(".files", TenantLayoutTransform.toTenantTypeFolder("files"));
        assertEquals("files", TenantLayoutTransform.toLegacyTypeFolder(".files"));
    }

    @Test
    public void testTypeFolderRoundTripsForEveryResourceType() {
        for (ResourceTypes type : ResourceTypes.values()) {
            String tenantFolder = TenantLayoutTransform.toTenantTypeFolder(type.group());
            assertEquals(type.group(), TenantLayoutTransform.toLegacyTypeFolder(tenantFolder));
        }
    }

    @Test
    public void testUnsupportedTypeFolder() {
        assertThrows(IllegalArgumentException.class, () -> TenantLayoutTransform.toTenantTypeFolder(""));
        assertThrows(IllegalArgumentException.class, () -> TenantLayoutTransform.toTenantTypeFolder(".files"));
        assertThrows(IllegalArgumentException.class, () -> TenantLayoutTransform.toLegacyTypeFolder("files"));
        assertThrows(IllegalArgumentException.class, () -> TenantLayoutTransform.toLegacyTypeFolder("."));
    }
}
