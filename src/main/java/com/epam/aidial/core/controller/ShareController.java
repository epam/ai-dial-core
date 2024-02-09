package com.epam.aidial.core.controller;

import com.epam.aidial.core.Proxy;
import com.epam.aidial.core.ProxyContext;
import com.epam.aidial.core.data.ListSharedResourcesRequest;
import com.epam.aidial.core.data.ResourceLinkCollection;
import com.epam.aidial.core.data.ShareResourcesRequest;
import com.epam.aidial.core.security.EncryptionService;
import com.epam.aidial.core.service.ShareService;
import com.epam.aidial.core.storage.BlobStorageUtil;
import com.epam.aidial.core.util.HttpException;
import com.epam.aidial.core.util.HttpStatus;
import com.epam.aidial.core.util.ProxyUtil;
import io.vertx.core.Future;
import io.vertx.core.buffer.Buffer;
import lombok.extern.slf4j.Slf4j;

import java.nio.charset.StandardCharsets;

@Slf4j
public class ShareController {

    private static final String LIST_SHARED_BY_ME_RESOURCES = "others";

    final Proxy proxy;
    final ProxyContext context;
    final ShareService shareService;
    final EncryptionService encryptionService;

    public ShareController(Proxy proxy, ProxyContext context) {
        this.proxy = proxy;
        this.context = context;
        this.shareService = proxy.getShareService();
        this.encryptionService = proxy.getEncryptionService();
    }


    public Future<?> handle(Operation operation) {
        switch (operation) {
            case LIST -> listSharedResources();
            case CREATE -> createSharedResources();
            case REVOKE -> revokeSharedResources();
            case DISCARD -> discardSharedResources();
            default ->
                    context.respond(HttpStatus.INTERNAL_SERVER_ERROR, "Operation %s is not supported".formatted(operation));
        }
        return Future.succeededFuture();
    }

    public Future<?> listSharedResources() {
        return context.getRequest()
                .body()
                .compose(buffer -> {
                    ListSharedResourcesRequest request;
                    try {
                        String body = buffer.toString(StandardCharsets.UTF_8);
                        request = ProxyUtil.convertToObject(body, ListSharedResourcesRequest.class);
                    } catch (Exception e) {
                        log.error("Invalid request body provided", e);
                        throw new IllegalArgumentException("Can't list shared resources. Incorrect body");
                    }

                    String bucketLocation = BlobStorageUtil.buildInitiatorBucket(context);
                    String bucket = encryptionService.encrypt(bucketLocation);
                    String with = request.getWith();

                    return proxy.getVertx().executeBlocking(() -> {
                        if (LIST_SHARED_BY_ME_RESOURCES.equals(with)) {
                            return shareService.listSharedByMe(bucket, bucketLocation, request);
                        } else {
                            return shareService.listSharedWithMe(bucket, bucketLocation, request);
                        }
                    });
                })
                .onSuccess(response -> context.respond(HttpStatus.OK, response))
                .onFailure(this::handleServiceError);
    }

    public Future<?> createSharedResources() {
        return context.getRequest()
                .body()
                .compose(buffer -> {
                    ShareResourcesRequest request;
                    try {
                        String body = buffer.toString(StandardCharsets.UTF_8);
                        request = ProxyUtil.convertToObject(body, ShareResourcesRequest.class);
                    } catch (Exception e) {
                        log.error("Invalid request body provided", e);
                        throw new IllegalArgumentException("Can't initiate share request. Incorrect body");
                    }

                    String bucketLocation = BlobStorageUtil.buildInitiatorBucket(context);
                    String bucket = encryptionService.encrypt(bucketLocation);
                    return proxy.getVertx().executeBlocking(() -> shareService.initializeShare(bucket, bucketLocation, request));
                }).onSuccess(response -> context.respond(HttpStatus.OK, response))
                .onFailure(this::handleServiceError);
    }

    public Future<?> discardSharedResources() {
        return context.getRequest()
                .body()
                .compose(buffer -> {
                    ResourceLinkCollection request = getResourceLinkCollection(buffer, Operation.DISCARD);
                    String bucketLocation = BlobStorageUtil.buildInitiatorBucket(context);
                    String bucket = encryptionService.encrypt(bucketLocation);
                    return proxy.getVertx()
                            .executeBlocking(() -> {
                                shareService.discardSharedAccess(bucket, bucketLocation, request);
                                return null;
                            });
                })
                .onSuccess(response -> context.respond(HttpStatus.OK))
                .onFailure(this::handleServiceError);
    }

    public Future<?> revokeSharedResources() {
        return context.getRequest()
                .body()
                .compose(buffer -> {
                    ResourceLinkCollection request = getResourceLinkCollection(buffer, Operation.REVOKE);
                    String bucketLocation = BlobStorageUtil.buildInitiatorBucket(context);
                    String bucket = encryptionService.encrypt(bucketLocation);
                    return proxy.getVertx()
                            .executeBlocking(() -> {
                                shareService.revokeSharedAccess(bucket, bucketLocation, request);
                                return null;
                            });
                }).onSuccess(response -> context.respond(HttpStatus.OK))
                .onFailure(this::handleServiceError);
    }

    private void handleServiceError(Throwable error) {
        if (error instanceof IllegalArgumentException) {
            context.respond(HttpStatus.BAD_REQUEST, error.getMessage());
        } else {
            context.respond(HttpStatus.INTERNAL_SERVER_ERROR, error.getMessage());
        }
    }

    private ResourceLinkCollection getResourceLinkCollection(Buffer buffer, Operation operation) {
        try {
            String body = buffer.toString(StandardCharsets.UTF_8);
            return ProxyUtil.convertToObject(body, ResourceLinkCollection.class);
        } catch (Exception e) {
            log.error("Invalid request body provided", e);
            throw new HttpException(HttpStatus.BAD_REQUEST, "Can't %s shared resources. Incorrect body".formatted(operation));
        }
    }

    public enum Operation {
        CREATE, LIST, DISCARD, REVOKE
    }
}
