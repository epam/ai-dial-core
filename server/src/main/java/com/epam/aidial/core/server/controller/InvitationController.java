package com.epam.aidial.core.server.controller;

import com.epam.aidial.core.openapi.annotations.ApiOperation;
import com.epam.aidial.core.openapi.annotations.ApiParameter;
import com.epam.aidial.core.openapi.annotations.ApiResponse;
import com.epam.aidial.core.openapi.annotations.OpenApiDescriptions;
import com.epam.aidial.core.openapi.annotations.ParameterIn;
import com.epam.aidial.core.openapi.annotations.ResponseProfile;
import com.epam.aidial.core.server.Proxy;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.data.Invitation;
import com.epam.aidial.core.server.data.InvitationCollection;
import com.epam.aidial.core.server.security.EncryptionService;
import com.epam.aidial.core.server.service.InvitationService;
import com.epam.aidial.core.server.service.PermissionDeniedException;
import com.epam.aidial.core.server.service.ShareService;
import com.epam.aidial.core.server.util.BucketBuilder;
import com.epam.aidial.core.storage.exception.ResourceNotFoundException;
import com.epam.aidial.core.storage.http.HttpStatus;
import com.epam.aidial.core.storage.service.LockService;
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

    @ApiOperation(
            method = "GET",
            path = "/v1/invitations",
            operationId = "getInvitations",
            tags = {"Sharing"},
            responses = {
                    @ApiResponse(code = 200, description = "Success", body = InvitationCollection.class)
            },
            responseProfile = ResponseProfile.AUTHENTICATED_READ_EXTENDED)
    public Future<?> getInvitations() {
        proxy.getTaskExecutor()
                .submit(() -> {
                    String bucketLocation = BucketBuilder.buildInitiatorBucket(context);
                    String bucket = encryptionService.encrypt(bucketLocation);
                    return invitationService.getMyInvitations(bucket, bucketLocation);
                })
                .onSuccess(response -> context.respond(HttpStatus.OK, response))
                .onFailure(error -> context.respond(HttpStatus.INTERNAL_SERVER_ERROR, error.getMessage()));
        return Future.succeededFuture();
    }

    @ApiOperation(
            method = "GET",
            path = "/v1/invitations/{invitation_id}",
            operationId = "getInvitation",
            tags = {"Sharing"},
            parameters = {
                    @ApiParameter(name = "accept", in = ParameterIn.QUERY, description = OpenApiDescriptions.INVITATION_ACCEPT, schema = Boolean.class)
            },
            responses = {
                @ApiResponse(code = 200, description = "Success", body = Invitation.class)
            },
            responseProfile = ResponseProfile.AUTHORIZED_OPERATION
    )
    public Future<?> getOrAcceptInvitation(String invitationId) {
        boolean accept = Boolean.parseBoolean(context.getRequest().getParam("accept"));
        if (accept) {
            proxy.getTaskExecutor()
                    .submit(() -> {
                        shareService.acceptSharedResources(context, invitationId);
                        return null;
                    })
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
            proxy.getTaskExecutor()
                    .submit(() -> invitationService.getInvitation(invitationId))
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

    @ApiOperation(
            method = "DELETE",
            path = "/v1/invitations/{invitation_id}",
            operationId = "deleteInvitation", tags = {"Sharing"},
            responses = {
                    @ApiResponse(code = 200, description = "Success")
            },
            responseProfile = ResponseProfile.AUTHORIZED_OPERATION)
    public Future<?> deleteInvitation(String invitationId) {
        proxy.getTaskExecutor()
                .submit(() -> {
                    String bucketLocation = BucketBuilder.buildInitiatorBucket(context);
                    String bucket = encryptionService.encrypt(bucketLocation);
                    return lockService.underBucketLock(bucketLocation, () -> {
                        invitationService.deleteInvitation(bucket, invitationId);
                        return null;
                    });
                })
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