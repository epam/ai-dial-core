package com.epam.aidial.core.server.controller;

import com.epam.aidial.core.server.Proxy;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.data.MoveResourcesRequest;
import com.epam.aidial.core.server.data.ResourceAccessType;
import com.epam.aidial.core.server.data.ResourceEvent;
import com.epam.aidial.core.server.data.ResourceTypes;
import com.epam.aidial.core.server.data.SubscribeResourcesRequest;
import com.epam.aidial.core.server.resource.ResourceDescriptor;
import com.epam.aidial.core.server.resource.ResourceDescriptorFactory;
import com.epam.aidial.core.server.security.AccessService;
import com.epam.aidial.core.server.security.EncryptionService;
import com.epam.aidial.core.server.service.HeartbeatService;
import com.epam.aidial.core.server.service.LockService;
import com.epam.aidial.core.server.service.PermissionDeniedException;
import com.epam.aidial.core.server.service.ResourceOperationService;
import com.epam.aidial.core.server.service.ResourceTopic;
import com.epam.aidial.core.server.util.HttpException;
import com.epam.aidial.core.server.util.HttpStatus;
import com.epam.aidial.core.server.util.ProxyUtil;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpHeaders;
import io.vertx.core.http.HttpServerResponse;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;

@Slf4j
public class ResourceOperationController {

    private static final Set<ResourceTypes> SUBSCRIPTION_ALLOWED_TYPES = Set.of(
            ResourceTypes.FILE, ResourceTypes.CONVERSATION, ResourceTypes.PROMPT, ResourceTypes.APPLICATION);

    private final ProxyContext context;
    private final Vertx vertx;
    private final EncryptionService encryptionService;
    private final ResourceOperationService resourceOperationService;
    private final LockService lockService;
    private final AccessService accessService;
    private final HeartbeatService heartbeatService;

    public ResourceOperationController(Proxy proxy, ProxyContext context) {
        this.context = context;
        this.vertx = proxy.getVertx();
        this.encryptionService = proxy.getEncryptionService();
        this.resourceOperationService = proxy.getResourceOperationService();
        this.lockService = proxy.getLockService();
        this.accessService = proxy.getAccessService();
        this.heartbeatService = proxy.getHeartbeatService();
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

                    ResourceDescriptor source = ResourceDescriptorFactory.fromAnyUrl(sourceUrl, encryptionService);
                    ResourceDescriptor destination = ResourceDescriptorFactory.fromAnyUrl(destinationUrl, encryptionService);

                    if (!source.getType().equals(destination.getType())) {
                        throw new IllegalArgumentException("source and destination resources must be the same type");
                    }

                    if (source.getUrl().equals(destination.getUrl())) {
                        throw new IllegalArgumentException("source and destination resources cannot be the same");
                    }

                    Set<ResourceDescriptor> resources = Set.of(source, destination);
                    Map<ResourceDescriptor, Set<ResourceAccessType>> permissions =
                            accessService.lookupPermissions(resources, context);

                    if (!permissions.get(source).containsAll(ResourceAccessType.ALL)) {
                        throw new PermissionDeniedException("no read and write access to source resource");
                    }

                    if (!permissions.get(destination).contains(ResourceAccessType.WRITE)) {
                        throw new PermissionDeniedException("no write access to destination resource");
                    }

                    List<String> buckets = List.of(source.getBucketLocation(), destination.getBucketLocation());
                    return vertx.executeBlocking(() -> lockService.underBucketLocks(buckets, () -> {
                        resourceOperationService.moveResource(source, destination, request.isOverwrite());
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
        Runnable heartbeat = this::sendHeartbeat;

        context.getRequest()
                .body()
                .compose(buffer -> {
                    Set<ResourceDescriptor> resources = parseAndVerifySubscriptionRequest(buffer);

                    response.setChunked(true)
                            .setStatusCode(200)
                            .putHeader(HttpHeaders.CONTENT_TYPE, "text/event-stream")
                            .write(""); // to force writing header

                    return vertx.executeBlocking(() -> {
                        ResourceTopic.Subscription subscription =
                                resourceOperationService.subscribeResources(resources, subscriber);
                        heartbeatService.subscribe(heartbeat);
                        return subscription;
                    }, false);
                })
                .onSuccess(subscription -> response.closeHandler(event -> {
                    heartbeatService.unsubscribe(heartbeat);
                    subscription.close();
                }))
                .onFailure(this::handleServiceError);

        return Future.succeededFuture();
    }

    private Set<ResourceDescriptor> parseAndVerifySubscriptionRequest(Buffer buffer) {
        SubscribeResourcesRequest request;
        try {
            request = ProxyUtil.convertToObject(buffer, SubscribeResourcesRequest.class);
        } catch (Throwable e) {
            throw new IllegalArgumentException("Invalid body provided");
        }

        if (request.getResources() == null || request.getResources().isEmpty()) {
            throw new IllegalArgumentException("resources list must be provided");
        }

        Set<ResourceDescriptor> resources = request.getResources().stream()
                .map(link -> ResourceDescriptorFactory.fromAnyUrl(link.url(), encryptionService))
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
            ResourceDescriptor resource = ResourceDescriptorFactory.fromAnyUrl(event.getUrl(), encryptionService);

            if (accessService.hasReadAccess(resource, context)) {
                String json = ProxyUtil.convertToString(event);
                response.write("data: " + json + "\n\n");
            }
        } catch (Throwable e) {
            log.warn("Can't send resource event", e);
            response.reset();
        }
    }

    private void sendHeartbeat() {
        HttpServerResponse response = context.getResponse();

        try {
            response.write(": heartbeat\n\n");
        } catch (Throwable e) {
            log.warn("Can't send a heartbeat", e);
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
