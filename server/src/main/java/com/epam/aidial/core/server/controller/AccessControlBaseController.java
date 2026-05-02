package com.epam.aidial.core.server.controller;

import com.epam.aidial.core.config.ResourceAccessType;
import com.epam.aidial.core.server.Proxy;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.security.AccessService;
import com.epam.aidial.core.server.security.AdminRoleAuthorizationService;
import com.epam.aidial.core.server.security.ConfigAuthorizationService;
import com.epam.aidial.core.server.security.Operation;
import com.epam.aidial.core.server.util.ResourceDescriptorFactory;
import com.epam.aidial.core.storage.http.HttpStatus;
import com.epam.aidial.core.storage.resource.ResourceDescriptor;
import io.vertx.core.Future;
import lombok.AllArgsConstructor;

import java.util.Set;

@AllArgsConstructor
public abstract class AccessControlBaseController {

    final Proxy proxy;
    final ProxyContext context;
    final boolean isWriteAccess;

    public Future<?> handle(String resourceUrl) {
        ResourceDescriptor resource;

        try {
            resource = ResourceDescriptorFactory.fromAnyUrl(resourceUrl, proxy.getEncryptionService());
        } catch (Exception e) {
            String errorMessage = e.getMessage() != null ? e.getMessage() : ("Invalid resource url provided: " + resourceUrl);
            return context.respond(HttpStatus.BAD_REQUEST, errorMessage);
        }

        // 1S.5: admin authz preflight (additive admit). When the admin role is asserted AND the
        // unified-config gate authorizes the request (public/ + admin → always; user-bucket only
        // when admin is the bucket owner), the rules-based check below is skipped and the handler
        // runs with hasWriteAccess=true. Otherwise the request falls through to the existing
        // AccessService path — preserving share-based grants (e.g. publication review). OQ-33's
        // "admin can't reach user buckets" is enforced by the gate not admitting admin onto user
        // buckets; it does not actively block existing share-based access.
        ConfigAuthorizationService configAuth = new AdminRoleAuthorizationService(proxy.getAccessService());
        if (configAuth.isAdmin(context)) {
            Operation operation = isWriteAccess ? Operation.WRITE : Operation.READ;
            if (configAuth.isAuthorized(context, resource.getType().group(),
                    resource.getName(), resource.getBucketName(), operation)) {
                return handle(resource, true);
            }
        }

        return proxy.getTaskExecutor()
                .submit(() -> {
                    AccessService service = proxy.getAccessService();
                    return service.lookupPermissions(Set.of(resource), context).get(resource);
                })
                .compose(permissions -> {
                    boolean hasAccess = permissions.contains(isWriteAccess
                            ? ResourceAccessType.WRITE : ResourceAccessType.READ);
                    if (hasAccess) {
                        return handle(resource, permissions.contains(ResourceAccessType.WRITE));
                    } else {
                        context.respond(HttpStatus.FORBIDDEN, "You don't have an access to: " + resourceUrl);
                        return Future.succeededFuture();
                    }
                });
    }

    /**
     * @return a successful future to read the request body after its completion.
     */
    protected abstract Future<?> handle(ResourceDescriptor resource, boolean hasWriteAccess);

}
