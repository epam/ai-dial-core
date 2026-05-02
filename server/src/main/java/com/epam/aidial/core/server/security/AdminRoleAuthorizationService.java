package com.epam.aidial.core.server.security;

import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.storage.resource.ResourceDescriptor;
import lombok.RequiredArgsConstructor;

/**
 * Bucket-aware {@link ConfigAuthorizationService} over {@link AccessService}.
 * {@link Operation#isRead() Reads} on {@code public/} are open to any authenticated caller;
 * everything on {@code platform/} and writes on {@code public/} require the admin role. User
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
        if (EntityBucketBinding.PLATFORM_BUCKET.equals(bucket)) {
            return accessService.hasAdminAccess(context);
        }
        if (ResourceDescriptor.PUBLIC_BUCKET.equals(bucket)) {
            return operation.isRead()
                    ? accessService.isAuthenticated(context)
                    : accessService.hasAdminAccess(context);
        }
        return accessService.isOwnerOf(context, bucket);
    }

    @Override
    public boolean isAdmin(ProxyContext context) {
        return accessService.hasAdminAccess(context);
    }
}
