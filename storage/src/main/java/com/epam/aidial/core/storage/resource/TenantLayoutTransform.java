package com.epam.aidial.core.storage.resource;

import lombok.experimental.UtilityClass;

import javax.annotation.Nullable;

/**
 * Converts the two halves of a physical storage path between the legacy bucket-rooted layout and the
 * tenant-rooted one: the bucket location prefix and the resource-type folder. Path composition itself
 * stays in {@link ResourceDescriptor#getAbsoluteFilePath()}.
 *
 * <p>The conversion is total and reversible in both directions, so a migrated path can always be mapped
 * back to its origin.
 *
 * <p>Legacy locations are produced by the server-side bucket builder; the prefixes are repeated here
 * because this module cannot depend on it.
 */
@UtilityClass
public class TenantLayoutTransform {

    private static final String LEGACY_USERS_PREFIX = "Users/";
    private static final String LEGACY_KEYS_PREFIX = "Keys/";

    private static final String ORG_PREFIX = ".org/";
    private static final String USERS_SEGMENT = ".users/";
    private static final String KEYS_SEGMENT = ".keys/";

    /**
     * The platform scope is the root of the tenant-rooted tree, above any tenant, so it has no prefix.
     */
    private static final String PLATFORM_LOCATION = "";

    /**
     * Where the system buckets of {@link ResourceDescriptor#SYSTEM_LOCATIONS} land — at the root, above any
     * tenant, keeping the bucket name so the mapping stays reversible.
     *
     * <p>Above rather than inside a tenant because that is what preserves today's behaviour: the background
     * job scheduler scans its bucket whole, and cost stats and response mappings are keyed by globally unique
     * trace, job and response ids. Whether this state should become tenant-scoped is a real question — per
     * tenant billing would want it to be — but it is a design decision for the phase that turns multi-tenancy
     * on, not something to settle silently inside a path transform.
     */
    private static final String SYSTEM_SEGMENT = ".system/";

    private static final char TYPE_FOLDER_MARKER = '.';

    public String toTenantLocation(String legacyLocation, String tenantId) {
        if (ResourceDescriptor.PLATFORM_LOCATION.equals(legacyLocation)) {
            return PLATFORM_LOCATION;
        }

        if (ResourceDescriptor.SYSTEM_LOCATIONS.contains(legacyLocation)) {
            return SYSTEM_SEGMENT + legacyLocation;
        }

        String tenantRoot = tenantRoot(tenantId);
        if (legacyLocation.startsWith(ResourceDescriptor.PUBLIC_LOCATION)) {
            // Not only "public/" itself: the platform synthesizes sub-buckets under it, e.g. a public
            // function app's source folder is keyed as "public/deployments/<id>/". The suffix keeps its
            // legacy shape under the tenant root.
            String scope = legacyLocation.substring(ResourceDescriptor.PUBLIC_LOCATION.length());
            requirePublicScope(scope, legacyLocation);
            return tenantRoot + scope;
        }

        String userId = principalId(legacyLocation, LEGACY_USERS_PREFIX);
        if (userId != null) {
            return tenantRoot + USERS_SEGMENT + userId;
        }

        String project = principalId(legacyLocation, LEGACY_KEYS_PREFIX);
        if (project != null) {
            return tenantRoot + KEYS_SEGMENT + project;
        }

        throw new IllegalArgumentException("Unsupported legacy bucket location: " + legacyLocation);
    }

    public String toLegacyLocation(String tenantLocation, String tenantId) {
        if (PLATFORM_LOCATION.equals(tenantLocation)) {
            return ResourceDescriptor.PLATFORM_LOCATION;
        }

        if (tenantLocation.startsWith(SYSTEM_SEGMENT)) {
            String system = tenantLocation.substring(SYSTEM_SEGMENT.length());
            if (!ResourceDescriptor.SYSTEM_LOCATIONS.contains(system)) {
                throw new IllegalArgumentException("Unknown system bucket location: " + tenantLocation);
            }
            return system;
        }

        String tenantRoot = tenantRoot(tenantId);
        if (!tenantLocation.startsWith(tenantRoot)) {
            throw new IllegalArgumentException("Location does not belong to tenant " + tenantId + ": " + tenantLocation);
        }

        String scope = tenantLocation.substring(tenantRoot.length());
        if (scope.isEmpty()) {
            return ResourceDescriptor.PUBLIC_LOCATION;
        }

        String userId = principalId(scope, USERS_SEGMENT);
        if (userId != null) {
            return LEGACY_USERS_PREFIX + userId;
        }

        String project = principalId(scope, KEYS_SEGMENT);
        if (project != null) {
            return LEGACY_KEYS_PREFIX + project;
        }

        if (scope.charAt(0) != TYPE_FOLDER_MARKER) {
            requirePublicScope(scope, tenantLocation);
            return ResourceDescriptor.PUBLIC_LOCATION + scope;
        }

        throw new IllegalArgumentException("Unsupported tenant bucket location: " + tenantLocation);
    }

    /**
     * A public sub-bucket suffix must end at a path boundary, and none of its segments may start with
     * the marker character: dotted names under the tenant root are reserved for principal branches and
     * resource-type folders, so a dotted segment here would make the mapping irreversible.
     */
    private void requirePublicScope(String scope, String location) {
        if (scope.isEmpty()) {
            return;
        }

        if (!scope.endsWith(ResourceDescriptor.PATH_SEPARATOR)
                || scope.charAt(0) == TYPE_FOLDER_MARKER
                || scope.contains(ResourceDescriptor.PATH_SEPARATOR + TYPE_FOLDER_MARKER)) {
            throw new IllegalArgumentException("Unsupported public bucket location: " + location);
        }
    }

    public String toTenantTypeFolder(String legacyTypeFolder) {
        if (legacyTypeFolder.isEmpty() || legacyTypeFolder.charAt(0) == TYPE_FOLDER_MARKER) {
            throw new IllegalArgumentException("Unsupported legacy resource type folder: " + legacyTypeFolder);
        }

        return TYPE_FOLDER_MARKER + legacyTypeFolder;
    }

    public String toLegacyTypeFolder(String tenantTypeFolder) {
        if (tenantTypeFolder.length() < 2 || tenantTypeFolder.charAt(0) != TYPE_FOLDER_MARKER) {
            throw new IllegalArgumentException("Unsupported tenant resource type folder: " + tenantTypeFolder);
        }

        return tenantTypeFolder.substring(1);
    }

    private String tenantRoot(String tenantId) {
        if (tenantId.isEmpty()) {
            throw new IllegalArgumentException("Tenant id must not be empty");
        }

        return ORG_PREFIX + tenantId + ResourceDescriptor.PATH_SEPARATOR;
    }

    /**
     * Returns the principal id following the given prefix, trailing separator included, or null when the
     * location does not carry that prefix. The id may span several segments: an application's own bucket
     * is keyed by the application url.
     */
    @Nullable
    private String principalId(String location, String prefix) {
        if (!location.startsWith(prefix)) {
            return null;
        }

        String id = location.substring(prefix.length());
        return id.length() > 1 && id.endsWith(ResourceDescriptor.PATH_SEPARATOR) ? id : null;
    }
}
