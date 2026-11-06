package com.epam.aidial.core.server.security;

import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.storage.resource.ResourceDescriptor;
import com.epam.aidial.core.storage.resource.ResourceTypes;
import lombok.RequiredArgsConstructor;

/**
 * Bucket-aware {@link ConfigAuthorizationService} over {@link AccessService}.
 * {@link Operation#isRead() Reads} on {@code public/} are open to any authenticated caller;
 * everything on {@code platform/}, writes on {@code public/} and every operation on {@code aliases} require the admin role. User
 * buckets fall through to the owner-check; admin has no access to user buckets.
 *
 * <p>See {@code docs/sandbox/dial-unified-config/04-security-and-audit.md} §1.2.
 */
@RequiredArgsConstructor
public class AdminRoleAuthorizationService implements ConfigAuthorizationService {

    private final AccessService accessService;

    @Override
    public boolean isAuthorized(ProxyContext context, String entityType, String entityName,
                                String bucket, Operation operation) {
        // an alias grants its callers its target, so even reading its definition is for admins only
        if (EntityBucketBinding.PLATFORM_BUCKET.equals(bucket) || ResourceTypes.ALIAS.group().equals(entityType)) {
            return accessService.hasExplicitAdminAccess(context);
        }
        if (ResourceDescriptor.PUBLIC_BUCKET.equals(bucket)) {
            // Reads on public/ are open to any authenticated caller — and reaching here means the
            // request is already authenticated (Proxy#authorizeRequest rejects otherwise).
            return operation.isRead() || accessService.hasExplicitAdminAccess(context);
        }
        return accessService.isOwnerOf(context, bucket);
    }

    @Override
    public boolean isAdmin(ProxyContext context) {
        return accessService.hasExplicitAdminAccess(context);
    }
}
