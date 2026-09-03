package com.epam.aidial.core.storage.resource;

/**
 * The tenant-rooted layout: every bucket location is placed under its tenant, and resource-type folders
 * are reserved names. Conversion rules live in {@link TenantLayoutTransform}.
 */
public final class TenantRootedStorageLayout implements StorageLayout {

    private final String tenantId;

    public TenantRootedStorageLayout(String tenantId) {
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalArgumentException("Tenant id must not be blank");
        }

        // Same rule the transform applies on every composition, but failing here fails at start-up.
        TenantLayoutTransform.requireTenantId(tenantId);
        this.tenantId = tenantId;
    }

    @Override
    public String resolveLocationPrefix(String bucketLocation) {
        return TenantLayoutTransform.toTenantLocation(bucketLocation, tenantId);
    }

    @Override
    public String resolveTypeFolder(String typeGroup) {
        return TenantLayoutTransform.toTenantTypeFolder(typeGroup);
    }
}
