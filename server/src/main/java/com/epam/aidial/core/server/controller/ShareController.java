package com.epam.aidial.core.server.controller;

import com.epam.aidial.core.config.ResourceAccessType;
import com.epam.aidial.core.openapi.annotations.ApiOperation;
import com.epam.aidial.core.openapi.annotations.ApiResponse;
import com.epam.aidial.core.openapi.annotations.ApiSchema;
import com.epam.aidial.core.openapi.annotations.ResponseProfile;
import com.epam.aidial.core.server.Proxy;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.data.CopySharedAccessRequest;
import com.epam.aidial.core.server.data.InvitationLink;
import com.epam.aidial.core.server.data.ListSharedResourcesRequest;
import com.epam.aidial.core.server.data.ResourceLinkCollection;
import com.epam.aidial.core.server.data.RevokeResourcesRequest;
import com.epam.aidial.core.server.data.ShareResourcesRequest;
import com.epam.aidial.core.server.data.SharedResource;
import com.epam.aidial.core.server.data.SharedResourcesResponse;
import com.epam.aidial.core.server.security.EncryptionService;
import com.epam.aidial.core.server.service.InvitationService;
import com.epam.aidial.core.server.service.ShareService;
import com.epam.aidial.core.server.util.BucketBuilder;
import com.epam.aidial.core.server.util.ProxyUtil;
import com.epam.aidial.core.server.util.ResourceDescriptorFactory;
import com.epam.aidial.core.storage.http.HttpException;
import com.epam.aidial.core.storage.http.HttpStatus;
import com.epam.aidial.core.storage.resource.ResourceDescriptor;
import com.epam.aidial.core.storage.service.LockService;
import io.vertx.core.Future;
import io.vertx.core.buffer.Buffer;
import lombok.extern.slf4j.Slf4j;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
public class ShareController {

    private static final String LIST_SHARED_BY_ME_RESOURCES = "others";

    private final Proxy proxy;
    private final ProxyContext context;
    private final ShareService shareService;
    private final EncryptionService encryptionService;
    private final LockService lockService;
    private final InvitationService invitationService;

    public ShareController(Proxy proxy, ProxyContext context) {
        this.proxy = proxy;
        this.context = context;
        this.shareService = proxy.getShareService();
        this.encryptionService = proxy.getEncryptionService();
        this.lockService = proxy.getLockService();
        this.invitationService = proxy.getInvitationService();
    }

    @ApiOperation(
            method = "POST",
            path = "/v1/ops/resource/share/create",
            operationId = "shareResource",
            requestBody = @ApiSchema(implementation = ShareResourcesRequest.class),
            tags = {"Sharing"},
            responses = {
                    @ApiResponse(code = 200, description = "Success", body = @ApiSchema(implementation = InvitationLink.class))
            },
            responseProfile = ResponseProfile.AUTHORIZED_OPERATION
    )
    @ApiOperation(
            method = "POST",
            path = "/v1/ops/resource/share/list",
            operationId = "getSharedResources",
            requestBody = @ApiSchema(implementation = ListSharedResourcesRequest.class),
            tags = {"Sharing"},
            responses = {
                    @ApiResponse(code = 200, description = "Success", body = @ApiSchema(implementation = SharedResourcesResponse.class))
            },
            responseProfile = ResponseProfile.AUTHORIZED_OPERATION
    )
    @ApiOperation(
            method = "POST",
            path = "/v1/ops/resource/share/revoke",
            operationId = "revokeSharedResources",
            requestBody = @ApiSchema(implementation = RevokeResourcesRequest.class),
            tags = {"Sharing"},
            responses = {
                    @ApiResponse(code = 200, description = "Success")
            },
                    responseProfile = ResponseProfile.AUTHORIZED_OPERATION
    )
    @ApiOperation(
            method = "POST",
            path = "/v1/ops/resource/share/discard",
            operationId = "discardSharedResources",
            requestBody = @ApiSchema(implementation = ResourceLinkCollection.class),
            tags = {"Sharing"},
            responses = {
                    @ApiResponse(code = 200, description = "Success")
            },
            responseProfile = ResponseProfile.AUTHORIZED_OPERATION
    )
    @ApiOperation(
            method = "POST",
            path = "/v1/ops/resource/share/copy",
            operationId = "copySharedResources",
            requestBody = @ApiSchema(implementation = CopySharedAccessRequest.class),
            tags = {"Sharing"},
            responses = {
                    @ApiResponse(code = 200, description = "Success")
            },
            responseProfile = ResponseProfile.AUTHORIZED_OPERATION
    )
    public Future<?> handle(Operation operation) {
        if (context.getApiKeyData().getPerRequestKey() != null) {
            context.respond(HttpStatus.FORBIDDEN, "The Share API is not allowed for per-request keys");
            return Future.succeededFuture();
        }

        switch (operation) {
            case LIST -> listSharedResources();
            case CREATE -> createSharedResources();
            case REVOKE -> revokeSharedResources();
            case DISCARD -> discardSharedResources();
            case COPY -> copySharedAccess();
            default ->
                    context.respond(HttpStatus.INTERNAL_SERVER_ERROR, "Operation %s is not supported".formatted(operation));
        }
        return Future.succeededFuture();
    }

