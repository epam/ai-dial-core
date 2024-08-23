package com.epam.aidial.core.controller;

import com.epam.aidial.core.Proxy;
import com.epam.aidial.core.ProxyContext;
import com.epam.aidial.core.security.EncryptionService;
import com.epam.aidial.core.service.InvitationService;
import com.epam.aidial.core.service.LockService;
import com.epam.aidial.core.service.PermissionDeniedException;
import com.epam.aidial.core.service.ResourceNotFoundException;
import com.epam.aidial.core.service.ShareService;
import com.epam.aidial.core.storage.BlobStorageUtil;
import com.epam.aidial.core.storage.ResourceDescription;
import com.epam.aidial.core.util.HttpStatus;
import io.vertx.core.Future;

public class InvitationController {

    private final Proxy proxy;
    private final ProxyContext context;
    private final InvitationService invitationService;
    private final ShareService shareService;
    private final EncryptionService encryptionService;
    private final LockService lockService;

    public InvitationController(Proxy proxy, ProxyContext context) {
        this.proxy = proxy;
        this.context = context;
        this.invitationService = proxy.getInvitationService();
        this.shareService = proxy.getShareService();
        this.encryptionService = proxy.getEncryptionService();
        this.lockService = proxy.getLockService();
    }

    public Future<?> getInvitations() {
        proxy.getVertx()
                .executeBlocking(() -> {
                    String bucketLocation = BlobStorageUtil.buildInitiatorBucket(context);
                    String bucket = encryptionService.encrypt(bucketLocation);
                    return invitationService.getMyInvitations(bucket, bucketLocation);
                }, false)
                .onSuccess(response -> context.respond(HttpStatus.OK, response))
                .onFailure(error -> context.respond(HttpStatus.INTERNAL_SERVER_ERROR, error.getMessage()));
        return Future.succeededFuture();
    }

    public Future<?> getOrAcceptInvitation(String invitationId) {
        boolean accept = Boolean.parseBoolean(context.getRequest().getParam("accept"));
        if (accept) {
            proxy.getVertx()
                    .executeBlocking(() -> {
                        String bucketLocation = BlobStorageUtil.buildInitiatorBucket(context);
                        String bucket = encryptionService.encrypt(bucketLocation);
                        ResourceDescription invitationResource = invitationService.getInvitationResource(invitationId);
                        if (invitationResource == null) {
                            throw new ResourceNotFoundException();
                        }
                        return lockService.underBucketLock(invitationResource.getBucketLocation(), () -> {
                            shareService.acceptSharedResources(bucket, bucketLocation, invitationId);
                            return null;
                        });
                    }, false)
                    .onSuccess(ignore -> context.respond(HttpStatus.OK))
                    .onFailure(error -> {
                        if (error instanceof ResourceNotFoundException) {
                            context.respond(HttpStatus.NOT_FOUND, "No invitation found for ID " + invitationId);
                        } else if (error instanceof IllegalArgumentException) {
                            context.respond(HttpStatus.BAD_REQUEST, error.getMessage());
                        } else {
                            context.respond(HttpStatus.INTERNAL_SERVER_ERROR, error.getMessage());
                        }
                    });
        } else {
            proxy.getVertx()
                    .executeBlocking(() -> invitationService.getInvitation(invitationId), false)
                    .onSuccess(invitation -> {
                        if (invitation == null) {
                            context.respond(HttpStatus.NOT_FOUND, "No invitation found for ID " + invitationId);
                        } else {
                            context.respond(HttpStatus.OK, invitation);
                        }
                    }).onFailure(error -> context.respond(HttpStatus.INTERNAL_SERVER_ERROR, error.getMessage()));
        }
        return Future.succeededFuture();
    }

    public Future<?> deleteInvitation(String invitationId) {
        proxy.getVertx()
                .executeBlocking(() -> {
                    String bucketLocation = BlobStorageUtil.buildInitiatorBucket(context);
                    String bucket = encryptionService.encrypt(bucketLocation);
                    return lockService.underBucketLock(bucketLocation, () -> {
                        invitationService.deleteInvitation(bucket, invitationId);
                        return null;
                    });
                }, false)
                .onSuccess(ignore -> context.respond(HttpStatus.OK))
                .onFailure(error -> {
                    String errorMessage = error.getMessage();
                    if (error instanceof PermissionDeniedException) {
                        context.respond(HttpStatus.FORBIDDEN, errorMessage);
                    } else if (error instanceof ResourceNotFoundException) {
                        context.respond(HttpStatus.NOT_FOUND, errorMessage);
                    } else {
                        context.respond(HttpStatus.INTERNAL_SERVER_ERROR, errorMessage);
                    }
                });

        return Future.succeededFuture();
    }
}
