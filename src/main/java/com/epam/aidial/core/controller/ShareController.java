package com.epam.aidial.core.controller;

import com.epam.aidial.core.Proxy;
import com.epam.aidial.core.ProxyContext;
import com.epam.aidial.core.data.ListSharedResourcesRequest;
import com.epam.aidial.core.data.ResourceLinkCollection;
import com.epam.aidial.core.data.ShareResourcesRequest;
import com.epam.aidial.core.storage.BlobStorageUtil;
import com.epam.aidial.core.util.HttpException;
import com.epam.aidial.core.util.HttpStatus;
import com.epam.aidial.core.util.ProxyUtil;
import io.vertx.core.Future;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.nio.charset.StandardCharsets;

@AllArgsConstructor
@Slf4j
public class ShareController {

    private static final String LIST_SHARED_BY_ME_RESOURCES = "others";

    final Proxy proxy;
    final ProxyContext context;

    public Future<?> handle(Operation operation) {
        log.info("Received share operation: " + operation);
        switch (operation) {
            case LIST -> listSharedResources();
            case CREATE -> createSharedResources();
            case REVOKE -> revokeSharedResources();
            case DISCARD -> discardSharedResources();
            default -> context.respond(HttpStatus.INTERNAL_SERVER_ERROR, "Operation %s is not supported".formatted(operation));
        }
        return Future.succeededFuture();
    }

    public Future<?> listSharedResources() {
        return context.getRequest().body().compose(buffer -> {
            ListSharedResourcesRequest request;
            try {
                String body = buffer.toString(StandardCharsets.UTF_8);
                request = ProxyUtil.convertToObject(body, ListSharedResourcesRequest.class);
            } catch (Exception e) {
                throw new HttpException(HttpStatus.BAD_REQUEST, e.getMessage());
            }

            String bucketLocation = BlobStorageUtil.buildInitiatorBucket(context);
            String bucket = proxy.getEncryptionService().encrypt(bucketLocation);
            String with = request.getWith();

            return proxy.getVertx().executeBlocking(() -> {
                if (LIST_SHARED_BY_ME_RESOURCES.equals(with)) {
                    return proxy.getShareService().listSharedByMe(bucket, bucketLocation, request);
                } else {
                    return proxy.getShareService().listSharedWithMe(bucket, bucketLocation, request);
                }
            });
        })
        .onSuccess(response -> context.respond(HttpStatus.OK, response))
        .onFailure(error -> {
            if (error instanceof HttpException httpException) {
                context.respond(httpException.getStatus(), httpException.getMessage());
            } else {
                context.respond(HttpStatus.INTERNAL_SERVER_ERROR, error.getMessage());
            }
        });
    }

    public Future<?> createSharedResources() {
        return context.getRequest().body().compose(buffer -> {
            ShareResourcesRequest request;
            try {
                String body = buffer.toString(StandardCharsets.UTF_8);
                request = ProxyUtil.convertToObject(body, ShareResourcesRequest.class);
                log.info("Received body: {}", request);
            } catch (Exception e) {
                throw new HttpException(HttpStatus.BAD_REQUEST, e.getMessage());
            }

            String bucketLocation = BlobStorageUtil.buildInitiatorBucket(context);
            String bucket = proxy.getEncryptionService().encrypt(bucketLocation);
            return proxy.getVertx()
                    .executeBlocking(() -> proxy.getShareService().initializeShare(bucket, bucketLocation, request))
                    .onSuccess(response -> {
                        log.info("Sending response body back: {}", response);
                        context.respond(HttpStatus.OK, response);
                    })
                    .onFailure(error -> {
                        if (error instanceof HttpException httpException) {
                            context.respond(httpException.getStatus(), httpException.getMessage());
                        } else {
                            context.respond(HttpStatus.INTERNAL_SERVER_ERROR, error.getMessage());
                        }
                    });

        });
    }

    public Future<?> discardSharedResources() {
        return context.getRequest().body().compose(buffer -> {
            ResourceLinkCollection request;
            try {
                String body = buffer.toString(StandardCharsets.UTF_8);
                request = ProxyUtil.convertToObject(body, ResourceLinkCollection.class);
            } catch (Exception e) {
                throw new HttpException(HttpStatus.BAD_REQUEST, e.getMessage());
            }

            String bucketLocation = BlobStorageUtil.buildInitiatorBucket(context);
            String bucket = proxy.getEncryptionService().encrypt(bucketLocation);
            return proxy.getVertx()
                    .executeBlocking(() -> {
                        proxy.getShareService().discardSharedAccess(bucket, bucketLocation, request);
                        return null;
                    })
                    .onSuccess(response -> context.respond(HttpStatus.OK))
                    .onFailure(error -> {
                        if (error instanceof HttpException httpException) {
                            context.respond(httpException.getStatus(), httpException.getMessage());
                        } else {
                            context.respond(HttpStatus.INTERNAL_SERVER_ERROR, error.getMessage());
                        }
                    });
        });
    }

    public Future<?> revokeSharedResources() {
        return context.getRequest().body().compose(buffer -> {
            ResourceLinkCollection request;
            try {
                String body = buffer.toString(StandardCharsets.UTF_8);
                request = ProxyUtil.convertToObject(body, ResourceLinkCollection.class);
            } catch (Exception e) {
                throw new HttpException(HttpStatus.BAD_REQUEST, e.getMessage());
            }

            String bucketLocation = BlobStorageUtil.buildInitiatorBucket(context);
            String bucket = proxy.getEncryptionService().encrypt(bucketLocation);
            return proxy.getVertx()
                    .executeBlocking(() -> {
                        proxy.getShareService().revokeSharedAccess(bucket, bucketLocation, request);
                        return null;
                    })
                    .onSuccess(response -> context.respond(HttpStatus.OK))
                    .onFailure(error -> {
                        if (error instanceof HttpException httpException) {
                            context.respond(httpException.getStatus(), httpException.getMessage());
                        } else {
                            context.respond(HttpStatus.INTERNAL_SERVER_ERROR, error.getMessage());
                        }
                    });
        });
    }

    public enum Operation {
        CREATE, LIST, DISCARD, REVOKE
    }
}
