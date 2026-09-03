package com.epam.aidial.core.storage.resource;

import lombok.experimental.UtilityClass;

import java.util.Set;
import javax.annotation.Nullable;

/**
 * Converts the two halves of a physical storage path between the legacy bucket-rooted layout and the
 * tenant-rooted one: the bucket location prefix and the resource-type folder. Path composition itself
 * stays in {@link ResourceDescriptor#getAbsoluteFilePath()}.
 *
 * <p>The conversion is total and reversible in both directions, so a migrated path can always be mapped
 * back to its origin. Nothing on the request path converts backwards — the legacy direction exists for
 * migration tooling and the layout verifier.
 */
@UtilityClass
public class TenantLayoutTransform {

    private static final String ORG_PREFIX = ".org/";
    private static final String USERS_SEGMENT = ".users/";
    private static final String KEYS_SEGMENT = ".keys/";

    /**
     * The platform scope maps to the root of the tenant-rooted tree, above any tenant, so it has no prefix.
     */
    private static final String TENANT_TREE_ROOT = "";

    /**
     * Where the system buckets of {@link ResourceDescriptor#SYSTEM_LOCATIONS} land — at the root, above any
     * tenant, which preserves whole-bucket scans and globally unique keys; the bucket name is kept so the
     * mapping stays reversible.
     */
    private static final String SYSTEM_SEGMENT = ".system/";

    private static final char TYPE_FOLDER_MARKER = '.';

    /**
     * The dotted names with a structural meaning in the tenant-rooted tree. A resource type whose group
     * produced one of these as its type folder would make full paths unparseable — nothing distinguishes
     * the public {@code .users} type folder from the {@code .users} principal branch — so such a group is
     * rejected outright. Nothing else keeps a future {@code ResourceTypes} entry off these names.
     */
    private static final Set<String> RESERVED_SEGMENTS = Set.of(ORG_PREFIX, USERS_SEGMENT, KEYS_SEGMENT, SYSTEM_SEGMENT);

    /**
     * A location that is not the platform, a system bucket, public or a principal is rejected rather than
     * passed through: a silent fallback would let the next unmapped bucket reach production unnoticed.
     */
    public String toTenantLocation(String legacyLocation, String tenantId) {
        if (ResourceDescriptor.PLATFORM_LOCATION.equals(legacyLocation)) {
            return TENANT_TREE_ROOT;
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

        String userId = principalId(legacyLocation, ResourceDescriptor.USERS_LOCATION_PREFIX);
        if (userId != null) {
            return tenantRoot + USERS_SEGMENT + userId;
        }

        String project = principalId(legacyLocation, ResourceDescriptor.KEYS_LOCATION_PREFIX);
        if (project != null) {
            return tenantRoot + KEYS_SEGMENT + project;
        }

        throw new IllegalArgumentException("Unsupported legacy bucket location: " + legacyLocation);
    }

    public String toLegacyLocation(String tenantLocation, String tenantId) {
        if (TENANT_TREE_ROOT.equals(tenantLocation)) {
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
            return ResourceDescriptor.USERS_LOCATION_PREFIX + userId;
        }

        String project = principalId(scope, KEYS_SEGMENT);
        if (project != null) {
            return ResourceDescriptor.KEYS_LOCATION_PREFIX + project;
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

        if (!scope.endsWith(ResourceDescriptor.PATH_SEPARATOR) || hasDottedSegment(scope)) {
            throw new IllegalArgumentException("Unsupported public bucket location: " + location);
        }
    }

    public String toTenantTypeFolder(String legacyTypeFolder) {
        if (legacyTypeFolder.isEmpty() || legacyTypeFolder.charAt(0) == TYPE_FOLDER_MARKER) {
            throw new IllegalArgumentException("Unsupported legacy resource type folder: " + legacyTypeFolder);
        }

        String folder = TYPE_FOLDER_MARKER + legacyTypeFolder;
        requireUnreserved(folder, legacyTypeFolder);
        return folder;
    }

    public String toLegacyTypeFolder(String tenantTypeFolder) {
        if (tenantTypeFolder.length() < 2 || tenantTypeFolder.charAt(0) != TYPE_FOLDER_MARKER) {
            throw new IllegalArgumentException("Unsupported tenant resource type folder: " + tenantTypeFolder);
        }

        requireUnreserved(tenantTypeFolder, tenantTypeFolder);
        return tenantTypeFolder.substring(1);
    }

    private void requireUnreserved(String dottedFolder, String input) {
        if (RESERVED_SEGMENTS.contains(dottedFolder + ResourceDescriptor.PATH_SEPARATOR)) {
            throw new IllegalArgumentException("Resource type folder collides with a reserved name: " + input);
        }
    }

    /**
     * A tenant id is a single undotted path segment: a separator would let one tenant's root nest inside
     * another's, and a leading marker would collide with the reserved dotted names.
     */
    void requireTenantId(String tenantId) {
        if (tenantId.isEmpty()
                || tenantId.contains(ResourceDescriptor.PATH_SEPARATOR)
                || tenantId.charAt(0) == TYPE_FOLDER_MARKER) {
            throw new IllegalArgumentException("Unsupported tenant id: " + tenantId);
        }
    }

    private String tenantRoot(String tenantId) {
        requireTenantId(tenantId);
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
        if (id.length() <= 1 || !id.endsWith(ResourceDescriptor.PATH_SEPARATOR)) {
            return null;
        }

        // A dotted segment would escape the tree ("..") or collide with the dotted names reserved for
        // type folders and principal branches; no legitimate producer emits one.
        if (hasDottedSegment(id)) {
            throw new IllegalArgumentException("Unsupported principal id in location: " + location);
        }
        return id;
    }

    private boolean hasDottedSegment(String path) {
        return path.charAt(0) == TYPE_FOLDER_MARKER
                || path.contains(ResourceDescriptor.PATH_SEPARATOR + TYPE_FOLDER_MARKER);
    }
}