    public void listSharedResources() {
        context.getRequest()
                .body()
                .compose(buffer -> {
                    ListSharedResourcesRequest request;
                    try {
                        String body = buffer.toString(StandardCharsets.UTF_8);
                        request = ProxyUtil.convertToObject(body, ListSharedResourcesRequest.class);
                    } catch (Exception e) {
                        log.warn("Invalid request body provided", e);
                        throw new IllegalArgumentException("Can't list shared resources. Incorrect body");
                    }

                    String bucketLocation = BucketBuilder.buildInitiatorBucket(context);
                    String bucket = encryptionService.encrypt(bucketLocation);
                    String with = request.getWith();

                    return proxy.getTaskExecutor().submit(() -> {
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

    public void createSharedResources() {
        context.getRequest()
                .body()
                .compose(buffer -> {
                    ShareResourcesRequest request;
                    try {
                        String body = buffer.toString(StandardCharsets.UTF_8);
                        request = ProxyUtil.convertToObject(body, ShareResourcesRequest.class);
                    } catch (Exception e) {
                        log.warn("Invalid request body provided", e);
                        throw new IllegalArgumentException("Can't initiate share request. Incorrect body");
                    }

                    return proxy.getTaskExecutor().submit(() -> shareService.initializeShare(context, request));
                })
                .onSuccess(response -> context.respond(HttpStatus.OK, response))
                .onFailure(this::handleServiceError);
    }

    public void discardSharedResources() {
        context.getRequest()
                .body()
                .compose(buffer -> {
                    ResourceLinkCollection request = getResourceLinkCollection(buffer, Operation.DISCARD);
                    String bucketLocation = BucketBuilder.buildInitiatorBucket(context);
                    String bucket = encryptionService.encrypt(bucketLocation);
                    return proxy.getTaskExecutor()
                            .submit(() -> {
                                shareService.discardSharedAccess(bucket, bucketLocation, request);
                                return null;
                            });
                })
                .onSuccess(response -> context.respond(HttpStatus.OK))
                .onFailure(this::handleServiceError);
    }

    public void revokeSharedResources() {
        context.getRequest()
                .body()
                .compose(buffer -> {
                    RevokeResourcesRequest request = getRevokeResourcesRequest(buffer, Operation.REVOKE);
                    String bucketLocation = BucketBuilder.buildInitiatorBucket(context);
                    String bucket = encryptionService.encrypt(bucketLocation);
                    Map<ResourceDescriptor, Set<ResourceAccessType>> permissionsToRevoke = request.getResources().stream()
                            .collect(Collectors.toUnmodifiableMap(
                                    resource -> ShareService.resourceFromUrl(resource.getUrl(), encryptionService),
                                    SharedResource::getPermissions));
                    return proxy.getTaskExecutor()
                            .submit(() -> lockService.underBucketLock(bucketLocation, () -> {
                                Map<ResourceDescriptor, Set<ResourceAccessType>> updatedPermissionsToRevoke =
                                        shareService.addSharedCredentialsToRevoke(permissionsToRevoke, context);
                                invitationService.cleanUpPermissions(bucket, bucketLocation, updatedPermissionsToRevoke);
                                shareService.revokeSharedAccess(bucket, bucketLocation, updatedPermissionsToRevoke);
                                return null;
                            }));
                })
                .onSuccess(response -> context.respond(HttpStatus.OK))
                .onFailure(this::handleServiceError);
    }

    public void copySharedAccess() {
        context.getRequest()
                .body()
                .compose(buffer -> {
                    CopySharedAccessRequest request;
                    try {
                        request = ProxyUtil.convertToObject(buffer, CopySharedAccessRequest.class);
                    } catch (Exception e) {
                        log.warn("Invalid request body provided", e);
                        throw new IllegalArgumentException("Can't initiate copy shared access request. Incorrect body provided");
                    }

                    String sourceUrl = request.sourceUrl();
                    if (sourceUrl == null) {
                        throw new IllegalArgumentException("sourceUrl must be provided");
                    }
                    String destinationUrl = request.destinationUrl();
                    if (destinationUrl == null) {
                        throw new IllegalArgumentException("destinationUrl must be provided");
                    }

                    String bucketLocation = BucketBuilder.buildInitiatorBucket(context);
                    String bucket = encryptionService.encrypt(bucketLocation);

                    ResourceDescriptor source = ResourceDescriptorFactory.fromPrivateUrl(sourceUrl, encryptionService);
                    if (!bucket.equals(source.getBucketName())) {
                        throw new IllegalArgumentException("sourceUrl does not belong to the user");
                    }
                    ResourceDescriptor destination = ResourceDescriptorFactory.fromPrivateUrl(destinationUrl, encryptionService);
                    if (!bucket.equals(destination.getBucketName())) {
                        throw new IllegalArgumentException("destinationUrl does not belong to the user");
                    }

                    if (source.getUrl().equals(destination.getUrl())) {
                        throw new IllegalArgumentException("source and destination cannot be the same");
                    }

                    return proxy.getTaskExecutor().submit(() ->
                            lockService.underBucketLock(bucketLocation, () -> {
                                shareService.copySharedAccess(bucket, bucketLocation, source, destination);
                                return null;
                            }));
                })
                .onSuccess(ignore -> context.respond(HttpStatus.OK))
                .onFailure(this::handleServiceError);
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

    private ResourceLinkCollection getResourceLinkCollection(Buffer buffer, Operation operation) {
        try {
            String body = buffer.toString(StandardCharsets.UTF_8);
            return ProxyUtil.convertToObject(body, ResourceLinkCollection.class);
        } catch (Exception e) {
            log.warn("Invalid request body provided", e);
            throw new HttpException(HttpStatus.BAD_REQUEST, "Can't %s shared resources. Incorrect body".formatted(operation));
        }
    }

    private RevokeResourcesRequest getRevokeResourcesRequest(Buffer buffer, Operation operation) {
        try {
            String body = buffer.toString(StandardCharsets.UTF_8);
            return ProxyUtil.convertToObject(body, RevokeResourcesRequest.class);
        } catch (Exception e) {
            log.warn("Invalid request body provided", e);
            throw new HttpException(HttpStatus.BAD_REQUEST, "Can't %s shared resources. Incorrect body".formatted(operation));
        }
    }

    public enum Operation {
        CREATE, LIST, DISCARD, REVOKE, COPY
    }
}