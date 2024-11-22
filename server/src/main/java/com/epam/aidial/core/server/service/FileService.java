package com.epam.aidial.core.server.service;

import com.epam.aidial.core.storage.resource.ResourceDescriptor;
import com.epam.aidial.core.storage.service.LockService;
import com.epam.aidial.core.storage.service.ResourceService;
import com.epam.aidial.core.storage.util.EtagHeader;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import lombok.AllArgsConstructor;

@AllArgsConstructor
public class FileService {

    private final Vertx vertx;

    private final ResourceService resourceService;

    private final LockService lockService;

    private final InvitationService invitationService;

    private final ShareService shareService;

    public Future<Void> deleteFile(ResourceDescriptor resource, EtagHeader etag) {
        return vertx.executeBlocking(() -> deleteFileSync(resource, etag), false);
    }

    Void deleteFileSync(ResourceDescriptor resource, EtagHeader etag) {
        String bucketName = resource.getBucketName();
        String bucketLocation = resource.getBucketLocation();
        return lockService.underBucketLock(bucketLocation, () -> {
            invitationService.cleanUpResourceLink(bucketName, bucketLocation, resource);
            shareService.revokeSharedResource(bucketName, bucketLocation, resource);
            resourceService.deleteResource(resource, etag);
            return null;
        });
    }
}
