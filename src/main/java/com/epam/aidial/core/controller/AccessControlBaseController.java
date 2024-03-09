package com.epam.aidial.core.controller;

import com.epam.aidial.core.Proxy;
import com.epam.aidial.core.ProxyContext;
import com.epam.aidial.core.security.AccessService;
import com.epam.aidial.core.storage.ResourceDescription;
import com.epam.aidial.core.util.HttpStatus;
import io.vertx.core.Future;
import lombok.AllArgsConstructor;

@AllArgsConstructor
public abstract class AccessControlBaseController {

    final Proxy proxy;
    final ProxyContext context;
    final boolean checkFullAccess;

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
                    AccessService accessService = proxy.getAccessService();
                    if (accessService.hasAdminAccess(context)) {
                        return true;
                    }

                    if (resource.isPrivate() && accessService.hasWriteAccess(resource, context)) {
                        return true;
                    }

                    if (!checkFullAccess) {
                        // some per-request API-keys may have access to the resources implicitly
                        boolean isAutoShared = context.getApiKeyData().getAttachedFiles().contains(resource.getUrl());
                        if (isAutoShared) {
                            return true;
                        }

                        if (accessService.hasReviewAccess(resource, context)) {
                            return true;
                        }

                        if (accessService.hasPublicAccess(resource, context)) {
                            return true;
                        }

                        return resource.isPrivate() && accessService.isSharedResource(resource, context);
                    }

                    return false;
                })
                .map(hasAccess -> {
                    if (hasAccess) {
                        handle(resource);
                    } else {
                        context.respond(HttpStatus.FORBIDDEN, "You don't have an access to: " + resourceUrl);
                    }
                    return null;
                });
    }

    protected abstract Future<?> handle(ResourceDescription resource);

}
