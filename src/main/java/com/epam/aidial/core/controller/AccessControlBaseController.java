package com.epam.aidial.core.controller;

import com.epam.aidial.core.Proxy;
import com.epam.aidial.core.ProxyContext;
import com.epam.aidial.core.storage.BlobStorageUtil;
import com.epam.aidial.core.storage.ResourceDescription;
import com.epam.aidial.core.storage.ResourceType;
import com.epam.aidial.core.util.HttpStatus;
import com.epam.aidial.core.util.UrlUtil;
import io.vertx.core.Future;
import lombok.AllArgsConstructor;

@AllArgsConstructor
public abstract class AccessControlBaseController {

    private static final String DEFAULT_RESOURCE_ERROR_MESSAGE = "Invalid resource url provided %s";

    final Proxy proxy;
    final ProxyContext context;

    /**
     * @param bucket url encoded bucket name
     * @param path url encoded resource path
     */
    public Future<?> handle(String folder, String bucket, String path) {
        String urlDecodedBucket = UrlUtil.decodePath(bucket);
        String expectedUserBucket = BlobStorageUtil.buildUserBucket(context);
        String decryptedBucket = proxy.getEncryptionService().decrypt(urlDecodedBucket);

        if (!expectedUserBucket.equals(decryptedBucket)) {
            return context.respond(HttpStatus.FORBIDDEN, "You don't have an access to the bucket " + bucket);
        }

        ResourceDescription resource;
        try {
            ResourceType type = ResourceType.fromFolder(folder);
            resource = ResourceDescription.fromEncoded(type, urlDecodedBucket, decryptedBucket, path);
        } catch (Exception ex) {
            String errorMessage = ex.getMessage() != null ? ex.getMessage() : DEFAULT_RESOURCE_ERROR_MESSAGE.formatted(path);
            return context.respond(HttpStatus.BAD_REQUEST, errorMessage);
        }

        return handle(resource);
    }

    protected abstract Future<?> handle(ResourceDescription resource);

}
