package com.epam.aidial.core.controller;

import com.epam.aidial.core.Proxy;
import com.epam.aidial.core.ProxyContext;
import com.epam.aidial.core.data.MoveResourcesRequest;
import com.epam.aidial.core.data.ResourceAccessType;
import com.epam.aidial.core.data.ResourceEvent;
import com.epam.aidial.core.data.ResourceType;
import com.epam.aidial.core.data.SubscribeResourcesRequest;
import com.epam.aidial.core.security.AccessService;
import com.epam.aidial.core.security.EncryptionService;
import com.epam.aidial.core.service.LockService;
import com.epam.aidial.core.service.PermissionDeniedException;
import com.epam.aidial.core.service.ResourceOperationService;
import com.epam.aidial.core.storage.BlobStorageUtil;
import com.epam.aidial.core.storage.ResourceDescription;
import com.epam.aidial.core.util.HttpException;
import com.epam.aidial.core.util.HttpStatus;
import com.epam.aidial.core.util.ProxyUtil;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpHeaders;
import io.vertx.core.http.HttpServerResponse;
import lombok.extern.slf4j.Slf4j;

import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;

@Slf4j
public class ResourceOperationController {

    private static final Set<ResourceType> SUBSCRIPTION_ALLOWED_TYPES = Set.of(
            ResourceType.FILE, ResourceType.CONVERSATION, ResourceType.PROMPT, ResourceType.APPLICATION);

    private final ProxyContext context;
    private final Vertx vertx;
    private final EncryptionService encryptionService;
    private final ResourceOperationService resourceOperationService;
    private final LockService lockService;
    private final AccessService accessService;

    public ResourceOperationController(Proxy proxy, ProxyContext context) {
        this.context = context;
        this.vertx = proxy.getVertx();
        this.encryptionService = proxy.getEncryptionService();
        this.resourceOperationService = proxy.getResourceOperationService();
        this.lockService = proxy.getLockService();
        this.accessService = proxy.getAccessService();
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

                    if (sourceResource.getUrl().equals(destinationResource.getUrl())) {
                        throw new IllegalArgumentException("source and destination resources cannot be the same");
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

    public Future<?> subscribe() {
        HttpServerResponse response = context.getResponse();
        Consumer<ResourceEvent> subscriber = this::sendSubscriptionEvent;

        context.getRequest()
                .body()
                .compose(buffer -> {
                    Set<ResourceDescription> resources = parseAndVerifySubscriptionRequest(buffer);

                    response.setChunked(true)
                            .setStatusCode(200)
                            .putHeader(HttpHeaders.CONTENT_TYPE, "text/event-stream")
                            .write(""); // to force writing header

                    return vertx.executeBlocking(() -> resourceOperationService.subscribeResources(resources, subscriber), false);
                })
                .onSuccess(subscription -> response.closeHandler(event -> subscription.close()))
                .onFailure(this::handleServiceError);

        return Future.succeededFuture();
    }

    private Set<ResourceDescription> parseAndVerifySubscriptionRequest(Buffer buffer) {
        SubscribeResourcesRequest request;
        try {
            request = ProxyUtil.convertToObject(buffer, SubscribeResourcesRequest.class);
        } catch (Throwable e) {
            throw new IllegalArgumentException("Invalid body provided");
        }

        if (request.getResources() == null || request.getResources().isEmpty()) {
            throw new IllegalArgumentException("resources list must be provided");
        }

        Set<ResourceDescription> resources = request.getResources().stream()
                .map(link -> ResourceDescription.fromAnyUrl(link.url(), encryptionService))
                .peek(resource -> {
                    if (resource.isFolder()) {
                        throw new IllegalArgumentException("resource folder is not supported: " + resource.getUrl());
                    }

                    if (!SUBSCRIPTION_ALLOWED_TYPES.contains(resource.getType())) {
                        throw new IllegalArgumentException("resource type is not supported: " + resource.getUrl());
                    }
                })
                .collect(Collectors.toSet());

        accessService.lookupPermissions(resources, context).forEach((resource, permissions) -> {
            if (!permissions.contains(ResourceAccessType.READ)) {
                throw new PermissionDeniedException("resource is not allowed: " + resource.getUrl());
            }
        });

        return resources;
    }

    private void sendSubscriptionEvent(ResourceEvent event) {
        HttpServerResponse response = context.getResponse();

        try {
            ResourceDescription resource = ResourceDescription.fromAnyUrl(event.getUrl(), encryptionService);

            if (accessService.hasReadAccess(resource, context)) {
                String json = ProxyUtil.convertToString(event);
                response.write("data: " + json + "\n\n");
            }
        } catch (Throwable e) {
            log.warn("Can't send resource event", e);
            response.reset();
        }
    }

    private void handleServiceError(Throwable error) {
        if (error instanceof IllegalArgumentException) {
            context.respond(HttpStatus.BAD_REQUEST, error.getMessage());
        } else if (error instanceof PermissionDeniedException httpException) {
            context.respond(HttpStatus.FORBIDDEN, httpException.getMessage());
        } else if (error instanceof HttpException httpException) {
            context.respond(httpException.getStatus(), httpException.getMessage());
        } else {
            context.respond(HttpStatus.INTERNAL_SERVER_ERROR, error.getMessage());
        }
    }
}
