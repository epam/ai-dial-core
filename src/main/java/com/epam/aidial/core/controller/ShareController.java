package com.epam.aidial.core.controller;

import com.epam.aidial.core.Proxy;
import com.epam.aidial.core.ProxyContext;
import com.epam.aidial.core.data.CopySharedAccessRequest;
import com.epam.aidial.core.data.ListSharedResourcesRequest;
import com.epam.aidial.core.data.ResourceAccessType;
import com.epam.aidial.core.data.ResourceLinkCollection;
import com.epam.aidial.core.data.RevokeResourcesRequest;
import com.epam.aidial.core.data.ShareResourcesRequest;
import com.epam.aidial.core.data.SharedResource;
import com.epam.aidial.core.security.EncryptionService;
import com.epam.aidial.core.service.InvitationService;
import com.epam.aidial.core.service.LockService;
import com.epam.aidial.core.service.ShareService;
import com.epam.aidial.core.storage.BlobStorageUtil;
import com.epam.aidial.core.storage.ResourceDescription;
import com.epam.aidial.core.util.HttpException;
import com.epam.aidial.core.util.HttpStatus;
import com.epam.aidial.core.util.ProxyUtil;
import com.epam.aidial.core.util.ResourceUtil;
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

    public Future<?> handle(Operation operation) {
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
                    }, false);
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
                    return proxy.getVertx().executeBlocking(() -> shareService.initializeShare(bucket, bucketLocation, request), false);
                })
                .onSuccess(response -> context.respond(HttpStatus.OK, response))
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
                            }, false);
                })
                .onSuccess(response -> context.respond(HttpStatus.OK))
                .onFailure(this::handleServiceError);
    }

    public Future<?> revokeSharedResources() {
        return context.getRequest()
                .body()
                .compose(buffer -> {
                    RevokeResourcesRequest request = getRevokeResourcesRequest(buffer, Operation.REVOKE);
                    String bucketLocation = BlobStorageUtil.buildInitiatorBucket(context);
                    String bucket = encryptionService.encrypt(bucketLocation);
                    Map<ResourceDescription, Set<ResourceAccessType>> permissionsToRevoke = request.getResources().stream()
                            .collect(Collectors.toUnmodifiableMap(
                                    resource -> ResourceUtil.resourceFromUrl(resource.url(), encryptionService),
                                    SharedResource::permissions));
                    return proxy.getVertx()
                            .executeBlocking(() -> lockService.underBucketLock(bucketLocation, () -> {
                                invitationService.cleanUpPermissions(bucket, bucketLocation, permissionsToRevoke);
                                shareService.revokeSharedAccess(bucket, bucketLocation, permissionsToRevoke);
                                return null;
                            }), false);
                })
                .onSuccess(response -> context.respond(HttpStatus.OK))
                .onFailure(this::handleServiceError);
    }

    public Future<?> copySharedAccess() {
        return context.getRequest()
                .body()
                .compose(buffer -> {
                    CopySharedAccessRequest request;
                    try {
                        request = ProxyUtil.convertToObject(buffer, CopySharedAccessRequest.class);
                    } catch (Exception e) {
                        log.error("Invalid request body provided", e);
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

                    String bucketLocation = BlobStorageUtil.buildInitiatorBucket(context);
                    String bucket = encryptionService.encrypt(bucketLocation);

                    ResourceDescription source = ResourceDescription.fromPrivateUrl(sourceUrl, encryptionService);
                    if (!bucket.equals(source.getBucketName())) {
                        throw new IllegalArgumentException("sourceUrl does not belong to the user");
                    }
                    ResourceDescription destination = ResourceDescription.fromPrivateUrl(destinationUrl, encryptionService);
                    if (!bucket.equals(destination.getBucketName())) {
                        throw new IllegalArgumentException("destinationUrl does not belong to the user");
                    }

                    return proxy.getVertx().executeBlocking(() ->
                            lockService.underBucketLock(bucketLocation, () -> {
                                shareService.copySharedAccess(bucket, bucketLocation, source, destination);
                                return null;
                            }), false);
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
            log.error("Invalid request body provided", e);
            throw new HttpException(HttpStatus.BAD_REQUEST, "Can't %s shared resources. Incorrect body".formatted(operation));
        }
    }

    private RevokeResourcesRequest getRevokeResourcesRequest(Buffer buffer, Operation operation) {
        try {
            String body = buffer.toString(StandardCharsets.UTF_8);
            return ProxyUtil.convertToObject(body, RevokeResourcesRequest.class);
        } catch (Exception e) {
            log.error("Invalid request body provided", e);
            throw new HttpException(HttpStatus.BAD_REQUEST, "Can't %s shared resources. Incorrect body".formatted(operation));
        }
    }

    public enum Operation {
        CREATE, LIST, DISCARD, REVOKE, COPY
    }
}
