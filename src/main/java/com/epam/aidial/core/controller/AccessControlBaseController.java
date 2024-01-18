package com.epam.aidial.core.controller;

import com.epam.aidial.core.Proxy;
import com.epam.aidial.core.ProxyContext;
import com.epam.aidial.core.data.ResourceType;
import com.epam.aidial.core.storage.BlobStorageUtil;
import com.epam.aidial.core.storage.ResourceDescription;
import com.epam.aidial.core.util.HttpStatus;
import com.epam.aidial.core.util.UrlUtil;
import io.vertx.core.Future;
import lombok.AllArgsConstructor;

@AllArgsConstructor
public abstract class AccessControlBaseController {

    private static final String DEFAULT_RESOURCE_ERROR_MESSAGE = "Invalid file url provided %s";

    final Proxy proxy;
    final ProxyContext context;
    final boolean checkFullAccess;


    /**
     * @param bucket url encoded bucket name
     * @param filePath url encoded file path
     */
    public Future<?> handle(String bucket, String filePath) {
        ResourceType type = ResourceType.FILE;
        String urlDecodedBucket = UrlUtil.decodePath(bucket);
        String decryptedBucket = proxy.getEncryptionService().decrypt(urlDecodedBucket);
        boolean hasReadAccess = isSharedWithMe(type, bucket, filePath);
        boolean hasWriteAccess = hasWriteAccess(filePath, decryptedBucket);
        boolean hasAccess = checkFullAccess ? hasWriteAccess : hasReadAccess || hasWriteAccess;

        if (!hasAccess) {
            return context.respond(HttpStatus.FORBIDDEN, "You don't have an access to the bucket " + bucket);
        }

        ResourceDescription resource;
        try {
            resource = ResourceDescription.fromEncoded(type, urlDecodedBucket, decryptedBucket, filePath);
        } catch (Exception ex) {
            String errorMessage = ex.getMessage() != null ? ex.getMessage() : DEFAULT_RESOURCE_ERROR_MESSAGE.formatted(filePath);
            return context.respond(HttpStatus.BAD_REQUEST, errorMessage);
        }

        return handle(resource);
    }

    protected abstract Future<?> handle(ResourceDescription resource);

    protected boolean isSharedWithMe(ResourceType type, String bucket, String filePath) {
        String url = type.getGroup() + BlobStorageUtil.PATH_SEPARATOR + bucket + BlobStorageUtil.PATH_SEPARATOR + filePath;
        return context.getApiKeyData().getAttachedFiles().contains(url);
    }

    protected boolean hasWriteAccess(String filePath, String decryptedBucket) {
        String expectedUserBucket = BlobStorageUtil.buildUserBucket(context);
        if (expectedUserBucket.equals(decryptedBucket)) {
            return true;
        }
        String expectedAppDataBucket = BlobStorageUtil.buildAppDataBucket(context);
        if (expectedAppDataBucket != null && expectedAppDataBucket.equals(decryptedBucket)) {
            return filePath.startsWith(BlobStorageUtil.APPDATA_PATTERN.formatted(UrlUtil.encodePath(context.getSourceDeployment())));
        }
        return false;
    }

}
