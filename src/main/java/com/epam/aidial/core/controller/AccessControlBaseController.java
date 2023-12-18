package com.epam.aidial.core.controller;

import com.epam.aidial.core.Proxy;
import com.epam.aidial.core.ProxyContext;
import com.epam.aidial.core.storage.BlobStorageUtil;
import com.epam.aidial.core.storage.ResourceDescription;
import com.epam.aidial.core.storage.ResourceType;
import com.epam.aidial.core.util.HttpStatus;
import io.vertx.core.Future;
import lombok.AllArgsConstructor;

@AllArgsConstructor
public abstract class AccessControlBaseController {

    final Proxy proxy;
    final ProxyContext context;


    public Future<?> handle(String bucket, String filePath) {
        String expectedUserBucket = BlobStorageUtil.buildUserBucket(context);
        String decryptedBucket = proxy.getEncryptionService().decrypt(bucket);

        if (!expectedUserBucket.equals(decryptedBucket)) {
            return context.respond(HttpStatus.FORBIDDEN, "You don't have an access to the bucket " + bucket);
        }

        ResourceDescription resource;
        try {
            resource = ResourceDescription.from(ResourceType.FILE, bucket, decryptedBucket, filePath);
        } catch (Exception ex) {
            return context.respond(HttpStatus.BAD_REQUEST, "Invalid file url provided");
        }

        return handle(resource);
    }

    protected abstract Future<?> handle(ResourceDescription resource);

}
