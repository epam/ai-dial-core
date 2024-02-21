package com.epam.aidial.core.controller;

import com.epam.aidial.core.Proxy;
import com.epam.aidial.core.ProxyContext;
import com.epam.aidial.core.data.ResourceType;
import com.epam.aidial.core.storage.ResourceDescription;
import com.epam.aidial.core.util.HttpStatus;
import com.epam.aidial.core.util.UrlUtil;
import io.vertx.core.Future;
import lombok.AllArgsConstructor;

@AllArgsConstructor
public abstract class AccessControlBaseController {

    private static final String DEFAULT_RESOURCE_ERROR_MESSAGE = "Invalid resource url provided %s";

    final Proxy proxy;
    final ProxyContext context;
    final boolean checkFullAccess;

    /**
     * @param bucket url encoded bucket name
     * @param path   url encoded resource path
     */
    public Future<?> handle(String resourceType, String bucket, String path) {
        ResourceType type = ResourceType.of(resourceType);
        String urlDecodedBucket = UrlUtil.decodePath(bucket);
        String decryptedBucket = proxy.getEncryptionService().decrypt(urlDecodedBucket);
        if (decryptedBucket == null) {
            context.respond(HttpStatus.FORBIDDEN, "You don't have an access to the %s %s/%s".formatted(type, bucket, path));
            return Future.succeededFuture();
        }

        ResourceDescription resource;
        try {
            resource = ResourceDescription.fromEncoded(type, urlDecodedBucket, decryptedBucket, path);
        } catch (Exception ex) {
            String errorMessage = ex.getMessage() != null ? ex.getMessage() : DEFAULT_RESOURCE_ERROR_MESSAGE.formatted(path);
            context.respond(HttpStatus.BAD_REQUEST, errorMessage);
            return Future.succeededFuture();
        }

        return proxy.getVertx()
                .executeBlocking(() -> {
                    boolean hasWriteAccess = proxy.getAccessService().hasWriteAccess(path, decryptedBucket, context);
                    if (hasWriteAccess) {
                        return true;
                    }

                    if (!checkFullAccess) {
                        // some per-request API-keys may have access to the resources implicitly
                        boolean isAutoShared = context.getApiKeyData().getAttachedFiles().contains(resource.getUrl());
                        if (isAutoShared) {
                            return true;
                        }

                        return proxy.getAccessService().isSharedResource(resource, context);
                    }

                    return false;
                })
                .map(hasAccess  -> {
                    if (hasAccess) {
                        handle(resource);
                    } else {
                        context.respond(HttpStatus.FORBIDDEN, "You don't have an access to the %s %s/%s".formatted(type, bucket, path));
                    }
                    return null;
                });
    }

    protected abstract Future<?> handle(ResourceDescription resource);

}
