package com.epam.aidial.core.controller;

import com.epam.aidial.core.Proxy;
import com.epam.aidial.core.ProxyContext;
import com.epam.aidial.core.security.AccessService;
import com.epam.aidial.core.storage.ResourceDescription;
import com.epam.aidial.core.util.HttpStatus;
import io.vertx.core.Future;
import lombok.AllArgsConstructor;
import org.apache.commons.lang3.tuple.Pair;

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
                    boolean hasWriteAccess = service.hasWriteAccess(resource, context);
                    if (hasWriteAccess) {
                        // pair of writeAccess, readAccess
                        return Pair.of(true, true);
                    } else {
                        // pair of writeAccess, readAccess
                        return Pair.of(false, service.hasReadAccess(resource, context));
                    }
                }, false)
                .map(pair -> {
                    boolean hasAccess = isWriteAccess ? pair.getLeft() : pair.getRight();
                    if (hasAccess) {
                        handle(resource, pair.getLeft());
                    } else {
                        context.respond(HttpStatus.FORBIDDEN, "You don't have an access to: " + resourceUrl);
                    }
                    return null;
                });
    }

    protected abstract Future<?> handle(ResourceDescription resource, boolean hasWriteAccess);

}
