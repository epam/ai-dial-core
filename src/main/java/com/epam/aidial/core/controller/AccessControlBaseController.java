package com.epam.aidial.core.controller;

import com.epam.aidial.core.Proxy;
import com.epam.aidial.core.ProxyContext;
import com.epam.aidial.core.data.ResourceAccessType;
import com.epam.aidial.core.security.AccessService;
import com.epam.aidial.core.storage.ResourceDescription;
import com.epam.aidial.core.util.HttpStatus;
import io.vertx.core.Future;
import lombok.AllArgsConstructor;

import java.util.Set;

@AllArgsConstructor
public abstract class AccessControlBaseController {

    final Proxy proxy;
    final ProxyContext context;
    final boolean isWriteAccess;

    public Future<?> handle(String resourceUrl) {
        ResourceDescription resource;

        try {
            resource = ResourceDescription.fromAnyUrl(resourceUrl, proxy.getEncryptionService());
        } catch (IllegalArgumentException e) {
            String errorMessage = e.getMessage() != null ? e.getMessage() : ("Invalid resource url provided: " + resourceUrl);
            context.respond(HttpStatus.BAD_REQUEST, errorMessage);
            return Future.succeededFuture();
        }

        return proxy.getVertx()
                .executeBlocking(() -> {
                    AccessService service = proxy.getAccessService();
                    return service.lookupPermissions(Set.of(resource), context).get(resource);
                }, false)
                .compose(permissions -> {
                    boolean hasAccess = permissions.contains(isWriteAccess
                            ? ResourceAccessType.WRITE : ResourceAccessType.READ);
                    if (hasAccess) {
                        return handle(resource, permissions.contains(ResourceAccessType.WRITE));
                    }

                    context.respond(HttpStatus.FORBIDDEN, "You don't have an access to: " + resourceUrl);
                    return Future.succeededFuture();
                });
    }

    protected abstract Future<?> handle(ResourceDescription resource, boolean hasWriteAccess);

}
