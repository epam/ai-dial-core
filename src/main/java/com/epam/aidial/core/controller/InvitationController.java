package com.epam.aidial.core.controller;

import com.epam.aidial.core.Proxy;
import com.epam.aidial.core.ProxyContext;
import com.epam.aidial.core.data.Invitation;
import com.epam.aidial.core.service.InvitationService;
import com.epam.aidial.core.service.ShareService;
import com.epam.aidial.core.storage.BlobStorageUtil;
import com.epam.aidial.core.util.HttpStatus;
import io.vertx.core.Future;
import lombok.AllArgsConstructor;

import java.util.List;

@AllArgsConstructor
public class InvitationController {

    final Proxy proxy;
    final ProxyContext context;

    public Future<?> getInvitations() {
        return proxy.getVertx().executeBlocking(() -> {
            InvitationService invitationService = proxy.getInvitationService();
            String userId = context.getUserSub() == null ? context.getProject() : context.getUserSub();
            List<Invitation> invitations = invitationService.getMyInvitations(userId);

            return context.respond(HttpStatus.OK, invitations);
        });
    }

    public Future<?> getOrAcceptInvitation(String invitationId) {
        String accept = context.getRequest().getParam("accept");
        if (accept != null) {
            return proxy.getVertx().executeBlocking(() -> {
                try {
                    ShareService shareService = proxy.getShareService();
                    String bucketLocation = BlobStorageUtil.buildInitiatorBucket(context);
                    String bucket = proxy.getEncryptionService().encrypt(bucketLocation);
                    shareService.acceptShare(bucket, bucketLocation, invitationId);
                    return context.respond(HttpStatus.OK);
                } catch (Exception e) {
                    return context.respond(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage());
                }
            });
        } else {
            return proxy.getVertx().executeBlocking(() -> {
                InvitationService invitationService = proxy.getInvitationService();
                Invitation invitation = invitationService.getInvitation(invitationId);
                if (invitation == null) {
                    return context.respond(HttpStatus.NOT_FOUND);
                }

                return context.respond(HttpStatus.OK, invitation);
            });
        }
    }
}
