package com.epam.aidial.core.controller;

import com.epam.aidial.core.Proxy;
import com.epam.aidial.core.ProxyContext;
import com.epam.aidial.core.data.Invitation;
import com.epam.aidial.core.data.InvitationCollection;
import com.epam.aidial.core.service.InvitationService;
import com.epam.aidial.core.service.ShareService;
import com.epam.aidial.core.storage.BlobStorageUtil;
import com.epam.aidial.core.util.HttpStatus;
import io.vertx.core.Future;
import lombok.AllArgsConstructor;

import java.util.HashSet;
import java.util.List;

@AllArgsConstructor
public class InvitationController {

    final Proxy proxy;
    final ProxyContext context;

    public Future<?> getInvitations() {
        proxy.getVertx().executeBlocking(() -> {
            InvitationService invitationService = proxy.getInvitationService();
            String bucketLocation = BlobStorageUtil.buildInitiatorBucket(context);
            String bucket = proxy.getEncryptionService().encrypt(bucketLocation);
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
                    ShareService shareService = proxy.getShareService();
                    String bucketLocation = BlobStorageUtil.buildInitiatorBucket(context);
                    String bucket = proxy.getEncryptionService().encrypt(bucketLocation);
                    shareService.acceptSharedResources(bucket, bucketLocation, invitationId);
                    return context.respond(HttpStatus.OK);
                } catch (Exception e) {
                    return context.respond(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage());
                }
            });
        } else {
            proxy.getVertx().executeBlocking(() -> {
                InvitationService invitationService = proxy.getInvitationService();
                Invitation invitation = invitationService.getInvitation(invitationId);
                if (invitation == null) {
                    return context.respond(HttpStatus.NOT_FOUND);
                }

                return context.respond(HttpStatus.OK, invitation);
            });
        }
        return Future.succeededFuture();
    }
}
