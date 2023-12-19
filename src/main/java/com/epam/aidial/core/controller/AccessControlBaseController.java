package com.epam.aidial.core.controller;

import com.epam.aidial.core.Proxy;
import com.epam.aidial.core.ProxyContext;
import com.epam.aidial.core.storage.BlobStorageUtil;
import com.epam.aidial.core.storage.ResourceDescription;
import com.epam.aidial.core.storage.ResourceType;
import com.epam.aidial.core.util.HttpStatus;
import io.vertx.core.Future;
import lombok.AllArgsConstructor;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

@AllArgsConstructor
public abstract class AccessControlBaseController {

    private static final String DEFAULT_RESOURCE_ERROR_MESSAGE = "Invalid file url provided %s";

    final Proxy proxy;
    final ProxyContext context;


    /**
     * @param bucket url encoded bucket name
     * @param filePath url encoded file path
     */
    public Future<?> handle(String bucket, String filePath) {
        String urlDecodedBucket = URLDecoder.decode(bucket, StandardCharsets.UTF_8);
        String expectedUserBucket = BlobStorageUtil.buildUserBucket(context);
        String decryptedBucket = proxy.getEncryptionService().decrypt(urlDecodedBucket);

        if (!expectedUserBucket.equals(decryptedBucket)) {
            return context.respond(HttpStatus.FORBIDDEN, "You don't have an access to the bucket " + bucket);
        }

        ResourceDescription resource;
        try {
            resource = ResourceDescription.from(ResourceType.FILE, urlDecodedBucket, decryptedBucket, filePath);
        } catch (Exception ex) {
            String errorMessage = ex.getMessage() != null ? ex.getMessage() : DEFAULT_RESOURCE_ERROR_MESSAGE.formatted(filePath);
            return context.respond(HttpStatus.BAD_REQUEST, errorMessage);
        }

        return handle(resource);
    }

    protected abstract Future<?> handle(ResourceDescription resource);

}
