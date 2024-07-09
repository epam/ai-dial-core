package com.epam.aidial.core.controller;

import com.epam.aidial.core.Proxy;
import com.epam.aidial.core.ProxyContext;
import com.epam.aidial.core.service.InvitationService;
import com.epam.aidial.core.service.LockService;
import com.epam.aidial.core.service.ShareService;
import com.epam.aidial.core.storage.BlobStorage;
import com.epam.aidial.core.storage.ResourceDescription;
import com.epam.aidial.core.util.EtagHeader;
import com.epam.aidial.core.util.HttpException;
import com.epam.aidial.core.util.HttpStatus;
import io.vertx.core.Future;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class DeleteFileController extends AccessControlBaseController {

    private final ShareService shareService;
    private final InvitationService invitationService;
    private final LockService lockService;

    public DeleteFileController(Proxy proxy, ProxyContext context) {
        super(proxy, context, true);
        this.shareService = proxy.getShareService();
        this.invitationService = proxy.getInvitationService();
        this.lockService = proxy.getLockService();
    }

    @Override
    protected Future<?> handle(ResourceDescription resource, boolean hasWriteAccess) {
        if (resource.isFolder()) {
            return context.respond(HttpStatus.BAD_REQUEST, "Can't delete a folder");
        }

        String absoluteFilePath = resource.getAbsoluteFilePath();
        EtagHeader etag = EtagHeader.fromRequest(context.getRequest());

        BlobStorage storage = proxy.getStorage();
        Future<Void> result = proxy.getVertx().executeBlocking(() -> {
            String bucketName = resource.getBucketName();
            String bucketLocation = resource.getBucketLocation();
            try (LockService.Lock ignored = proxy.getLockService().lock(resource)) {
                etag.validate(() -> storage.getEtag(resource.getAbsoluteFilePath()));
                return lockService.underBucketLock(bucketLocation, () -> {
                    invitationService.cleanUpResourceLink(bucketName, bucketLocation, resource);
                    shareService.revokeSharedResource(bucketName, bucketLocation, resource);
                    storage.delete(absoluteFilePath);
                    return null;
                });
            }
        }, false);

        return result
                .onSuccess(success -> context.respond(HttpStatus.OK))
                .onFailure(error -> {
                    log.error("Failed to delete file  {}/{}", resource.getBucketName(), resource.getOriginalPath(), error);
                    if (error instanceof HttpException exception) {
                        context.respond(exception.getStatus(), exception.getMessage());
                    } else {
                        context.respond(HttpStatus.INTERNAL_SERVER_ERROR, error.getMessage());
                    }
                });
    }
}
