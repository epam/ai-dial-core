package com.epam.aidial.core.controller;

import com.epam.aidial.core.Proxy;
import com.epam.aidial.core.ProxyContext;
import com.epam.aidial.core.data.Invitation;
import com.epam.aidial.core.data.InvitationCollection;
import com.epam.aidial.core.security.EncryptionService;
import com.epam.aidial.core.service.InvitationService;
import com.epam.aidial.core.service.PermissionDeniedException;
import com.epam.aidial.core.service.ResourceNotFoundException;
import com.epam.aidial.core.service.ShareService;
import com.epam.aidial.core.storage.BlobStorageUtil;
import com.epam.aidial.core.util.HttpStatus;
import io.vertx.core.Future;
import lombok.AllArgsConstructor;

import java.util.HashSet;
import java.util.List;

public class InvitationController {

    final Proxy proxy;
    final ProxyContext context;
    final InvitationService invitationService;
    final ShareService shareService;
    final EncryptionService encryptionService;

    public InvitationController(Proxy proxy, ProxyContext context) {
        this.proxy = proxy;
        this.context = context;
        this.invitationService = proxy.getInvitationService();
        this.shareService = proxy.getShareService();
        this.encryptionService = proxy.getEncryptionService();
    }

    public Future<?> getInvitations() {
        proxy.getVertx().executeBlocking(() -> {
            String bucketLocation = BlobStorageUtil.buildInitiatorBucket(context);
            String bucket = encryptionService.encrypt(bucketLocation);
            List<Invitation> invitations = invitationService.getMyInvitations(bucket, bucketLocation);
            InvitationCollection response = new InvitationCollection(new HashSet<>());
            response.getInvitations().addAll(invitations);

            return context.respond(HttpStatus.OK, response);
        });
        return Future.succeededFuture();
    }

    public Future<?> getOrAcceptInvitation(String invitationId) {
        String accept = context.getRequest().getParam("accept");
        if (accept != null) {
            proxy.getVertx().executeBlocking(() -> {
                try {
                    String bucketLocation = BlobStorageUtil.buildInitiatorBucket(context);
                    String bucket = encryptionService.encrypt(bucketLocation);
                    shareService.acceptSharedResources(bucket, bucketLocation, invitationId);
                    return context.respond(HttpStatus.OK);
                } catch (Exception e) {
                    if (e instanceof ResourceNotFoundException) {
                        return context.respond(HttpStatus.NOT_FOUND, "No invitation found for ID " + invitationId);
                    }
                    return context.respond(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage());
                }
            });
        } else {
            proxy.getVertx().executeBlocking(() -> {
                Invitation invitation = invitationService.getInvitation(invitationId);
                if (invitation == null) {
                    return context.respond(HttpStatus.NOT_FOUND, "No invitation found for ID " + invitationId);
                }

                return context.respond(HttpStatus.OK, invitation);
            });
        }
        return Future.succeededFuture();
    }

    public Future<?> deleteInvitation(String invitationId) {
        proxy.getVertx().executeBlocking(() -> {
            String bucketLocation = BlobStorageUtil.buildInitiatorBucket(context);
            String bucket = encryptionService.encrypt(bucketLocation);
            try {
                invitationService.deleteInvitation(bucket, bucketLocation, invitationId);
                return context.respond(HttpStatus.OK);
            } catch (Exception e) {
                if (e instanceof PermissionDeniedException) {
                    return context.respond(HttpStatus.FORBIDDEN, e.getMessage());
                }

                if (e instanceof ResourceNotFoundException) {
                    return context.respond(HttpStatus.NOT_FOUND, e.getMessage());
                }

                return context.respond(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage());
            }
        });

        return Future.succeededFuture();
    }
}
