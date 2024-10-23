package com.epam.aidial.core.server.controller;

import com.epam.aidial.core.server.Proxy;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.service.InvitationService;
import com.epam.aidial.core.server.service.LockService;
import com.epam.aidial.core.server.service.ResourceService;
import com.epam.aidial.core.server.service.ShareService;
import com.epam.aidial.core.server.resource.ResourceDescription;
import com.epam.aidial.core.server.util.EtagHeader;
import com.epam.aidial.core.server.util.HttpStatus;
import io.vertx.core.Future;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class DeleteFileController extends AccessControlBaseController {

    private final ShareService shareService;
    private final InvitationService invitationService;
    private final LockService lockService;
    private final ResourceService resourceService;

    public DeleteFileController(Proxy proxy, ProxyContext context) {
        super(proxy, context, true);
        this.shareService = proxy.getShareService();
        this.invitationService = proxy.getInvitationService();
        this.lockService = proxy.getLockService();
        this.resourceService = proxy.getResourceService();
    }

    @Override
    protected Future<?> handle(ResourceDescription resource, boolean hasWriteAccess) {
        if (resource.isFolder()) {
            return context.respond(HttpStatus.BAD_REQUEST, "Can't delete a folder");
        }

        proxy.getVertx().executeBlocking(() -> {
            EtagHeader etag = EtagHeader.fromRequest(context.getRequest());
            String bucketName = resource.getBucketName();
            String bucketLocation = resource.getBucketLocation();
            return lockService.underBucketLock(bucketLocation, () -> {
                invitationService.cleanUpResourceLink(bucketName, bucketLocation, resource);
                shareService.revokeSharedResource(bucketName, bucketLocation, resource);
                resourceService.deleteResource(resource, etag);

                return null;
            });
        }, false)
                .onSuccess(success -> context.respond(HttpStatus.OK))
                .onFailure(error -> {
                    log.error("Failed to delete file  {}/{}", resource.getBucketName(), resource.getOriginalPath(), error);
                    context.respond(error, error.getMessage());
                });

        return Future.succeededFuture();
    }
}
