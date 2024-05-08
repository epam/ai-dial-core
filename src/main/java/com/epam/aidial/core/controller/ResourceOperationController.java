package com.epam.aidial.core.controller;

import com.epam.aidial.core.Proxy;
import com.epam.aidial.core.ProxyContext;
import com.epam.aidial.core.data.MoveResourcesRequest;
import com.epam.aidial.core.security.EncryptionService;
import com.epam.aidial.core.service.LockService;
import com.epam.aidial.core.service.ResourceOperationService;
import com.epam.aidial.core.storage.BlobStorageUtil;
import com.epam.aidial.core.storage.ResourceDescription;
import com.epam.aidial.core.util.HttpException;
import com.epam.aidial.core.util.HttpStatus;
import com.epam.aidial.core.util.ProxyUtil;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class ResourceOperationController {

    private final ProxyContext context;
    private final Proxy proxy;
    private final Vertx vertx;
    private final EncryptionService encryptionService;
    private final ResourceOperationService resourceOperationService;
    private final LockService lockService;

    public ResourceOperationController(Proxy proxy, ProxyContext context) {
        this.context = context;
        this.proxy = proxy;
        this.vertx = proxy.getVertx();
        this.encryptionService = proxy.getEncryptionService();
        this.resourceOperationService = proxy.getResourceOperationService();
        this.lockService = proxy.getLockService();
    }

    public Future<?> move() {
        context.getRequest()
                .body()
                .compose(buffer -> {
                    MoveResourcesRequest request;
                    try {
                        request = ProxyUtil.convertToObject(buffer, MoveResourcesRequest.class);
                    } catch (Exception e) {
                        log.error("Invalid request body provided", e);
                        throw new IllegalArgumentException("Can't initiate move resource request. Incorrect body provided");
                    }

                    String sourceUrl = request.getSourceUrl();
                    if (sourceUrl == null) {
                        throw new IllegalArgumentException("sourceUrl must be provided");
                    }

                    String destinationUrl = request.getDestinationUrl();
                    if (destinationUrl == null) {
                        throw new IllegalArgumentException("destinationUrl must be provided");
                    }

                    String bucketLocation = BlobStorageUtil.buildInitiatorBucket(context);
                    String bucket = encryptionService.encrypt(bucketLocation);

                    ResourceDescription sourceResource = ResourceDescription.fromPrivateUrl(sourceUrl, encryptionService);
                    if (!sourceResource.getBucketName().equals(bucket)) {
                        throw new IllegalArgumentException("sourceUrl do not belong to the user");
                    }

                    ResourceDescription destinationResource = ResourceDescription.fromPrivateUrl(destinationUrl, encryptionService);
                    if (!destinationResource.getBucketName().equals(bucket)) {
                        throw new IllegalArgumentException("destinationUrl do not belong to the user");
                    }

                    if (!sourceResource.getType().equals(destinationResource.getType())) {
                        throw new IllegalArgumentException("source and destination resources must be the same type");
                    }

                    return vertx.executeBlocking(() -> lockService.underBucketLock(bucketLocation, () -> {
                        resourceOperationService.moveResource(bucket, bucketLocation, sourceResource, destinationResource, request.isOverwrite());
                        return null;
                    }), false);
                })
                .onSuccess(ignore -> context.respond(HttpStatus.OK))
                .onFailure(this::handleServiceError);

        return Future.succeededFuture();
    }

    private void handleServiceError(Throwable error) {
        if (error instanceof IllegalArgumentException) {
            context.respond(HttpStatus.BAD_REQUEST, error.getMessage());
        } else if (error instanceof HttpException httpException) {
            context.respond(httpException.getStatus(), httpException.getMessage());
        } else {
            context.respond(HttpStatus.INTERNAL_SERVER_ERROR, error.getMessage());
        }
    }
}
