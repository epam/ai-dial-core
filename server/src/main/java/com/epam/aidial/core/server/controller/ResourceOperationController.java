package com.epam.aidial.core.server.controller;

import com.epam.aidial.core.config.ResourceAccessType;
import com.epam.aidial.core.openapi.annotations.ApiOperation;
import com.epam.aidial.core.openapi.annotations.ApiResponse;
import com.epam.aidial.core.openapi.annotations.ApiSchema;
import com.epam.aidial.core.openapi.annotations.OpenApiDescriptions;
import com.epam.aidial.core.openapi.annotations.ResponseProfile;
import com.epam.aidial.core.server.Proxy;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.data.CopyResourcesRequest;
import com.epam.aidial.core.server.data.MoveResourcesRequest;
import com.epam.aidial.core.server.data.SubscribeResourcesRequest;
import com.epam.aidial.core.server.security.AccessService;
import com.epam.aidial.core.server.security.EncryptionService;
import com.epam.aidial.core.server.service.HeartbeatService;
import com.epam.aidial.core.server.service.PermissionDeniedException;
import com.epam.aidial.core.server.service.ResourceOperationService;
import com.epam.aidial.core.server.util.ProxyUtil;
import com.epam.aidial.core.server.util.ResourceDescriptorFactory;
import com.epam.aidial.core.server.vertx.AsyncTaskExecutor;
import com.epam.aidial.core.storage.data.ResourceEvent;
import com.epam.aidial.core.storage.http.HttpException;
import com.epam.aidial.core.storage.http.HttpStatus;
import com.epam.aidial.core.storage.resource.ResourceDescriptor;
import com.epam.aidial.core.storage.resource.ResourceType;
import com.epam.aidial.core.storage.resource.ResourceTypes;
import com.epam.aidial.core.storage.service.LockService;
import com.epam.aidial.core.storage.service.ResourceTopic;
import io.vertx.core.Future;
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

    private static final Set<ResourceType> SUBSCRIPTION_ALLOWED_TYPES = Set.of(
            ResourceTypes.FILE, ResourceTypes.CONVERSATION, ResourceTypes.PROMPT, ResourceTypes.APPLICATION, ResourceTypes.TOOL_SET);

    private final ProxyContext context;
    private final AsyncTaskExecutor taskExecutor;
    private final EncryptionService encryptionService;
    private final ResourceOperationService resourceOperationService;
    private final LockService lockService;
    private final AccessService accessService;
    private final HeartbeatService heartbeatService;

    public ResourceOperationController(Proxy proxy, ProxyContext context) {
        this.context = context;
        this.taskExecutor = proxy.getTaskExecutor();
        this.encryptionService = proxy.getEncryptionService();
        this.resourceOperationService = proxy.getResourceOperationService();
        this.lockService = proxy.getLockService();
        this.accessService = proxy.getAccessService();
        this.heartbeatService = proxy.getHeartbeatService();
    }

    @ApiOperation(
            method = "POST",
            path = "/v1/ops/resource/move",
            operationId = "moveResource",
            requestBody = @ApiSchema(implementation = MoveResourcesRequest.class),
            tags = {"Files", "Conversations", "Prompts", "Applications", "Toolsets"},
            responses = {
                    @ApiResponse(code = 200, description = "Success")
            },
            responseProfile = ResponseProfile.AUTHORIZED_OPERATION
    )
    public Future<?> move() {
        context.getRequest()
                .body()
                .compose(buffer -> {
                    MoveResourcesRequest request;
                    try {
                        request = ProxyUtil.convertToObject(buffer, MoveResourcesRequest.class);
                    } catch (Exception e) {
                        log.warn("Invalid request body provided", e);
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
                        throw new PermissionDeniedException("No read and write access to source resource");
                    }

                    if (!permissions.get(destination).contains(ResourceAccessType.WRITE)) {
                        throw new PermissionDeniedException("No write access to destination resource");
                    }

                    List<String> buckets = List.of(source.getBucketLocation(), destination.getBucketLocation());
                    return taskExecutor.submit(() -> lockService.underBucketLocks(buckets, () -> {
                        resourceOperationService.moveResource(context, source, destination, request.isOverwrite());
                        return null;
                    }));
                })
                .onSuccess(ignore -> context.respond(HttpStatus.OK))
                .onFailure(this::handleServiceError);

        return Future.succeededFuture();
    }

    @ApiOperation(
            method = "POST",
            path = "/v1/ops/resource/copy",
            operationId = "copyResource",
            requestBody = @ApiSchema(implementation = CopyResourcesRequest.class),
            tags = {"Files", "Conversations", "Prompts", "Applications", "Toolsets"},
            responses = {
                    @ApiResponse(code = 200, description = "Success")
            },
            responseProfile = ResponseProfile.AUTHORIZED_OPERATION
    )
    public Future<?> copy() {
        context.getRequest()
                .body()
                .compose(buffer -> {
                    CopyResourcesRequest request;
                    try {
                        request = ProxyUtil.convertToObject(buffer, CopyResourcesRequest.class);
                    } catch (Exception e) {
                        throw new IllegalArgumentException("bad request body");
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
                        throw new IllegalArgumentException("source and destination resources must have same type");
                    }

                    if (source.getUrl().equals(destination.getUrl())) {
                        throw new IllegalArgumentException("source and destination resources must have different urls");
                    }

                    Set<ResourceDescriptor> resources = Set.of(source, destination);
                    Map<ResourceDescriptor, Set<ResourceAccessType>> permissions =
                            accessService.lookupPermissions(resources, context);

                    if (!permissions.get(source).contains(ResourceAccessType.READ)) {
                        throw new PermissionDeniedException("No read access to source resource");
                    }

                    if (!permissions.get(destination).contains(ResourceAccessType.WRITE)) {
                        throw new PermissionDeniedException("No write access to destination resource");
                    }

                    if (source.getType() == ResourceTypes.APPLICATION || source.getType() == ResourceTypes.TOOL_SET) {
                        if (!permissions.get(source).contains(ResourceAccessType.WRITE)) {
                            throw new PermissionDeniedException("No write access to source resource");
                        }
                    }

                    return taskExecutor.submit(() -> {
                        resourceOperationService.copyResource(context, source, destination, request.isOverwrite());
                        return null;
                    });
                })
                .onSuccess(ignore -> context.respond(HttpStatus.OK))
                .onFailure(this::handleServiceError);

        return Future.succeededFuture();
    }

    @ApiOperation(
            method = "POST",
            path = "/v1/ops/resource/subscribe",
            operationId = "subscribeToResources",
            requestBody = @ApiSchema(implementation = SubscribeResourcesRequest.class),
            contentType = "text/event-stream",
            tags = {"Notifications"},
            responses = {
                    @ApiResponse(code = 200, description = OpenApiDescriptions.RESPONSE_SUCCESS,
                            body = @ApiSchema(implementation = ResourceEvent.class), contentTypes = {"text/event-stream"}),
                    @ApiResponse(code = 400, description = OpenApiDescriptions.RESPONSE_BAD_REQUEST),
                    @ApiResponse(code = 401, description = OpenApiDescriptions.RESPONSE_UNAUTHORIZED),
                    @ApiResponse(code = 500, description = OpenApiDescriptions.RESPONSE_SERVER_ERROR)
            }
    )
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

                    return taskExecutor.submit(() -> {
                        ResourceTopic.Subscription subscription =
                                resourceOperationService.subscribeResources(resources, subscriber);
                        heartbeatService.subscribe(heartbeat);
                        return subscription;
                    });
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
                throw new PermissionDeniedException("Resource is not allowed: " + resource.getUrl());
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